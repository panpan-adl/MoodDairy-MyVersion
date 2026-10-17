package com.example.mydiary.util

import com.example.mydiary.data.network.ApiEndpoints

/** Resolve saved API media paths while preserving remote and device URIs. */
internal fun resolveVideoUrl(url: String, baseUrl: String = ApiEndpoints.apiBaseUrl): String {
    val value = url.trim()
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) return value
    if (value.startsWith("//")) return "${baseUrl.substringBefore(":")}:$value"
    val path = when {
        value.startsWith("/uploads/") -> "/media/${value.removePrefix("/uploads/")}"
        value.startsWith("uploads/") -> "/media/${value.removePrefix("uploads/")}"
        value.startsWith("/") -> value
        else -> "/$value"
    }
    return "${baseUrl.trimEnd('/')}$path"
}
