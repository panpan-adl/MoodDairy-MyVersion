package com.example.mydiary.ui.chat

import com.example.mydiary.data.models.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatTimelineEntriesTest {
    @Test fun streamingAndCardsDoNotChangeMessageIdentity() {
        val original = ChatMessage(content = "", isFromUser = false, timestamp = 123, isStreaming = true)
        val updated = original.copy(content = "逐字增长的回复", isStreaming = false, socialSearchLocked = true)
        assertEquals(chatTimelineEntries(listOf(original)).single().key, chatTimelineEntries(listOf(updated)).single().key)
    }

    @Test fun importedDuplicateTimestampsRemainUniqueAfterAppending() {
        val messages = List(3) { ChatMessage(content = "消息 $it", isFromUser = false, timestamp = 123) }
        val keys = chatTimelineEntries(messages).map { it.key }
        assertEquals(3, keys.toSet().size)
        assertEquals(keys, chatTimelineEntries(messages + messages.last()).take(3).map { it.key })
    }
}
