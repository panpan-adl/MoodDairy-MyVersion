package com.example.mydiary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoUrlsTest {
    private val base = "http://127.0.0.1:8000/"

    @Test fun savedUploadPathsUsePlayableMediaEndpoint() {
        assertEquals("${base}media/videos/saved.mp4", resolveVideoUrl("/uploads/videos/saved.mp4", base))
        assertEquals("${base}media/videos/saved.mp4", resolveVideoUrl("uploads/videos/saved.mp4", base))
    }

    @Test fun persistedMediaPathsResolveAgainstServer() {
        assertEquals("${base}media/videos/saved.mp4", resolveVideoUrl("/media/videos/saved.mp4", base))
        assertEquals("${base}media/videos/saved.mp4", resolveVideoUrl("media/videos/saved.mp4", base))
    }

    @Test fun generatedRemoteUrlsKeepSignatureAndQuery() {
        val url = "https://cdn.example/video.mp4?token=abc%2B123&expires=123"
        assertEquals(url, resolveVideoUrl(url, base))
    }

    @Test fun deviceVideosKeepContentAndFileUris() {
        for (url in listOf("content://media/external/video/media/23", "file:///storage/emulated/0/video.mp4")) {
            assertEquals(url, resolveVideoUrl(url, base))
        }
    }

    @Test fun protocolRelativeUrlsUseServerScheme() {
        assertEquals("https://cdn.example/video.mp4", resolveVideoUrl("//cdn.example/video.mp4", "https://example.com/"))
    }
}
