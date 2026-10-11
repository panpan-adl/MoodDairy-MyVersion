package com.example.mydiary.ui.vip

import java.net.URI
import java.net.URLDecoder

/** Only extract source IDs locally. Share links are never visited by the server. */
object VideoImportSource {
    fun parse(platform: String, input: String): String? = runCatching {
        val value = input.trim()
        val pattern = if (platform == "douyin") Regex("MS4w[A-Za-z0-9_-]+") else Regex("[0-9]{1,30}")
        if (pattern.matches(value)) return@runCatching value
        val link = Regex("https?://[^\\s]+").find(value)?.value ?: return@runCatching null
        val uri = URI(link)
        val host = uri.host?.lowercase()
        val id = if (platform == "douyin" && host in listOf("douyin.com", "www.douyin.com")) {
            if (uri.path.startsWith("/user/")) uri.path.removePrefix("/user/").trim('/') else ""
        } else if (platform == "bilibili" && host == "space.bilibili.com") {
            uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                .firstOrNull { it.first() == "fid" }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
        } else ""
        id.takeIf { pattern.matches(it) }
    }.getOrNull()
}
