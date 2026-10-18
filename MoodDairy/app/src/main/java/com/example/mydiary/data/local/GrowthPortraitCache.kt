package com.example.mydiary.data.local

import android.content.Context
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Private snapshots are scoped to the account, period and end date. */
@Singleton
class GrowthPortraitCache @Inject constructor(@ApplicationContext context: Context, private val gson: Gson) {
    private val prefs = SecurePreferences.create(context, "growth_portrait_cache_v1")
    fun load(userId: Long, days: Int, endDate: String): GrowthPortraitResponse? = runCatching {
        val json = prefs.getString("${userId}_$days", null) ?: return null
        gson.fromJson(json, GrowthPortraitResponse::class.java)?.takeIf { it.userId == userId && it.rangeEnd == endDate }
    }.getOrNull()

    fun save(days: Int, value: GrowthPortraitResponse) {
        prefs.edit().putString("${value.userId}_$days", gson.toJson(value)).apply()
    }
}
