package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

/**
 * 成就系统数据模型
 */

// 成就类型
enum class AchievementType {
    DIARY_COUNT,        // 写日记数量成就
    STREAK,             // 连续天数成就
    CHAT_COUNT,         // 对话次数成就
    DRAWING_COUNT,      // 绘画数量成就
    TEST_COMPLETE,      // 完成测试成就
    POINTS_MILESTONE,   // 积分里程碑成就
    SPECIAL             // 特殊成就
}

// 成就状态
enum class AchievementStatus {
    LOCKED,     // 未解锁
    UNLOCKED,   // 已解锁
    VIEWED      // 已查看（显示过动画）
}

/**
 * 单个成就
 */
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val iconName: String,
    val type: AchievementType,
    val requirement: Int,          // 解锁所需数量
    val pointsReward: Int,        // 解锁奖励积分
    var currentProgress: Int = 0, // 当前进度
    var status: AchievementStatus = AchievementStatus.LOCKED,
    var unlockedAt: Long? = null  // 解锁时间
)

/**
 * 用户积分数据
 */
data class UserPoints(
    val totalPoints: Int = 0,        // 总积分
    val availablePoints: Int = 0,    // 可用积分
    val earnedPoints: Int = 0,       // 已消费积分
    val lastEarnedAt: Long? = null   // 最后获得积分时间
)

/**
 * 积分记录
 */
data class PointsRecord(
    val id: Long = 0,
    val points: Int,                  // 积分变化（正数增加，负数减少）
    val reason: String,              // 原因
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 商城物品
 */
data class ShopItem(
    val id: String,
    val name: String,
    val description: String,
    val iconName: String,
    val price: Int,                  // 价格
    val category: ShopCategory,      // 分类
    val isAvailable: Boolean = true  // 是否可兑换
)

enum class ShopCategory {
    AVATAR,      // 头像
    THEME,       // 主题
    STICKER,     // 贴纸
    FRAME,       // 相框
    BADGE        // 徽章
}

/**
 * 用户已购买的商城物品
 */
data class PurchasedItem(
    val itemId: String,
    val purchasedAt: Long = System.currentTimeMillis()
)
