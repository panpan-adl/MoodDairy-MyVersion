package com.example.mydiary.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mydiary.data.models.ChatMessage
import kotlinx.coroutines.flow.collect

internal data class ChatTimelineEntry(val key: String, val message: ChatMessage)

/** Stable across streaming edits; also tolerates imported messages with equal timestamps. */
internal fun chatTimelineEntries(messages: List<ChatMessage>): List<ChatTimelineEntry> {
    val occurrences = mutableMapOf<String, Int>()
    return messages.map { message ->
        val base = "${message.timestamp}-${message.isFromUser}"
        val occurrence = occurrences.getOrDefault(base, 0)
        occurrences[base] = occurrence + 1
        ChatTimelineEntry("$base-$occurrence", message)
    }
}

/** Reverse layout anchors growing replies and keyboard resizing at the visual bottom. */
@Composable
internal fun ChatViewport(
    listState: LazyListState,
    entries: List<ChatTimelineEntry>,
    header: @Composable () -> Unit,
    composer: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    var followingLatest by remember(listState) { mutableStateOf(true) }
    var movingToLatest by remember(listState) { mutableStateOf(false) }
    var previousUserKey by remember(listState) { mutableStateOf<String?>(null) }
    val latestUserKey = entries.lastOrNull { it.message.isFromUser }?.key
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (scrolling, index, offset) ->
                if (scrolling && !movingToLatest) followingLatest = index == 0 && offset == 0
            }
    }
    LaunchedEffect(entries.size, latestUserKey) {
        val userSentMessage = latestUserKey != null && latestUserKey != previousUserKey
        previousUserKey = latestUserKey
        if (entries.isNotEmpty() && (followingLatest || userSentMessage)) {
            movingToLatest = true
            try {
                listState.scrollToItem(0)
                followingLatest = true
            } finally { movingToLatest = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().testTag("chat_fixed_header")) { header() }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_timeline").padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            content = content,
        )
        Box(Modifier.fillMaxWidth().testTag("chat_composer")) { composer() }
    }
}
