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
 * 登录页面ViewModel
 * 
 * 职责：
 * - 管理登录状态
 * - 处理表单验证
 * - 执行登录操作
 * - 检查现有会话
 * 
 * 验证需求: 1.1, 1.4, 1.5, 1.6
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    
    // 用户名输入
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()
    
    // 密码输入
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    
    // 登录状态
    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()
    
    // 表单验证状态
    val isFormValid: StateFlow<Boolean> = combine(
        _username,
        _password
    ) { username, password ->
        username.isNotBlank() && password.isNotBlank()
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
     * 执行登录
     * 
     * 验证需求: 1.1
     */
    fun login() {
        viewModelScope.launch {
            // 前置校验：与后端要求一致（密码至少6位），避免触发422校验错误
            if (_password.value.length < 6) {
                _loginState.value = LoginState.Error("密码至少需要 6 位，请重新输入")
                return@launch
            }

            userRepository.login(
                username = _username.value.trim(),
                password = _password.value
            ).collect { result ->
                when (result) {
                    is ApiResult.Loading -> {
                        _loginState.value = LoginState.Loading
                    }
                    is ApiResult.Success -> {
                        _loginState.value = LoginState.Success(result.data)
                    }
                    is ApiResult.Error -> {
                        _loginState.value = LoginState.Error(
                            result.message ?: "登录失败，请重试"
                        )
                    }
                }
            }
        }
    }
    
    /**
     * 检查现有会话
     * 如果已登录，自动跳转
     * 
     * 验证需求: 1.4
     */
    fun checkExistingSession() {
        if (userRepository.isLoggedIn()) {
            val user = userRepository.getCurrentUser()
            if (user != null) {
                _loginState.value = LoginState.Success(user)
            }
        }
    }
    
    /**
     * 重置登录状态
     */
    fun resetState() {
        _loginState.value = LoginState.Idle
    }
}

/**
 * 登录状态
 */
sealed class LoginState {
    /**
     * 空闲状态
     */
    object Idle : LoginState()
    
    /**
     * 加载中
     */
    object Loading : LoginState()
    
    /**
     * 登录成功
     * 
     * @param user 用户信息
     */
    data class Success(val user: User) : LoginState()
    
    /**
     * 登录失败
     * 
     * @param message 错误信息
     */
    data class Error(val message: String) : LoginState()
}
