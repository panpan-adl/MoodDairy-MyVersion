package com.example.mydiary.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.ChatMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatHistoryStorage @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
) {
    private val prefs: SharedPreferences =
        SecurePreferences.create(context, PREFS_NAME)

    private val listType = object : TypeToken<List<ChatMessage>>() {}.type

    fun loadMessages(userId: Long): List<ChatMessage> {
        val json = prefs.getString(key(userId), null) ?: return emptyList()
        return try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMessages(userId: Long, messages: List<ChatMessage>) {
        prefs.edit()
            .putString(key(userId), gson.toJson(messages))
            .apply()
    }

    private fun key(userId: Long) = "${KEY_PREFIX}$userId"

    companion object {
        private const val PREFS_NAME = "chat_history_secure"
        private const val KEY_PREFIX = "messages_"
    }
}
