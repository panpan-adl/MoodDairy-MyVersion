package com.example.mydiary.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.ui.theme.*

@Composable
internal fun DiaryReviewCard(
    review: DiaryReviewResponse?,
    dirty: Boolean,
    generating: Boolean,
    error: String?,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(review?.diaryId) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("这篇日记的小回顾", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("根据这篇日记里写下的内容，陪你回看一下。", color = TextSecondary)
            if (dirty) {
                Text("先保存这篇日记，再一起回顾吧。", color = TextSecondary)
            } else if (review?.hasText == false) {
                Text("这些画面也值得留下。愿意的话，可以补一句当时的感受。", color = TextPrimary)
            }
            if (review?.stale == true || (dirty && review?.body != null)) {
                Text("内容有了变化，这份回顾需要更新。", color = TextSecondary)
            }
            review?.body?.let { Text(it, color = TextPrimary, lineHeight = 24.sp) }
            review?.question?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = PrimaryDark, lineHeight = 22.sp)
                Text("想聊时再回答，也可以只在这里停一停。", color = TextSecondary, fontSize = 12.sp)
            }
            error?.takeUnless { dirty && it == "先保存这篇日记，再一起回顾吧。" }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onGenerate, enabled = !generating) {
                if (generating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (generating) "正在陪你回看…" else if (review?.body != null) "重新回顾" else "帮我回顾")
            }
            review?.attachmentNote?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
            if (!review?.clues.isNullOrEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起记录线索" else "看看记录线索")
                }
                if (expanded) {
                    review?.clues?.forEach { Text(it, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp) }
                }
            }
        }
    }
}
