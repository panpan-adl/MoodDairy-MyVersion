package com.example.mydiary.ui.chat

import com.example.mydiary.ui.components.pageVerticalScroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PrimaryDark
import com.example.mydiary.ui.theme.PrimaryLight
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.PageTopBackground
import com.example.mydiary.ui.theme.PageMiddleBackground
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.PrimaryVariant

private val MoodBodyColor = Color(0xFF526963)

/** Shared only by mood dialogs; never inherits the global lavender dialog surface. */
@Composable
private fun MoodDialog(
    title: String,
    description: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    showConfirm: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(160.dp)
    val fontScale = LocalDensity.current.fontScale
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(28.dp),
            color = SoftWhite,
            shadowElevation = 8.dp,
        ) {
            BoxWithConstraints(
                Modifier.background(Brush.verticalGradient(listOf(PageTopBackground, PageMiddleBackground, SoftWhite))),
            ) {
                val stackActions = maxWidth < 320.dp || fontScale > 1.2f
                Column(
                    Modifier.pageVerticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(description, color = MoodBodyColor, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                    content()
                    if (!showConfirm) {
                        MoodDismissButton(dismissLabel, onDismiss, Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = .55f), RoundedCornerShape(16.dp)))
                    } else if (stackActions) {
                        MoodConfirmButton(confirmLabel, onConfirm, Modifier.fillMaxWidth())
                        MoodDismissButton(dismissLabel, onDismiss, Modifier.fillMaxWidth())
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MoodDismissButton(dismissLabel, onDismiss, Modifier.weight(1f))
                            MoodConfirmButton(confirmLabel, onConfirm, Modifier.weight(2f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodConfirmButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MintGreen, contentColor = TextPrimary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) { Text(label, fontSize = 14.sp) }
}

@Composable
private fun MoodDismissButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    TextButton(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MoodBodyColor),
    ) { Text(label, fontSize = 14.sp) }
}

@Composable
internal fun MoodPickerDialog(selectedMood: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    MoodDialog(
        title = "此刻的心情",
        description = "选一个贴近此刻的心情\n以你的感受为准，变化时会先询问你。",
        confirmLabel = "跟随表情识别", dismissLabel = "取消",
        onConfirm = { onSelect(null) }, onDismiss = onDismiss,
        showConfirm = false,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (MoodChangeTracker.choices.map { it as String? } + listOf<String?>(null)).chunked(2).forEach { choices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices.forEach { mood ->
                        val display = mood?.let { FaceEmotionStatusUi.fromObservation(it, 1f) }
                        val selected = selectedMood == mood
                        Surface(
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                            color = if (selected) MintGreen else Color.White.copy(alpha = .65f),
                            border = BorderStroke(1.dp, if (selected) PrimaryVariant else Color.White.copy(alpha = .8f)),
                        ) {
                            Row(
                                Modifier.selectable(selected, role = Role.RadioButton, onClick = { onSelect(mood) })
                                    .heightIn(min = 68.dp).padding(horizontal = 10.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(34.dp).background(
                                    if (selected) Color.White.copy(alpha = .65f) else PaleYellow.copy(alpha = .6f), CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    if (display != null) Text(display.icon, fontSize = 22.sp)
                                    else Icon(Icons.Outlined.AutoAwesome, contentDescription = null,
                                        tint = PrimaryDark, modifier = Modifier.size(20.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(display?.label ?: "自动", fontSize = 14.sp, lineHeight = 20.sp, color = TextPrimary,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    if (display == null) Text("跟随表情", fontSize = 10.sp, lineHeight = 14.sp, color = MoodBodyColor)
                                }
                                if (selected) Icon(Icons.Filled.Check, contentDescription = null,
                                    tint = PrimaryDark, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (choices.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun MoodChangeDialog(current: FaceEmotionStatusUi, suggested: FaceEmotionStatusUi, onRespond: (Boolean) -> Unit) {
    MoodDialog(
        title = "心情有变化吗？",
        description = "你选择的是“${current.label}”，最近的表情看起来更像“${suggested.icon} ${suggested.label}”。表情不一定代表心情，要更新吗？",
        confirmLabel = "更新心情", dismissLabel = "保持原心情",
        onConfirm = { onRespond(true) }, onDismiss = { onRespond(false) },
    )
}

@Composable
internal fun MoodCareDialog(onRespond: (Boolean) -> Unit) {
    MoodDialog(
        title = "想休息一下吗？",
        description = "我注意到你可能有些不舒服。表情识别也可能有误，你愿意看看音乐、放松和心情记录建议吗？",
        confirmLabel = "看看建议", dismissLabel = "暂时不用",
        onConfirm = { onRespond(true) }, onDismiss = { onRespond(false) },
    )
}

@Composable
internal fun ManualMoodCareDialog(care: ManualMoodCare, onRelax: () -> Unit, onDismiss: () -> Unit) {
    val mood = FaceEmotionStatusUi.fromObservation(care.mood, 1f)
    MoodDialog(
        title = "${mood.icon} 给此刻的你一点照顾",
        description = care.introduction,
        confirmLabel = "听点音乐，放松一下", dismissLabel = "先放一放",
        onConfirm = onRelax, onDismiss = onDismiss,
    ) {
        care.tips.forEachIndexed { index, tip ->
            Surface(color = Color.White.copy(alpha = .85f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", color = PrimaryDark, fontWeight = FontWeight.SemiBold)
                    Text(tip, modifier = Modifier.weight(1f), color = TextPrimary, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}
