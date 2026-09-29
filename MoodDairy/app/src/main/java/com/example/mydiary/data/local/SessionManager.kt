package com.example.mydiary.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.User
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话管理器 — 使用 EncryptedSharedPreferences 存储用户信息与 JWT。
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences: SharedPreferences =
        SecurePreferences.create(context, PREF_NAME)

    fun saveSession(user: User, accessToken: String) {
        sharedPreferences.edit().apply {
            putLong(KEY_USER_ID, user.id)
            putString(KEY_USERNAME, user.username)
            putString(KEY_NICKNAME, user.nickname)
            putString(KEY_AVATAR, user.avatar)
            putString(KEY_PHONE, user.phone)
            putString(KEY_EMAIL, user.email)
            putInt(KEY_GENDER, user.gender)
            putString(KEY_BIRTHDAY, user.birthday)
            putInt(KEY_STATUS, user.status)
            putString(KEY_ACCESS_TOKEN, accessToken)
            putBoolean(KEY_IS_LOGGED_IN, true)
            apply()
        }
    }

    fun getAccessToken(): String? =
        sharedPreferences.getString(KEY_ACCESS_TOKEN, null)

    fun getSession(): User? {
        if (!isLoggedIn()) return null
        val userId = sharedPreferences.getLong(KEY_USER_ID, -1)
        if (userId == -1L) return null
        return User(
            id = userId,
            username = sharedPreferences.getString(KEY_USERNAME, "") ?: "",
            nickname = sharedPreferences.getString(KEY_NICKNAME, null),
            avatar = sharedPreferences.getString(KEY_AVATAR, null),
            phone = sharedPreferences.getString(KEY_PHONE, null),
            email = sharedPreferences.getString(KEY_EMAIL, null),
            gender = sharedPreferences.getInt(KEY_GENDER, 0),
            birthday = sharedPreferences.getString(KEY_BIRTHDAY, null),
            status = sharedPreferences.getInt(KEY_STATUS, 1),
            createdAt = null,
            updatedAt = null,
        )
    }

    fun clearSession() {
        sharedPreferences.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean =
        sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)
            && !getAccessToken().isNullOrBlank()

    fun getUserId(): Long? {
        if (!isLoggedIn()) return null
        val userId = sharedPreferences.getLong(KEY_USER_ID, -1)
        return if (userId == -1L) null else userId
    }

    fun updateSession(user: User) {
        if (isLoggedIn()) {
            val token = getAccessToken() ?: return
            saveSession(user, token)
        }
    }

    companion object {
        private const val PREF_NAME = "diary_session_secure"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_NICKNAME = "nickname"
        const val KEY_AVATAR = "avatar"
        const val KEY_PHONE = "phone"
        const val KEY_EMAIL = "email"
        const val KEY_GENDER = "gender"
        const val KEY_BIRTHDAY = "birthday"
        const val KEY_STATUS = "status"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
    }
}
