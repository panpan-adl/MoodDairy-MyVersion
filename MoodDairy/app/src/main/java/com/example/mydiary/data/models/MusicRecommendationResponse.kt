package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

/**
 * 音乐推荐响应体
 */
data class MusicRecommendationResponse(
    @SerializedName("tracks") val tracks: List<MusicTrack>? = null
)

data class MusicTrack(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("cover_url") val coverUrl: String? = null,
    @SerializedName("play_url") val playUrl: String? = null
)
