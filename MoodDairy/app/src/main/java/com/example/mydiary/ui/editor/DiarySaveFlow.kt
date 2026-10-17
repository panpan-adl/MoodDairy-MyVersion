package com.example.mydiary.ui.editor

import com.example.mydiary.data.models.MediaItem
import java.time.LocalDate

sealed interface DiarySaveAction {
    data object ReturnToCalendar : DiarySaveAction
    data class ConfirmText(val mediaId: String, val content: String) : DiarySaveAction
}

data class DiarySaveCompletion(val action: DiarySaveAction, val diaryId: Long, val date: LocalDate)

internal fun DiaryEditorUiState.contentMatches(other: DiaryEditorUiState): Boolean =
    date == other.date && title == other.title && mediaItems == other.mediaItems &&
        weather == other.weather && location == other.location && moodScore == other.moodScore &&
        moodType == other.moodType && isHighlight == other.isHighlight && isLittleJoy == other.isLittleJoy

/** The sync API normalizes order and replaces temporary IDs. Keep text confirmations attached to their cards. */
internal fun remapConfirmedText(
    submitted: List<MediaItem>, saved: List<MediaItem>, confirmed: Map<String, String>, action: DiarySaveAction?,
): Map<String, String> = buildMap {
    submitted.sortedBy { it.order }.zip(saved.sortedBy { it.order }).forEach { (before, after) ->
        if (before is MediaItem.Text && after is MediaItem.Text) {
            val newlyConfirmed = action as? DiarySaveAction.ConfirmText
            val content = if (newlyConfirmed?.mediaId == before.id && newlyConfirmed.content == before.content)
                before.content else confirmed[before.id]
            content?.let { put(after.id, it) }
        }
    }
}

/** Preserve edits made while syncing, while still adopting the server's media IDs. */
internal fun reconcileSavedMedia(submitted: List<MediaItem>, saved: List<MediaItem>, current: List<MediaItem>): List<MediaItem> {
    if (current == submitted) return saved
    val ids = submitted.sortedBy { it.order }.zip(saved.sortedBy { it.order }).associate { it.first.id to it.second.id }
    return current.map { item ->
        val id = ids[item.id] ?: return@map item
        when (item) {
            is MediaItem.Text -> item.copy(id = id)
            is MediaItem.Image -> item.copy(id = id)
            is MediaItem.Audio -> item.copy(id = id)
            is MediaItem.Video -> item.copy(id = id)
        }
    }
}
