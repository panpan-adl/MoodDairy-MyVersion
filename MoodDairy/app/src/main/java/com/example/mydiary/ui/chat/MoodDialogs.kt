package com.example.mydiary.ui.chat

import com.example.mydiary.ui.components.pageVerticalScroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(160.dp)
    val fontScale = LocalDensity.current.fontScale
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(24.dp),
            color = SoftWhite,
            shadowElevation = 8.dp,
        ) {
            BoxWithConstraints(
                Modifier.background(Brush.verticalGradient(listOf(PrimaryLight.copy(alpha = .55f), SoftWhite), endY = 260f)),
            ) {
                val stackActions = maxWidth < 320.dp || fontScale > 1.2f
                Column(
                    Modifier.pageVerticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(description, color = MoodBodyColor, fontSize = 13.sp, lineHeight = 20.sp)
                    content()
                    if (stackActions) {
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
        description = "以你的感受为准。表情有变化时，会先问你是否更新。",
        confirmLabel = "跟随表情识别", dismissLabel = "取消",
        onConfirm = { onSelect(null) }, onDismiss = onDismiss,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoodChangeTracker.choices.chunked(2).forEach { choices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices.forEach { mood ->
                        val display = FaceEmotionStatusUi.fromObservation(mood, 1f)
                        val selected = selectedMood == mood
                        Surface(
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                            color = if (selected) PrimaryLight else Color.White.copy(alpha = .9f),
                            border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PrimaryDark else Color(0xFFDDEAE4)),
                        ) {
                            Row(
                                Modifier.selectable(selected, role = Role.RadioButton, onClick = { onSelect(mood) })
                                    .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(display.icon, fontSize = 24.sp)
                                Text(display.label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = TextPrimary)
                                if (selected) Text("✓", color = PrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
