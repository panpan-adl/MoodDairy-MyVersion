package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName

data class RelaxTrack(
    val id: String,
    val title: String,
    val artist: String,
    @SerializedName("jump_url") val jumpUrl: String,
)
data class RelaxRecommendations(val tracks: List<RelaxTrack> = emptyList())

data class RelaxPlayback(val url: String)
