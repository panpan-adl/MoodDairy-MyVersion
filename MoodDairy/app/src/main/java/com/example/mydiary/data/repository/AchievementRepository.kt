package com.example.mydiary.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.*
import com.google.gson.Gson
import java.util.Calendar
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("achievement_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _achievements = MutableStateFlow<List<Achievement>>(emptyList())
    val achievements: StateFlow<List<Achievement>> = _achievements.asStateFlow()

    private val _userPoints = MutableStateFlow(UserPoints())
    val userPoints: StateFlow<UserPoints> = _userPoints.asStateFlow()

    private val _purchasedItems = MutableStateFlow<List<PurchasedItem>>(emptyList())
    val purchasedItems: StateFlow<List<PurchasedItem>> = _purchasedItems.asStateFlow()

    private val _newlyUnlockedAchievements = MutableStateFlow<List<Achievement>>(emptyList())
    val newlyUnlockedAchievements: StateFlow<List<Achievement>> = _newlyUnlockedAchievements.asStateFlow()

    private val _lastCheckInDate = MutableStateFlow<Long?>(null)
    val lastCheckInDate: StateFlow<Long?> = _lastCheckInDate.asStateFlow()

    companion object {
        private const val KEY_LAST_CHECKIN = "last_checkin_date"
        const val DAILY_CHECKIN_POINTS = 10
    }

    init {
        loadData()
    }

    private fun isSameDay(ms1: Long, ms2: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = ms1 }
        val c2 = Calendar.getInstance().apply { timeInMillis = ms2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun loadData() {
        // 加载成就数据
        val achievementsJson = prefs.getString("achievements", null)
        val savedAchievements = if (achievementsJson != null) {
            try {
                val type = object : TypeToken<List<Achievement>>() {}.type
                gson.fromJson<List<Achievement>>(achievementsJson, type)
            } catch (e: Exception) {
                null
            }
        } else null

        _achievements.value = savedAchievements ?: getDefaultAchievements()

        // 加载积分数据
        val pointsJson = prefs.getString("user_points", null)
        val savedPoints = if (pointsJson != null) {
            try {
                gson.fromJson(pointsJson, UserPoints::class.java)
            } catch (e: Exception) {
                null
            }
        } else null
        _userPoints.value = savedPoints ?: UserPoints()

        // 加载已购买物品
        val purchasedJson = prefs.getString("purchased_items", null)
        val savedPurchased = if (purchasedJson != null) {
            try {
                val type = object : TypeToken<List<PurchasedItem>>() {}.type
                gson.fromJson<List<PurchasedItem>>(purchasedJson, type)
            } catch (e: Exception) {
                null
            }
        } else null
        _purchasedItems.value = savedPurchased ?: emptyList()

        _lastCheckInDate.value = prefs.getLong(KEY_LAST_CHECKIN, 0L).takeIf { it > 0 }
    }

    private fun saveData() {
        prefs.edit()
            .putString("achievements", gson.toJson(_achievements.value))
            .putString("user_points", gson.toJson(_userPoints.value))
            .putString("purchased_items", gson.toJson(_purchasedItems.value))
            .putLong(KEY_LAST_CHECKIN, _lastCheckInDate.value ?: 0L)
            .apply()
    }

    /** 每日签到，签到成功返回 true，今日已签到返回 false */
    fun checkIn(): Boolean {
        val now = System.currentTimeMillis()
        _lastCheckInDate.value?.let { last ->
            if (isSameDay(last, now)) return false
        }
        _lastCheckInDate.value = now
        addPoints(DAILY_CHECKIN_POINTS, "每日签到")
        saveData()
        return true
    }

    private fun getDefaultAchievements(): List<Achievement> = listOf(
        // 日记成就
        Achievement(
            id = "diary_1",
            title = "日记新人",
            description = "写下你的第一篇日记",
            iconName = "edit_note",
            type = AchievementType.DIARY_COUNT,
            requirement = 1,
            pointsReward = 10
        ),
        Achievement(
            id = "diary_7",
            title = "一周记",
            description = "连续7天写日记",
            iconName = "event_note",
            type = AchievementType.STREAK,
            requirement = 7,
            pointsReward = 50
        ),
        Achievement(
            id = "diary_30",
            title = "月度达人",
            description = "累计写满30篇日记",
            iconName = "book",
            type = AchievementType.DIARY_COUNT,
            requirement = 30,
            pointsReward = 100
        ),
        Achievement(
            id = "diary_100",
            title = "日记大师",
            description = "累计写满100篇日记",
            iconName = "military_tech",
            type = AchievementType.DIARY_COUNT,
            requirement = 100,
            pointsReward = 300
        ),

        // 对话成就
        Achievement(
            id = "chat_1",
            title = "初次交谈",
            description = "和AI助手进行第一次对话",
            iconName = "chat",
            type = AchievementType.CHAT_COUNT,
            requirement = 1,
            pointsReward = 10
        ),
        Achievement(
            id = "chat_50",
            title = "聊天达人",
            description = "累计对话50次",
            iconName = "forum",
            type = AchievementType.CHAT_COUNT,
            requirement = 50,
            pointsReward = 100
        ),

        // 绘画成就
        Achievement(
            id = "drawing_1",
            title = "创意起步",
            description = "生成你的第一幅AI画作",
            iconName = "palette",
            type = AchievementType.DRAWING_COUNT,
            requirement = 1,
            pointsReward = 20
        ),
        Achievement(
            id = "drawing_10",
            title = "小小艺术家",
            description = "累计生成10幅画作",
            iconName = "brush",
            type = AchievementType.DRAWING_COUNT,
            requirement = 10,
            pointsReward = 80
        ),

        // 测试成就
        Achievement(
            id = "test_1",
            title = "自我探索",
            description = "完成第一个心理测试",
            iconName = "psychology",
            type = AchievementType.TEST_COMPLETE,
            requirement = 1,
            pointsReward = 30
        ),
        Achievement(
            id = "test_5",
            title = "了解自己",
            description = "累计完成5个心理测试",
            iconName = "self_improvement",
            type = AchievementType.TEST_COMPLETE,
            requirement = 5,
            pointsReward = 150
        ),

        // 积分成就
        Achievement(
            id = "points_100",
            title = "积分达人",
            description = "累计获得100积分",
            iconName = "star",
            type = AchievementType.POINTS_MILESTONE,
            requirement = 100,
            pointsReward = 0
        ),
        Achievement(
            id = "points_500",
            title = "积分富豪",
            description = "累计获得500积分",
            iconName = "workspace_premium",
            type = AchievementType.POINTS_MILESTONE,
            requirement = 500,
            pointsReward = 0
        )
    )

    fun clearNewlyUnlockedAchievements() {
        _newlyUnlockedAchievements.value = emptyList()
    }

    // 检查并更新成就进度
    fun checkAchievements(
        diaryCount: Int = 0,
        streakDays: Int = 0,
        chatCount: Int = 0,
        drawingCount: Int = 0,
        testCompleteCount: Int = 0,
        totalPoints: Int = 0
    ) {
        val currentAchievements = _achievements.value.toMutableList()
        val newlyUnlocked = mutableListOf<Achievement>()
        var pointsToAdd = 0

        for (achievement in currentAchievements) {
            if (achievement.status == AchievementStatus.UNLOCKED) continue

            val progress = when (achievement.type) {
                AchievementType.DIARY_COUNT -> diaryCount
                AchievementType.STREAK -> streakDays
                AchievementType.CHAT_COUNT -> chatCount
                AchievementType.DRAWING_COUNT -> drawingCount
                AchievementType.TEST_COMPLETE -> testCompleteCount
                AchievementType.POINTS_MILESTONE -> totalPoints
                AchievementType.SPECIAL -> 0
            }

            val wasComplete = achievement.currentProgress >= achievement.requirement
            achievement.currentProgress = progress

            if (!wasComplete && progress >= achievement.requirement) {
                achievement.status = AchievementStatus.UNLOCKED
                achievement.unlockedAt = System.currentTimeMillis()
                if (achievement.pointsReward > 0) {
                    pointsToAdd += achievement.pointsReward
                }
                newlyUnlocked.add(achievement)
            }
        }

        _achievements.value = currentAchievements

        if (pointsToAdd > 0) {
            addPoints(pointsToAdd, "成就奖励")
        }

        if (newlyUnlocked.isNotEmpty()) {
            _newlyUnlockedAchievements.value = newlyUnlocked
        }

        saveData()
    }

    // 添加积分
    fun addPoints(points: Int, reason: String) {
        val currentPoints = _userPoints.value
        _userPoints.value = currentPoints.copy(
            totalPoints = currentPoints.totalPoints + points,
            availablePoints = currentPoints.availablePoints + points,
            lastEarnedAt = System.currentTimeMillis()
        )
        saveData()
    }

    // 消费积分
    fun spendPoints(points: Int, reason: String): Boolean {
        val currentPoints = _userPoints.value
        if (currentPoints.availablePoints < points) {
            return false
        }
        _userPoints.value = currentPoints.copy(
            availablePoints = currentPoints.availablePoints - points,
            earnedPoints = currentPoints.earnedPoints + points
        )
        saveData()
        return true
    }

    // 购买商城物品
    fun purchaseItem(item: ShopItem): Boolean {
        if (!spendPoints(item.price, "购买:${item.name}")) {
            return false
        }
        val currentPurchased = _purchasedItems.value.toMutableList()
        currentPurchased.add(PurchasedItem(item.id))
        _purchasedItems.value = currentPurchased
        saveData()
        return true
    }

    // 检查物品是否已购买
    fun isItemPurchased(itemId: String): Boolean {
        return _purchasedItems.value.any { it.itemId == itemId }
    }

    // 获取积分商城物品列表
    fun getShopItems(): List<ShopItem> = listOf(
        // 头像
        ShopItem(
            id = "avatar_1",
            name = "温暖阳光",
            description = "阳光开朗的头像框",
            iconName = "wb_sunny",
            price = 50,
            category = ShopCategory.AVATAR
        ),
        ShopItem(
            id = "avatar_2",
            name = "星空漫步",
            description = "梦幻星空头像框",
            iconName = "nightlight",
            price = 80,
            category = ShopCategory.AVATAR
        ),
        ShopItem(
            id = "avatar_3",
            name = "樱花烂漫",
            description = "春日樱花头像框",
            iconName = "local_florist",
            price = 100,
            category = ShopCategory.AVATAR
        ),

        // 贴纸
        ShopItem(
            id = "sticker_1",
            name = "开心EMOJI",
            description = "可爱的开心表情包",
            iconName = "sentiment_very_satisfied",
            price = 30,
            category = ShopCategory.STICKER
        ),
        ShopItem(
            id = "sticker_2",
            name = "爱心发射",
            description = "满满的爱意贴纸",
            iconName = "favorite",
            price = 40,
            category = ShopCategory.STICKER
        ),
        ShopItem(
            id = "sticker_3",
            name = "彩虹独角兽",
            description = "梦幻彩虹独角兽",
            iconName = "auto_awesome",
            price = 60,
            category = ShopCategory.STICKER
        ),

        // 相框
        ShopItem(
            id = "frame_1",
            name = "简约边框",
            description = "简洁大方的日记边框",
            iconName = "crop_square",
            price = 20,
            category = ShopCategory.FRAME
        ),
        ShopItem(
            id = "frame_2",
            name = "复古相框",
            description = "怀旧复古风格相框",
            iconName = "photo_camera",
            price = 50,
            category = ShopCategory.FRAME
        ),

        // 徽章
        ShopItem(
            id = "badge_1",
            name = "坚持不懈",
            description = "连续7天签到奖励",
            iconName = "emoji_events",
            price = 100,
            category = ShopCategory.BADGE
        ),
        ShopItem(
            id = "badge_2",
            name = "记录达人",
            description = "写满50篇日记徽章",
            iconName = "verified",
            price = 150,
            category = ShopCategory.BADGE
        )
    )

    // 初始化/重置成就进度（基于实际数据）
    fun initializeProgress(
        diaryCount: Int,
        streakDays: Int,
        chatCount: Int,
        drawingCount: Int,
        testCompleteCount: Int
    ) {
        checkAchievements(
            diaryCount = diaryCount,
            streakDays = streakDays,
            chatCount = chatCount,
            drawingCount = drawingCount,
            testCompleteCount = testCompleteCount,
            totalPoints = _userPoints.value.totalPoints
        )
    }
}
