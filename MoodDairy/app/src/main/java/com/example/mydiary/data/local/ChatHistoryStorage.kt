package com.example.mydiary.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.ChatMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一条已保存的历史对话（文件级持久化，可在历史页查看/导出/删除） */
data class StoredConversation(
    val conversationId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
)

@Singleton
class ChatHistoryStorage @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
) {
    private val prefs: SharedPreferences =
        SecurePreferences.create(context, PREFS_NAME)

    private val historyDir: File = File(context.filesDir, "chat_history")

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

    // ============================================================================
    // 按对话分文件存储（历史对话页）
    // ============================================================================

    /** 把一轮对话保存为独立 JSON 文件；title 取首条用户消息 */
    fun saveConversation(userId: Long, conversationId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val dir = userDir(userId)
        if (!dir.exists()) dir.mkdirs()
        val now = System.currentTimeMillis()
        val firstUser = messages.firstOrNull { it.isFromUser }?.content ?: "对话"
        val stored = StoredConversation(
            conversationId = conversationId,
            title = firstUser.take(24),
            createdAt = messages.firstOrNull()?.timestamp ?: now,
            updatedAt = messages.lastOrNull()?.timestamp ?: now,
            messages = messages,
        )
        try {
            convFile(dir, conversationId).writeText(gson.toJson(stored))
        } catch (_: Exception) {
        }
    }

    /** 列出某用户全部历史对话，按最近更新倒序 */
    fun listConversations(userId: Long): List<StoredConversation> {
        val dir = userDir(userId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    gson.fromJson(f.readText(), StoredConversation::class.java)
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun loadConversation(userId: Long, conversationId: String): StoredConversation? {
        return try {
            val f = convFile(userDir(userId), conversationId)
            if (!f.exists()) null else gson.fromJson(f.readText(), StoredConversation::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteConversation(userId: Long, conversationId: String) {
        try {
            convFile(userDir(userId), conversationId).delete()
        } catch (_: Exception) {
        }
    }

    private fun userDir(userId: Long) = File(historyDir, "u$userId")

    private fun convFile(dir: File, conversationId: String): File {
        val safe = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(dir, "$safe.json")
    }

    private fun key(userId: Long) = "${KEY_PREFIX}$userId"

    companion object {
        private const val PREFS_NAME = "chat_history_secure"
        private const val KEY_PREFIX = "messages_"
    }
}
