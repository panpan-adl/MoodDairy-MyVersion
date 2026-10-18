package com.example.mydiary.data.local

import android.content.Context
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.DiaryResponse
import com.example.mydiary.data.models.toDomain
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Account-scoped snapshots survive navigation and app restarts. */
@Singleton
class ExploreCache @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
) {
    private val prefs = context.getSharedPreferences("explore_cache_v1", Context.MODE_PRIVATE)
    private val listType = object : TypeToken<List<DiaryResponse>>() {}.type

    fun load(userId: Long, section: String): List<Diary>? {
        val json = prefs.getString("${userId}_$section", null) ?: return null
        return runCatching {
            gson.fromJson<List<DiaryResponse>>(json, listType)?.map { it.toDomain() }
        }.getOrNull()
    }

    fun save(userId: Long, section: String, diaries: List<DiaryResponse>) {
        prefs.edit().putString("${userId}_$section", gson.toJson(diaries)).apply()
    }
}
