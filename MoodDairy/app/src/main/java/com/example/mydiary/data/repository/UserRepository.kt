package com.example.mydiary.data.repository

import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.models.*
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户数据仓库
 * 
 * 职责：
 * - 封装用户相关的网络调用
 * - 管理用户会话（通过SessionManager）
 * - 提供统一的用户数据访问接口
 * - 处理数据转换（网络模型 <-> 领域模型）
 * 
 * 验证需求: 1.1, 1.3, 2.1, 2.3, 4.2, 5.2, 6.2
 */
@Singleton
class UserRepository @Inject constructor(
    private val apiService: DiaryApiService,
    private val sessionManager: SessionManager
) {
    
    /**
     * 用户登录
     * 
     * 验证需求: 1.1, 1.3
     * 
     * @param username 用户名
     * @param password 密码
     * @return Flow<ApiResult<User>> 用户数据流
     */
    fun login(
        username: String,
        password: String
    ): Flow<ApiResult<User>> = flow {
        emit(ApiResult.Loading)
        
        // 创建登录请求
        val request = LoginRequest(
            username = username,
            password = password
        )
        
        // 调用API
        val result = SafeApiCall.execute {
            apiService.login(request)
        }
        
        // 转换结果
        when (result) {
            is ApiResult.Success -> {
                val user = result.data.toDomain()
                val token = result.data.accessToken
                if (token.isNullOrBlank()) {
                    emit(ApiResult.Error(-1, "登录响应缺少访问令牌"))
                    return@flow
                }
                sessionManager.saveSession(user, token)
                emit(ApiResult.Success(user))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 不应该到达这里
            }
        }
    }
    
    /**
     * 用户注册
     * 
     * 验证需求: 2.1, 2.3
     * 
     * @param username 用户名
     * @param password 密码
     * @param nickname 昵称（可选）
     * @return Flow<ApiResult<User>> 用户数据流
     */
    fun register(
        username: String,
        password: String,
        nickname: String? = null
    ): Flow<ApiResult<User>> = flow {
        emit(ApiResult.Loading)
        
        // 创建注册请求
        val request = RegisterRequest(
            username = username,
            password = password,
            nickname = nickname
        )
        
        // 调用API
        val result = SafeApiCall.execute {
            apiService.register(request)
        }
        
        // 转换结果
        when (result) {
            is ApiResult.Success -> {
                val user = result.data.toDomain()
                val token = result.data.accessToken
                if (token.isNullOrBlank()) {
                    emit(ApiResult.Error(-1, "注册响应缺少访问令牌"))
                    return@flow
                }
                sessionManager.saveSession(user, token)
                emit(ApiResult.Success(user))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 不应该到达这里
            }
        }
    }
    
    /**
     * 获取用户信息
     * 
     * 验证需求: 3.1, 3.2
     * 
     * @param userId 用户ID
     * @return Flow<ApiResult<User>> 用户数据流
     */
    fun getUser(
        userId: Long
    ): Flow<ApiResult<User>> = flow {
        emit(ApiResult.Loading)
        
        // 调用API
        val result = SafeApiCall.execute {
            apiService.getUser(userId)
        }
        
        // 转换结果
        when (result) {
            is ApiResult.Success -> {
                val user = result.data.toDomain()
                // 更新本地会话
                sessionManager.updateSession(user)
                emit(ApiResult.Success(user))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 不应该到达这里
            }
        }
    }
    
    /**
     * 更新用户信息
     * 
     * 验证需求: 4.2
     * 
     * @param userId 用户ID
     * @param request 更新请求
     * @return Flow<ApiResult<User>> 更新后的用户数据流
     */
    fun updateUser(
        userId: Long,
        request: UpdateUserRequest
    ): Flow<ApiResult<User>> = flow {
        emit(ApiResult.Loading)
        
        // 调用API
        val result = SafeApiCall.execute {
            apiService.updateUser(userId, request)
        }
        
        // 转换结果
        when (result) {
            is ApiResult.Success -> {
                val user = result.data.toDomain()
                // 更新本地会话
                sessionManager.updateSession(user)
                emit(ApiResult.Success(user))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 不应该到达这里
            }
        }
    }
    
    /**
     * 上传用户头像
     * 
     * 验证需求: 5.2
     * 
     * @param userId 用户ID
     * @param imageFile 头像图片文件
     * @return Flow<ApiResult<String>> 头像URL数据流
     */
    fun uploadAvatar(
        userId: Long,
        imageFile: File
    ): Flow<ApiResult<String>> = flow {
        emit(ApiResult.Loading)
        
        // 创建MultipartBody.Part
        val requestBody = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData(
            "file",
            imageFile.name,
            requestBody
        )
        
        // 调用API
        val result = SafeApiCall.execute {
            apiService.uploadAvatar(userId, filePart)
        }
        
        // 转换结果
        when (result) {
            is ApiResult.Success -> {
                val avatarUrl = resolveUserAvatarUrl(result.data.avatarUrl) ?: result.data.avatarUrl
                // 更新本地会话中的头像URL
                val currentUser = sessionManager.getSession()
                if (currentUser != null) {
                    val updatedUser = currentUser.copy(avatar = avatarUrl)
                    sessionManager.updateSession(updatedUser)
                }
                emit(ApiResult.Success(avatarUrl))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 不应该到达这里
            }
        }
    }
    
    /**
     * 用户登出
     * 清除本地会话
     * 
     * 验证需求: 6.2
     */
    fun logout() {
        sessionManager.clearSession()
    }
    
    /**
     * 获取当前登录用户
     * 从本地会话获取
     * 
     * 验证需求: 7.2
     * 
     * @return 当前用户，如果未登录则返回null
     */
    fun getCurrentUser(): User? {
        return sessionManager.getSession()?.let { user ->
            user.copy(avatar = resolveUserAvatarUrl(user.avatar))
        }
    }
    
    /**
     * 检查是否已登录
     * 
     * 验证需求: 7.2
     * 
     * @return true表示已登录，false表示未登录
     */
    fun isLoggedIn(): Boolean {
        return sessionManager.isLoggedIn()
    }
    
    /**
     * 获取当前用户ID
     * 
     * @return 用户ID，如果未登录则返回null
     */
    fun getCurrentUserId(): Long? {
        return sessionManager.getUserId()
    }
}
