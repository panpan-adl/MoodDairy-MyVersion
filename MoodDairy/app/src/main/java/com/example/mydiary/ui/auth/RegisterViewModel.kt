package com.example.mydiary.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.User
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 注册页面ViewModel
 * 
 * 职责：
 * - 管理注册状态
 * - 处理表单验证（用户名长度、密码长度、确认密码）
 * - 执行注册操作
 * 
 * 验证需求: 2.1, 2.4, 2.5, 2.6
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    
    // 用户名输入
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()
    
    // 密码输入
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    
    // 确认密码输入
    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()
    
    // 注册状态
    private val _registerState = MutableStateFlow<RegisterState>(RegisterState.Idle)
    val registerState: StateFlow<RegisterState> = _registerState.asStateFlow()
    
    // 用户名错误信息
    val usernameError: StateFlow<String?> = _username.map { username ->
        when {
            username.isEmpty() -> null
            username.length < 1 -> "用户名不能为空"
            username.length > 50 -> "用户名不能超过50个字符"
            else -> null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
    
    // 密码错误信息
    val passwordError: StateFlow<String?> = _password.map { password ->
        when {
            password.isEmpty() -> null
            password.length < 6 -> "密码至少需要6个字符"
            else -> null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
    
    // 确认密码错误信息
    val confirmPasswordError: StateFlow<String?> = combine(
        _password,
        _confirmPassword
    ) { password, confirmPassword ->
        when {
            confirmPassword.isEmpty() -> null
            password != confirmPassword -> "两次输入的密码不一致"
            else -> null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
    
    // 表单验证状态
    val isFormValid: StateFlow<Boolean> = combine(
        _username,
        _password,
        _confirmPassword,
        usernameError,
        passwordError,
        confirmPasswordError
    ) { flows: Array<Any?> ->
        val username = flows[0] as String
        val password = flows[1] as String
        val confirmPassword = flows[2] as String
        val usernameErr = flows[3] as String?
        val passwordErr = flows[4] as String?
        val confirmPasswordErr = flows[5] as String?
        
        username.isNotBlank() &&
        password.isNotBlank() &&
        confirmPassword.isNotBlank() &&
        usernameErr == null &&
        passwordErr == null &&
        confirmPasswordErr == null
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    
    /**
     * 用户名输入变化
     * 
     * @param value 新的用户名
     */
    fun onUsernameChange(value: String) {
        _username.value = value
    }
    
    /**
     * 密码输入变化
     * 
     * @param value 新的密码
     */
    fun onPasswordChange(value: String) {
        _password.value = value
    }
    
    /**
     * 确认密码输入变化
     * 
     * @param value 新的确认密码
     */
    fun onConfirmPasswordChange(value: String) {
        _confirmPassword.value = value
    }
    
    /**
     * 执行注册
     * 
     * 验证需求: 2.1
     */
    fun register() {
        viewModelScope.launch {
            userRepository.register(
                username = _username.value.trim(),
                password = _password.value,
                nickname = null
            ).collect { result ->
                when (result) {
                    is ApiResult.Loading -> {
                        _registerState.value = RegisterState.Loading
                    }
                    is ApiResult.Success -> {
                        _registerState.value = RegisterState.Success(result.data)
                    }
                    is ApiResult.Error -> {
                        _registerState.value = RegisterState.Error(
                            result.message ?: "注册失败，请重试"
                        )
                    }
                }
            }
        }
    }
    
    /**
     * 重置注册状态
     */
    fun resetState() {
        _registerState.value = RegisterState.Idle
    }
}

/**
 * 注册状态
 */
sealed class RegisterState {
    /**
     * 空闲状态
     */
    object Idle : RegisterState()
    
    /**
     * 加载中
     */
    object Loading : RegisterState()
    
    /**
     * 注册成功
     * 
     * @param user 用户信息
     */
    data class Success(val user: User) : RegisterState()
    
    /**
     * 注册失败
     * 
     * @param message 错误信息
     */
    data class Error(val message: String) : RegisterState()
}
