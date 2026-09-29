package com.example.mydiary.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.models.User
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.repository.GrowthPortraitRepository
import com.example.mydiary.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 个人主页ViewModel
 *
 * 职责：
 * - 管理用户资料加载状态
 * - 处理登出功能
 * - 提供用户数据给UI层
 *
 * 验证需求: 3.1, 3.2, 3.3, 6.1, 6.2
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val growthPortraitRepository: GrowthPortraitRepository,
) : ViewModel() {

    // 个人主页状态
    private val _profileState = MutableStateFlow<ProfileState>(ProfileState.Idle)
    val profileState: StateFlow<ProfileState> = _profileState.asStateFlow()

    // 当前用户数据
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _growthPortrait = MutableStateFlow<GrowthPortraitResponse?>(null)
    val growthPortrait: StateFlow<GrowthPortraitResponse?> = _growthPortrait.asStateFlow()

    init {
        // 初始化时加载用户资料
        loadProfile()
    }

    /**
     * 加载用户资料
     *
     * 验证需求: 3.1, 3.2, 3.3
     *
     * @param forceRefresh 是否强制刷新（从编辑页面返回时使用）
     */
    fun loadProfile(forceRefresh: Boolean = false) {
        // 如果已有数据且不是强制刷新，跳过加载
        if (_user.value != null && _profileState.value == ProfileState.Success && !forceRefresh) {
            return
        }

        viewModelScope.launch {
            // 首先尝试从本地会话获取用户信息
            val currentUser = userRepository.getCurrentUser()

            if (currentUser == null) {
                _profileState.value = ProfileState.Error("未登录")
                return@launch
            }

            // 设置当前用户（立即显示本地数据，避免闪烁）
            _user.value = currentUser
            loadGrowthPortrait()

            // 只有首次加载时才显示 Loading 状态
            val isFirstLoad = _profileState.value == ProfileState.Idle
            if (isFirstLoad) {
                _profileState.value = ProfileState.Loading
            }

            // 从服务器获取最新的用户信息（静默刷新）
            val userId = currentUser.id
            userRepository.getUser(userId).collect { result ->
                when (result) {
                    is ApiResult.Loading -> {
                        // 只有首次加载才显示 Loading
                        if (isFirstLoad) {
                            _profileState.value = ProfileState.Loading
                        }
                    }
                    is ApiResult.Success -> {
                        _user.value = result.data
                        _profileState.value = ProfileState.Success
                    }
                    is ApiResult.Error -> {
                        // 如果已有数据，保持 Success 状态；否则显示错误
                        if (_user.value != null) {
                            _profileState.value = ProfileState.Success
                        } else {
                            _profileState.value = ProfileState.Error(result.message)
                        }
                    }
                }
            }
        }
    }

    /**
     * 用户登出
     *
     * 验证需求: 6.1, 6.2
     */
    fun logout() {
        userRepository.logout()
        _user.value = null
        _growthPortrait.value = null
        _profileState.value = ProfileState.LoggedOut
    }

    private fun loadGrowthPortrait() {
        viewModelScope.launch {
            growthPortraitRepository.getGrowthPortrait(days = 7).collect { result ->
                if (result is ApiResult.Success) {
                    _growthPortrait.value = result.data
                }
            }
        }
    }
}

/**
 * 个人主页状态
 */
sealed class ProfileState {
    object Idle : ProfileState()
    object Loading : ProfileState()
    object Success : ProfileState()
    data class Error(val message: String) : ProfileState()
    object LoggedOut : ProfileState()
}
