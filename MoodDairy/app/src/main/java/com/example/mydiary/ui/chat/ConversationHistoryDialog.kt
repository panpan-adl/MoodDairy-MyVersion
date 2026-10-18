package com.example.mydiary.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mydiary.data.local.StoredConversation
import com.example.mydiary.ui.components.PageLazyColumn
import com.example.mydiary.ui.theme.PageTopBackground
import com.example.mydiary.ui.theme.PageMiddleBackground
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.PrimaryDark
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val HistoryInk = Color(0xFF294A40)
private val HistoryMuted = Color(0xFF71857C)
private val HistoryGreen = PrimaryDark
private val HistoryPaper = SoftWhite

@Composable
internal fun ConversationHistoryDialog(
    conversations: List<StoredConversation>,
    onDismiss: () -> Unit,
    onOpen: (StoredConversation) -> Unit,
    onExport: (StoredConversation) -> Unit,
    onDelete: (StoredConversation) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<StoredConversation?>(null) }
    val filtered = remember(conversations, query) {
        val keyword = query.trim()
        conversations.sortedByDescending { it.updatedAt }.filter { conversation ->
            keyword.isEmpty() || conversation.title.contains(keyword, ignoreCase = true) ||
                conversation.messages.any { it.content.contains(keyword, ignoreCase = true) }
        }
    }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    val dateFormat = remember { SimpleDateFormat("M月d日 · EEEE", Locale.CHINA) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    val today = dayFormat.format(Date())
    val yesterday = dayFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
    val groups = filtered.groupBy { dayFormat.format(Date(it.updatedAt)) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
            val dialogHeight = minOf(maxHeight, 640.dp)
            Surface(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = dialogHeight)
                    .align(Alignment.Center),
                shape = RoundedCornerShape(28.dp),
                color = HistoryPaper,
            ) {
                Column(Modifier.background(Brush.verticalGradient(
                    listOf(PageTopBackground, PageMiddleBackground, SoftWhite)))) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = Color.White.copy(alpha = .72f)) {
                                Icon(Icons.Outlined.Forum, null, Modifier.padding(8.dp).size(20.dp), tint = HistoryGreen)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("历史对话", color = HistoryInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text("${conversations.size} 段", color = HistoryGreen, fontSize = 11.sp)
                                }
                                Text("每一次倾诉，都值得被记住", color = HistoryMuted, fontSize = 12.sp)
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Outlined.Close, "关闭历史对话", tint = HistoryMuted)
                            }
                        }
                    }
                    if (conversations.isNotEmpty()) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("找找聊过的话题", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                            trailingIcon = if (query.isNotEmpty()) ({
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp))
                                }
                            }) else null,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HistoryGreen, unfocusedBorderColor = Color.White.copy(alpha = .8f),
                                focusedContainerColor = Color.White.copy(alpha = .75f), unfocusedContainerColor = Color.White.copy(alpha = .65f),
                                focusedTextColor = HistoryInk, unfocusedTextColor = HistoryInk,
                                focusedLeadingIconColor = HistoryGreen, unfocusedLeadingIconColor = HistoryMuted,
                                cursorColor = HistoryGreen,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    if (filtered.isEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 38.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(shape = CircleShape, color = Color(0xFFE8F1E7)) {
                                Icon(if (conversations.isEmpty()) Icons.Outlined.Forum else Icons.Outlined.Search,
                                    null, Modifier.padding(20.dp).size(32.dp), tint = HistoryGreen)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(if (conversations.isEmpty()) "故事，从一次倾诉开始" else "还没找到这段对话",
                                color = HistoryInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(if (conversations.isEmpty()) "聊完休息片刻，对话就会自动收藏在这里。\n想再聊聊时，随时回来。"
                                else "换个关键词试试，也可以搜索聊天内容。",
                                color = HistoryMuted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
                        }
                    } else {
                        key(query) {
                            PageLazyColumn(
                                modifier = Modifier.weight(1f, fill = false),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                groups.forEach { (day, entries) ->
                                    item(key = "date:$day") {
                                        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(6.dp).background(HistoryGreen.copy(alpha = .5f), CircleShape))
                                            Spacer(Modifier.width(8.dp))
                                            Text(when (day) {
                                                today -> "今天"
                                                yesterday -> "昨天"
                                                else -> dateFormat.format(Date(entries.first().updatedAt))
                                            }, color = HistoryMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            Spacer(Modifier.weight(1f))
                                            Text("${entries.size} 段", color = HistoryMuted, fontSize = 11.sp)
                                        }
                                    }
                                    items(entries, key = { it.conversationId }) { conversation ->
                                        HistoryConversationCard(conversation, timeFormat.format(Date(conversation.updatedAt)),
                                            onOpen = { onOpen(conversation) }, onExport = { onExport(conversation) },
                                            onDelete = { pendingDelete = conversation })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFAB5C55)) },
            title = { Text("删除这段对话？") },
            text = { Text("“${conversation.title}”删除后无法恢复，想留个纪念的话，可以先导出。") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(conversation) }) {
                    Text("确认删除", color = Color(0xFFAB5C55))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("再想想", color = HistoryGreen) } },
            containerColor = HistoryPaper,
        )
    }
}

@Composable
private fun HistoryConversationCard(
    conversation: StoredConversation,
    time: String,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val preview = remember(conversation) {
        conversation.messages.lastOrNull { !it.isFromUser && it.content.isNotBlank() }?.content
            ?.replace(Regex("\\s+"), " ")?.trim()
    }
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .7f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .85f)), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(14.dp), tint = HistoryGreen)
                Spacer(Modifier.width(6.dp))
                Text(time, color = HistoryMuted, fontSize = 11.sp)
                Text("  ·  ${conversation.messages.size} 条消息", color = HistoryMuted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "更多操作：${conversation.title}", tint = HistoryMuted)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("导出对话") },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                            onClick = { menuExpanded = false; onExport() })
                        DropdownMenuItem(text = { Text("删除对话", color = Color(0xFFAB5C55)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFAB5C55)) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conversation.title.ifBlank { "一段心里话" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = HistoryInk, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp,
                    modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "继续聊", Modifier.size(18.dp), tint = HistoryGreen)
            }
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(preview, fontSize = 12.sp, lineHeight = 19.sp, color = HistoryMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 4.dp))
            }
        }
    }
}
