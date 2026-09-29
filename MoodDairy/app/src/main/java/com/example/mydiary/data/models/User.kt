package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName
import com.example.mydiary.data.network.ApiEndpoints

/**
 * 用户相关数据模型
 * 用于用户认证和个人资料管理
 */

// ============================================================================
// 请求模型 (Request Models)
// ============================================================================

/**
 * 登录请求
 */
data class LoginRequest(
    @SerializedName("username")
    val username: String,
    
    @SerializedName("password")
    val password: String
)

/**
 * 注册请求
 */
data class RegisterRequest(
    @SerializedName("username")
    val username: String,
    
    @SerializedName("password")
    val password: String,
    
    @SerializedName("nickname")
    val nickname: String? = null
)

/**
 * 更新用户信息请求
 */
data class UpdateUserRequest(
    @SerializedName("nickname")
    val nickname: String? = null,
    
    @SerializedName("phone")
    val phone: String? = null,
    
    @SerializedName("email")
    val email: String? = null,
    
    @SerializedName("gender")
    val gender: Int? = null,
    
    @SerializedName("birthday")
    val birthday: String? = null
)

// ============================================================================
// 响应模型 (Response Models)
// ============================================================================

/**
 * 用户响应
 */
data class UserResponse(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("username")
    val username: String,
    
    @SerializedName("nickname")
    val nickname: String? = null,
    
    @SerializedName("avatar")
    val avatar: String? = null,
    
    @SerializedName("phone")
    val phone: String? = null,
    
    @SerializedName("email")
    val email: String? = null,
    
    @SerializedName("gender")
    val gender: Int = 0,
    
    @SerializedName("birthday")
    val birthday: String? = null,
    
    @SerializedName("status")
    val status: Int = 1,
    
    @SerializedName("created_at")
    val createdAt: String? = null,
    
    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("access_token")
    val accessToken: String? = null,

    @SerializedName("token_type")
    val tokenType: String? = null,
)

/**
 * 头像上传响应
 */
data class AvatarUploadResponse(
    @SerializedName("avatar_url")
    val avatarUrl: String,
    
    @SerializedName("message")
    val message: String
)

// ============================================================================
// 领域模型 (Domain Models)
// ============================================================================

/**
 * 用户领域模型
 * 用于应用内部的业务逻辑
 */
data class User(
    val id: Long,
    val username: String,
    val nickname: String? = null,
    val avatar: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val gender: Int = 0,
    val birthday: String? = null,
    val status: Int = 1,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    /**
     * 获取性别显示文本
     * 0 -> "未知"
     * 1 -> "男"
     * 2 -> "女"
     */
    fun getGenderText(): String = when (gender) {
        1 -> "男"
        2 -> "女"
        else -> "未知"
    }
    
    /**
     * 获取显示昵称
     * 如果昵称为空，返回"未设置"
     */
    fun getDisplayNickname(): String = nickname ?: "未设置"
    
    /**
     * 获取显示手机号
     * 如果手机号为空，返回"未设置"
     */
    fun getDisplayPhone(): String = phone ?: "未设置"
    
    /**
     * 获取显示邮箱
     * 如果邮箱为空，返回"未设置"
     */
    fun getDisplayEmail(): String = email ?: "未设置"
    
    /**
     * 获取显示生日
     * 如果生日为空，返回"未设置"
     */
    fun getDisplayBirthday(): String = birthday ?: "未设置"
}

// ============================================================================
// 映射扩展函数 (Mapper Extension Functions)
// ============================================================================

/**
 * 将UserResponse转换为User领域模型
 */
fun UserResponse.toDomain(): User {
    return User(
        id = this.id,
        username = this.username,
        nickname = this.nickname,
        avatar = resolveUserAvatarUrl(this.avatar),
        phone = this.phone,
        email = this.email,
        gender = this.gender,
        birthday = this.birthday,
        status = this.status,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

fun resolveUserAvatarUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw

    val normalizedPath = when {
        raw.startsWith("/uploads/") -> "/media/${raw.removePrefix("/uploads/")}"
        raw.startsWith("uploads/") -> "/media/${raw.removePrefix("uploads/")}"
        raw.startsWith("/") -> raw
        else -> "/$raw"
    }

    val base = ApiEndpoints.apiBaseUrl.trimEnd('/')
    return "$base$normalizedPath"
}
