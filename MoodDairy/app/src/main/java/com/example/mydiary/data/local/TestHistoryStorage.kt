package com.example.mydiary.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.TestHistoryItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TestHistoryStorage @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val listType = object : TypeToken<List<TestHistoryItem>>() {}.type

    fun load(): List<TestHistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TestHistoryItem>>(json, listType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun save(items: List<TestHistoryItem>) {
        prefs.edit()
            .putString(KEY_HISTORY, gson.toJson(items))
            .apply()
    }

    fun append(item: TestHistoryItem, maxItems: Int = 30) {
        val merged = (listOf(item) + load()).take(maxItems)
        save(merged)
    }

    companion object {
        private const val PREFS_NAME = "test_history"
        private const val KEY_HISTORY = "history_items"
    }
}
