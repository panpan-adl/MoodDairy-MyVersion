package com.example.mydiary.ui.editor

import com.example.mydiary.data.models.MediaItem
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DiaryLocalReviewTest {
    private fun text(value: String) = MediaItem.Text("text", content = value, order = 0, createdAt = Instant.EPOCH)

    @Test fun blankAndTitleOnlyAndPhotoOnlyDoNotDisplayReview() {
        assertFalse(shouldShowDiaryReview(DiaryEditorUiState()))
        assertFalse(shouldShowDiaryReview(DiaryEditorUiState(title = "我的标题")))
        assertFalse(shouldShowDiaryReview(DiaryEditorUiState(mediaItems = listOf(text("  \n")))))
        val photo = MediaItem.Image("photo", url = "photo.jpg", thumbnailUrl = null, order = 0, createdAt = Instant.EPOCH)
        assertFalse(shouldShowDiaryReview(DiaryEditorUiState(mediaItems = listOf(photo))))
        assertTrue(shouldShowDiaryReview(DiaryEditorUiState(mediaItems = listOf(text("今天上课")))))
        assertFalse(shouldShowDiaryReview(DiaryEditorUiState(isLoading = true, mediaItems = listOf(text("今天上课")))))
    }

    @Test fun offlineWordsAreRealUniqueAndCannotInventAnEmotion() {
        for (source in listOf("嗯", "开心", "今天上课写报告", "。。。😊", "我不开心，也不怎么焦虑。")) {
            val result = localDiaryReview(10, source)
            assertTrue(result.keywords.isNotEmpty())
            assertTrue(result.keywords.size <= 12)
            assertEquals(result.keywords.size, result.keywords.map { it.text }.distinct().size)
            assertTrue(result.keywords.all { it.text in source })
            assertTrue(result.keywords.all { it.weight in 0f..1f })
        }
        val negative = localDiaryReview(10, "我不开心，也不怎么焦虑。")
        assertTrue(negative.keywords.all { it.category == "neutral" })
        assertEquals("情绪不明确", localDiaryReview(10, "上课写报告").primaryEmotion)
        assertTrue(localDiaryReview(10, "").keywords.isEmpty())
    }
}
