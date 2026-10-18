package com.example.mydiary.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val PostGreen = Color(0xFF397C68)
private val PostGold = Color(0xFF9B7849)
private val PostLine = Color(0xFFDFEAE2)

internal fun letterOpeningDate(letter: JSONObject): LocalDate? = runCatching {
    Instant.parse(letter.getString("opens_at")).atZone(ZoneId.of(letter.getString("timezone"))).toLocalDate()
}.getOrNull()

@Composable
internal fun TimeMailbox(
    letters: List<JSONObject>, drafts: List<JSONObject>, busy: Boolean,
    onWrite: () -> Unit, onOpen: (JSONObject) -> Unit,
    onResume: (JSONObject) -> Unit, onDiscard: (JSONObject) -> Unit, onRefresh: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    // A local copy of a server draft is shown once, with its latest on-device edits.
    val localServerIds = drafts.map { it.optString("server_id") }.filter { it.isNotBlank() }.toSet()
    val remote = letters.filter { it.optString("id") !in localServerIds }
    val waiting = letters.count { it.optString("state") == "sealed" }
    val opened = letters.count { it.optString("state") == "opened" }
    val due = letters.filter { it.optString("state") == "sealed" && it.optBoolean("due") }
    val next = letters.filter { it.optString("state") == "sealed" }
        .minByOrNull { it.optString("opens_at") }

    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2E8))) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("慢递邮局 · 只寄给你", color = PostGreen, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text("把此刻，\n寄给未来的你", color = TextPrimary, fontSize = 27.sp,
                        lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                }
                EnvelopeArt(Modifier.size(100.dp))
            }
            Text("收藏今天的文字、照片与声音，\n等到约定的日子，再和自己重逢。", color = PostGreen,
                fontSize = 13.sp, lineHeight = 22.sp)
            Button(onClick = onWrite, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = PostGreen)) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("写一封视频信", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .82f))
        .padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf((remote.size + drafts.size) to "珍藏的信", waiting to "交给时间", opened to "已经重逢").forEachIndexed { index, (count, label) ->
            if (index > 0) Box(Modifier.width(1.dp).height(30.dp).background(PostLine))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(count.toString().padStart(2, '0'), color = PostGreen, fontSize = 25.sp, fontWeight = FontWeight.Medium)
                Text(label, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
    if (next != null) {
        val days = letterOpeningDate(next)?.let { ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of(next.optString("timezone", "Asia/Shanghai"))), it).coerceAtLeast(0) }
        Card(onClick = { onOpen(next) }, enabled = !busy, shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5EEDA))) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                Box(Modifier.size(58.dp).border(1.dp, PostGold.copy(alpha = .24f), CircleShape)
                    .background(Color.White.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                    if (due.isNotEmpty()) Icon(Icons.Outlined.MarkEmailUnread, null, tint = PostGold)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(days?.toString() ?: "…", color = PostGold, fontSize = 23.sp, fontWeight = FontWeight.Medium)
                        Text("天后", color = PostGold, fontSize = 9.sp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (due.isNotEmpty()) "有 ${due.size} 封来信，等你开启" else "下一次，和自己重逢", color = PostGold,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(next.optString("title"), color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (due.isNotEmpty()) "准备好了，就来拆信吧" else "${letterOpeningDate(next)?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "约定日期"} · 时间会替你好好保管",
                        color = PostGold, fontSize = 10.sp)
                }
                Icon(Icons.Outlined.ChevronRight, "查看这封信", tint = PostGold, modifier = Modifier.size(18.dp))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的时光来信", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !busy, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp)); Text("刷新", fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "sealed" to "待开启", "opened" to "已开启", "draft" to "制作中").forEach { (key, label) ->
                FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label, fontSize = 12.sp) },
                    shape = RoundedCornerShape(14.dp), colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFD3EBDF), selectedLabelColor = PostGreen),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == key,
                        borderColor = PostLine, selectedBorderColor = Color.Transparent))
            }
        }
        val visibleDrafts = if (filter in listOf("all", "draft")) drafts else emptyList()
        val visibleLetters = remote.filter { filter == "all" || if (filter == "draft") it.optString("state") !in listOf("sealed", "opened") else it.optString("state") == filter }
        visibleDrafts.forEach { local ->
            MailLetterCard(local, local = true, enabled = !busy, onClick = { onResume(local) }, onDiscard = { onDiscard(local) })
        }
        visibleLetters.forEach { letter ->
            MailLetterCard(letter, local = false, enabled = !busy, onClick = { onOpen(letter) })
        }
        if (visibleLetters.isEmpty() && visibleDrafts.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .7f)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EnvelopeArt(Modifier.size(88.dp))
                Text(if (filter == "all") "第一封信，从今天开始" else "这里还没有来信", color = PostGreen, fontWeight = FontWeight.Medium)
                Text(when (filter) { "sealed" -> "封存一封信，让时间为你保管。"; "opened" -> "等约定的日子到了，再来重温。";
                    "draft" -> "有想说的话，就先把它记下来。"; else -> "写下一个愿望，或一件舍不得忘的小事。" }, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
    Text("慢慢写，慢慢等。每一封信，都有抵达的日子。", color = TextSecondary, fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
internal fun LetterWritingIntro(reply: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFFE3F2E8)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EnvelopeArt(Modifier.size(66.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (reply) "让现在，回应当时" else "亲爱的，未来的我", color = PostGreen, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("写下心事 · 附上回忆 · 约定重逢", color = PostGreen, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MailLetterCard(letter: JSONObject, local: Boolean, enabled: Boolean, onClick: () -> Unit, onDiscard: (() -> Unit)? = null) {
    val state = if (local) "local" else letter.optString("state")
    val sealed = state == "sealed"
    val ink = when (state) { "opened" -> PostGold; "failed" -> Color(0xFFAB6E60); else -> PostGreen }
    val badge = when (state) { "local" -> "手机草稿"; "sealed" -> if (letter.optBoolean("due")) "等你拆信" else "时光保管中";
        "opened" -> "已开启"; "ready" -> "视频待预览"; "rendering" -> "视频制作中"; "failed" -> "待重新生成"; else -> "账号草稿" }
    Card(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEF8)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5EBDD))) {
        Column(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val step = 20.dp.toPx()
                var x = 0f
                while (x < size.width) { drawLine(ink.copy(alpha = .28f), Offset(x, 0f), Offset(x + 10.dp.toPx(), size.height), 6.dp.toPx()); x += step }
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = ink.copy(alpha = .09f), shape = RoundedCornerShape(7.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(if (sealed) Icons.Outlined.Lock else if (state == "opened") Icons.Outlined.Drafts else Icons.Outlined.EditNote,
                                null, tint = ink, modifier = Modifier.size(13.dp))
                            Text(badge, color = ink, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.LocalPostOffice, null, tint = ink.copy(alpha = .55f),
                        modifier = Modifier.size(34.dp).border(1.dp, ink.copy(alpha = .25f), RoundedCornerShape(5.dp)).padding(6.dp))
                }
                Text(letter.optString("title").ifBlank { "给未来的自己" }, color = TextPrimary, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when { sealed -> "把心事交给时间，等一个刚刚好的重逢。"; local -> letter.optString("text").ifBlank { "还没说完的话，留在这里等你。" };
                    state == "rendering" -> "正在把文字与回忆，编织成一封视频信。"; state == "opened" -> "那时的心情，今天的你终于读懂。";
                    state == "ready" -> "回忆已成片，预览后就可以封存寄出。"; state == "failed" -> "回忆还在，打开信件后可以重新生成。";
                    else -> "文字与回忆已保存，继续完成这封信。" }, color = TextSecondary, fontSize = 12.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = PostLine)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Event, null, tint = ink, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    val date = if (local) letter.optString("open_date") else letterOpeningDate(letter)?.toString().orEmpty()
                    Text(if (date.isBlank()) "等待约定重逢日期" else "$date · 约定开启", color = ink, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (onDiscard != null) IconButton(onClick = onDiscard, enabled = enabled, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, "移除手机草稿", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = ink, modifier = Modifier.size(18.dp))
                }
                if (letter.has("parent_id") && !letter.isNull("parent_id")) Text("当时的我  ···  现在的我", color = PostGold, fontSize = 10.sp)
            }
        }
    }
}

/** Vector stationery stays crisp at every display size and needs no remote assets. */
@Composable
private fun EnvelopeArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFFD1E8D9), w * .46f, Offset(w * .51f, h * .51f))
        drawCircle(Color(0xFFF4E8C7), w * .09f, Offset(w * .88f, h * .16f))
        drawRoundRect(Color(0xFFFFFEF5), Offset(w * .25f, h * .17f), Size(w * .5f, h * .53f), CornerRadius(w * .05f))
        repeat(3) { i -> drawLine(Color(0xFFBDD6C6), Offset(w * .34f, h * (.3f + i * .085f)), Offset(w * .66f, h * (.3f + i * .085f)), w * .018f, StrokeCap.Round) }
        drawRoundRect(Color(0xFF8ABBA3), Offset(w * .12f, h * .43f), Size(w * .76f, h * .43f), CornerRadius(w * .05f))
        drawPath(Path().apply { moveTo(w * .13f, h * .45f); lineTo(w * .5f, h * .7f); lineTo(w * .87f, h * .45f) },
            Color(0xFFE5F3E6), style = Stroke(w * .018f, cap = StrokeCap.Round))
        drawPath(Path().apply { moveTo(w * .14f, h * .83f); lineTo(w * .39f, h * .64f); moveTo(w * .86f, h * .83f); lineTo(w * .61f, h * .64f) },
            Color(0xFF679B82), style = Stroke(w * .012f))
        drawCircle(Color(0xFFF5E7C3), w * .085f, Offset(w * .5f, h * .69f))
        drawCircle(Color(0xFFBE9A5C), w * .044f, Offset(w * .5f, h * .69f), style = Stroke(w * .012f))
        drawLine(PostGreen.copy(alpha = .45f), Offset(w * .08f, h * .19f), Offset(w * .08f, h * .29f), w * .015f, StrokeCap.Round)
        drawLine(PostGreen.copy(alpha = .45f), Offset(w * .03f, h * .24f), Offset(w * .13f, h * .24f), w * .015f, StrokeCap.Round)
    }
}
