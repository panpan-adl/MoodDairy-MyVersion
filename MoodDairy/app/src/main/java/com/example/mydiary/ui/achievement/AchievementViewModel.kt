package com.example.mydiary.ui.achievement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.Achievement
import com.example.mydiary.data.models.ShopItem
import com.example.mydiary.data.models.UserPoints
import com.example.mydiary.data.repository.AchievementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository
) : ViewModel() {

    val achievements: StateFlow<List<Achievement>> = achievementRepository.achievements
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val userPoints: StateFlow<UserPoints> = achievementRepository.userPoints
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPoints()
        )

    val newlyUnlockedAchievements: StateFlow<List<Achievement>> = achievementRepository.newlyUnlockedAchievements
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val purchasedItems = achievementRepository.purchasedItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val lastCheckInDate = achievementRepository.lastCheckInDate
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun clearNewlyUnlockedAchievements() {
        achievementRepository.clearNewlyUnlockedAchievements()
    }

    fun purchaseItem(item: ShopItem): Boolean {
        return achievementRepository.purchaseItem(item)
    }

    // 初始化成就进度
    fun initializeProgress(
        diaryCount: Int,
        streakDays: Int,
        chatCount: Int,
        drawingCount: Int,
        testCompleteCount: Int
    ) {
        achievementRepository.initializeProgress(
            diaryCount = diaryCount,
            streakDays = streakDays,
            chatCount = chatCount,
            drawingCount = drawingCount,
            testCompleteCount = testCompleteCount
        )
    }

    // 添加积分（用于各种获得积分的操作）
    fun addPoints(points: Int, reason: String) {
        achievementRepository.addPoints(points, reason)
    }

    /** 每日签到，成功返回 true */
    fun checkIn(): Boolean = achievementRepository.checkIn()

    fun getShopItems(): List<ShopItem> = achievementRepository.getShopItems()
}
