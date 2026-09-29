package com.example.mydiary.ui.profile

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.UpdateUserRequest
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 编辑资料ViewModel
 * 
 * 职责：
 * - 管理资料编辑状态
 * - 处理表单验证（邮箱格式、字段长度）
 * - 处理头像上传
 * - 保存用户资料更新
 * 
 * 验证需求: 4.1, 4.2, 4.5, 4.6, 5.1, 5.2
 */
@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    
    // 编辑状态
    private val _editState = MutableStateFlow<EditState>(EditState.Idle)
    val editState: StateFlow<EditState> = _editState.asStateFlow()
    
    // 表单字段
    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()
    
    private val _phone = MutableStateFlow("")
    val phone: StateFlow<String> = _phone.asStateFlow()
    
    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()
    
    private val _gender = MutableStateFlow(0)
    val gender: StateFlow<Int> = _gender.asStateFlow()
    
    private val _birthday = MutableStateFlow<String?>(null)
    val birthday: StateFlow<String?> = _birthday.asStateFlow()
    
    // 头像相关
    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()
    
    private val _avatarUploadState = MutableStateFlow<AvatarUploadState>(AvatarUploadState.Idle)
    val avatarUploadState: StateFlow<AvatarUploadState> = _avatarUploadState.asStateFlow()
    
    // 验证错误
    private val _emailError = MutableStateFlow<String?>(null)
    val emailError: StateFlow<String?> = _emailError.asStateFlow()
    
    private val _nicknameError = MutableStateFlow<String?>(null)
    val nicknameError: StateFlow<String?> = _nicknameError.asStateFlow()
    
    private val _phoneError = MutableStateFlow<String?>(null)
    val phoneError: StateFlow<String?> = _phoneError.asStateFlow()
    
    // 用户ID
    private var userId: Long? = null
    
    init {
        loadCurrentProfile()
    }
    
    /**
     * 加载当前用户资料
     * 
     * 验证需求: 4.1
     */
    fun loadCurrentProfile() {
        viewModelScope.launch {
            val currentUser = userRepository.getCurrentUser()
            
            if (currentUser == null) {
                _editState.value = EditState.Error("未登录")
                return@launch
            }
            
            // 设置用户ID
            userId = currentUser.id
            
            // 预填表单
            _nickname.value = currentUser.nickname ?: ""
            _phone.value = currentUser.phone ?: ""
            _email.value = currentUser.email ?: ""
            _gender.value = currentUser.gender
            _birthday.value = currentUser.birthday
            _avatarUrl.value = currentUser.avatar
        }
    }
    
    /**
     * 昵称变化
     * 
     * 验证需求: 4.5
     */
    fun onNicknameChange(value: String) {
        _nickname.value = value
        
        // 验证昵称长度
        _nicknameError.value = when {
            value.length > 50 -> "昵称不能超过50个字符"
            else -> null
        }
    }
    
    /**
     * 手机号变化
     * 
     * 验证需求: 4.5
     */
    fun onPhoneChange(value: String) {
        _phone.value = value
        
        // 验证手机号长度
        _phoneError.value = when {
            value.length > 20 -> "手机号不能超过20个字符"
            else -> null
        }
    }
    
    /**
     * 邮箱变化
     * 
     * 验证需求: 4.6
     */
    fun onEmailChange(value: String) {
        _email.value = value
        
        // 验证邮箱格式
        _emailError.value = when {
            value.isNotEmpty() && !isValidEmail(value) -> "邮箱格式不正确"
            value.length > 100 -> "邮箱不能超过100个字符"
            else -> null
        }
    }
    
    /**
     * 性别变化
     */
    fun onGenderChange(value: Int) {
        _gender.value = value
    }
    
    /**
     * 生日变化
     */
    fun onBirthdayChange(value: String?) {
        _birthday.value = value
    }
    
    /**
     * 保存资料
     * 
     * 验证需求: 4.2
     */
    fun saveProfile() {
        viewModelScope.launch {
            // 验证表单
            if (!validateForm()) {
                return@launch
            }
            
            val currentUserId = userId
            if (currentUserId == null) {
                _editState.value = EditState.Error("用户ID无效")
                return@launch
            }
            
            _editState.value = EditState.Loading
            
            // 创建更新请求
            val request = UpdateUserRequest(
                nickname = _nickname.value.ifEmpty { null },
                phone = _phone.value.ifEmpty { null },
                email = _email.value.ifEmpty { null },
                gender = _gender.value,
                birthday = _birthday.value
            )
            
            // 调用Repository更新
            userRepository.updateUser(currentUserId, request).collect { result ->
                when (result) {
                    is ApiResult.Loading -> {
                        _editState.value = EditState.Loading
                    }
                    is ApiResult.Success -> {
                        _editState.value = EditState.Success
                    }
                    is ApiResult.Error -> {
                        _editState.value = EditState.Error(result.message)
                    }
                }
            }
        }
    }
    
    /**
     * 上传头像
     * 
     * 验证需求: 5.1, 5.2
     * 
     * @param imageFile 头像图片文件
     */
    fun uploadAvatar(imageFile: File) {
        viewModelScope.launch {
            val currentUserId = userId
            if (currentUserId == null) {
                _avatarUploadState.value = AvatarUploadState.Error("用户ID无效")
                return@launch
            }
            
            _avatarUploadState.value = AvatarUploadState.Loading
            
            // 调用Repository上传头像
            userRepository.uploadAvatar(currentUserId, imageFile).collect { result ->
                when (result) {
                    is ApiResult.Loading -> {
                        _avatarUploadState.value = AvatarUploadState.Loading
                    }
                    is ApiResult.Success -> {
                        _avatarUrl.value = result.data
                        _avatarUploadState.value = AvatarUploadState.Success(result.data)
                    }
                    is ApiResult.Error -> {
                        _avatarUploadState.value = AvatarUploadState.Error(result.message)
                    }
                }
            }
        }
    }
    
    /**
     * 验证表单
     * 
     * @return true表示验证通过，false表示验证失败
     */
    private fun validateForm(): Boolean {
        var isValid = true
        
        // 验证昵称
        if (_nickname.value.length > 50) {
            _nicknameError.value = "昵称不能超过50个字符"
            isValid = false
        }
        
        // 验证手机号
        if (_phone.value.length > 20) {
            _phoneError.value = "手机号不能超过20个字符"
            isValid = false
        }
        
        // 验证邮箱
        if (_email.value.isNotEmpty() && !isValidEmail(_email.value)) {
            _emailError.value = "邮箱格式不正确"
            isValid = false
        }
        
        if (_email.value.length > 100) {
            _emailError.value = "邮箱不能超过100个字符"
            isValid = false
        }
        
        return isValid
    }
    
    /**
     * 验证邮箱格式
     * 
     * 验证需求: 4.6
     * 
     * @param email 邮箱地址
     * @return true表示格式正确，false表示格式错误
     */
    private fun isValidEmail(email: String): Boolean {
        return Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
    
    /**
     * 重置状态
     */
    fun resetState() {
        _editState.value = EditState.Idle
    }
    
    /**
     * 重置头像上传状态
     */
    fun resetAvatarUploadState() {
        _avatarUploadState.value = AvatarUploadState.Idle
    }
}

/**
 * 编辑状态
 */
sealed class EditState {
    object Idle : EditState()
    object Loading : EditState()
    object Success : EditState()
    data class Error(val message: String) : EditState()
}

/**
 * 头像上传状态
 */
sealed class AvatarUploadState {
    object Idle : AvatarUploadState()
    object Loading : AvatarUploadState()
    data class Success(val avatarUrl: String) : AvatarUploadState()
    data class Error(val message: String) : AvatarUploadState()
}
