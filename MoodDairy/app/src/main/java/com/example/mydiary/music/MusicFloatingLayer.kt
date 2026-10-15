package com.example.mydiary.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mydiary.whitenoise.WhiteNoiseManager
import com.example.mydiary.whitenoise.WhiteNoiseSoundList
import kotlinx.coroutines.delay

/**
 * 音乐悬浮层（骨架实现，待补充）
 * 「听点音乐」推荐弹窗 + 可拖拽迷你播放条
 */
@Composable
fun MusicFloatingLayer(@Suppress("UNUSED_PARAMETER") controller: MusicWidgetController) {
    val manager = remember { WhiteNoiseManager.getInstance() }
    val sounds = remember { WhiteNoiseSoundList.getAllSounds() }
    var playing by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(manager) {
        while (true) {
            playing = sounds.filter { manager.isPlaying(it.id) }.map { it.name }
            delay(400)
        }
    }
    if (playing.isNotEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 84.dp), tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("白噪音播放中 · ${playing.joinToString("、")}", Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { manager.stopAllSounds(); playing = emptyList() }) { Text("停止") }
            }
        }
    }
}
