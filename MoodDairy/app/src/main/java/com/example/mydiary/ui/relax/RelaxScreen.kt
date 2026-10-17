package com.example.mydiary.ui.relax

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mydiary.ui.theme.*
import com.example.mydiary.whitenoise.WhiteNoiseSound
import com.example.mydiary.whitenoise.WhiteNoiseSoundList

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.models.RelaxTrack
import com.example.mydiary.whitenoise.WhiteNoiseScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RelaxMusicState(val loading: Boolean = false, val tracks: List<RelaxTrack> = emptyList(), val error: String? = null)

@HiltViewModel
class RelaxViewModel @Inject constructor(private val api: DiaryApiService) : ViewModel() {
    private val _state = MutableStateFlow(RelaxMusicState())
    val state = _state.asStateFlow()
    fun load() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = RelaxMusicState(loading = true)
            try {
                val response = api.relaxRecommendations()
                val tracks = response.body()?.tracks.orEmpty()
                _state.value = if (response.isSuccessful && tracks.isNotEmpty()) RelaxMusicState(tracks = tracks)
                    else RelaxMusicState(error = "暂未获取到音乐，请重试或选择白噪音。")
            } catch (exc: CancellationException) { throw exc }
            catch (_: Exception) { _state.value = RelaxMusicState(error = "音乐服务暂时无法连接，请重试或选择白噪音。") }
        }
    }
}

@Composable
fun RelaxScreen(onNavigateBack: () -> Unit, controller: RelaxPlaybackController,
                initialTab: Int = 0, viewModel: RelaxViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val playback by controller.state.collectAsState()
    RelaxContent(state, viewModel::load, onNavigateBack, playback, controller::toggle,
        controller::pause, controller::seekTo, controller::stop,
        onNoise = controller::playNoise, initialTab = initialTab)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelaxContent(state: RelaxMusicState, onReload: () -> Unit, onNavigateBack: () -> Unit,
                          playback: RelaxPlaybackState = RelaxPlaybackState(),
                          onPlay: (RelaxTrack) -> Unit = {}, onPause: () -> Unit = {},
                          onSeek: (Long) -> Unit = {}, onStop: () -> Unit = {},
                          onNoise: (WhiteNoiseSound) -> Unit = {}, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var category by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    val sounds = remember { WhiteNoiseSoundList.getAllSounds() }
    val categories = remember { linkedMapOf("全部" to "全部", "RAIN" to "雨声", "NATURE" to "自然",
        "ANIMALS" to "动物", "PLACES" to "场所", "THINGS" to "物品", "TRANSPORT" to "交通",
        "URBAN" to "城市", "NOISE" to "噪音") }
    LaunchedEffect(tab) { if (tab == 0 && state.tracks.isEmpty()) onReload() }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("放松一下", color = TextPrimary, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回", tint = PrimaryDark) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }, modifier = Modifier.background(Brush.verticalGradient(listOf(PrimaryLight, SoftWhite, SoftWhite)))) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text("留一点时间，听听喜欢的声音", color = TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 16.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("音乐" to Icons.Default.MusicNote, "白噪音" to Icons.Default.Spa).forEachIndexed { index, (label, icon) ->
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            color = if (tab == index) PrimaryLight else Color.Transparent) {
                            Tab(selected = tab == index, onClick = { tab = index },
                                selectedContentColor = TextPrimary, unselectedContentColor = TextSecondary) {
                                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                    Text(label, fontSize = 15.sp, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
            Text("开始播放后会回到助手，小播放器陪你继续记录。", fontSize = 12.sp, color = PrimaryDark,
                modifier = Modifier.padding(vertical = 14.dp))
            if (tab == 1) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索雨声、鸟鸣、海浪…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(16.dp))
                androidx.compose.foundation.lazy.LazyRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories.keys.toList()) { key -> FilterChip(selected = category == key, onClick = { category = key },
                        label = { Text(categories.getValue(key)) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PrimaryLight)) }
                }
                val visible = sounds.filter { (category == "全部" || it.category == category) && it.name.contains(query.trim(), true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 160.dp)) {
                    if (visible.isEmpty()) item { Text("没有找到这个声音，试试其他关键词。", color = TextSecondary) }
                    items(visible, key = { it.id }) { sound ->
                        val selected = playback.whiteNoise && playback.trackId == sound.id
                        SoundRow(sound.name, categories[sound.category] ?: "环境声", selected, playback,
                            onClick = { if (selected && playback.playing) onPause() else onNoise(sound) },
                            artwork = { Text(sound.icon, fontSize = 25.sp) })
                    }
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 160.dp)) {
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = PrimaryVariant) }
                state.error?.let { error -> item { Text(error, color = TextSecondary); TextButton(onClick = onReload) { Text("重新加载", color = PrimaryDark) } } }
                items(state.tracks, key = { it.id }) { track ->
                    SoundRow(track.title, track.artist, !playback.whiteNoise && playback.trackId == track.id, playback,
                        onClick = { onPlay(track) }, artwork = {
                            if (track.coverUrl != null) AsyncImage(track.coverUrl, null, Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Icon(Icons.Default.MusicNote, null, tint = PrimaryDark, modifier = Modifier.size(26.dp))
                        })
                }
            }
        }
    }
}

@Composable
private fun SoundRow(title: String, subtitle: String, selected: Boolean, playback: RelaxPlaybackState,
                     onClick: () -> Unit, artwork: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, PrimaryVariant) else null) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(enabled = !(selected && playback.loading), onClick = onClick).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(PrimaryLight.copy(alpha = .6f)),
                    contentAlignment = Alignment.Center) { artwork() }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (selected && playback.loading) "正在准备声音…" else subtitle, color = TextSecondary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalIconButton(onClick = onClick, enabled = !(selected && playback.loading),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = PrimaryLight, contentColor = PrimaryDark)) {
                    if (selected && playback.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = PrimaryDark)
                    else Icon(if (selected && playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (selected && playback.playing) "暂停" else "播放")
                }
            }
            if (selected) playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) }
        }
    }
}

