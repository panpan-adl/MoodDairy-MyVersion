package com.example.mydiary.ui.relax

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaxScreen(onNavigateBack: () -> Unit, viewModel: RelaxViewModel = hiltViewModel(),
                playerViewModel: RelaxPlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val playback by playerViewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(playerViewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playerViewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerViewModel.pause()
        }
    }
    RelaxContent(state = state, onReload = viewModel::load, onNavigateBack = onNavigateBack,
        playback = playback, onPlay = playerViewModel::toggle, onPause = playerViewModel::pause,
        onSeek = playerViewModel::seekTo, onStop = playerViewModel::stop)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelaxContent(state: RelaxMusicState, onReload: () -> Unit, onNavigateBack: () -> Unit,
                          playback: RelaxPlaybackState = RelaxPlaybackState(),
                          onPlay: (RelaxTrack) -> Unit = {}, onPause: () -> Unit = {},
                          onSeek: (Long) -> Unit = {}, onStop: () -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) { if (tab == 0 && state.tracks.isEmpty()) onReload() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("放松一下") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("音乐") })
                Tab(selected = tab == 1, onClick = { onPause(); tab = 1 }, text = { Text("白噪音") })
            }
            if (tab == 1) {
                WhiteNoiseScreen(onNavigateBack = onNavigateBack, embedded = true)
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("挑一首喜欢的歌，在这里播放。你可以随时暂停或换一首。", Modifier.padding(top = 16.dp)) }
                if (state.loading) item { CircularProgressIndicator() }
                state.error?.let { error -> item { Text(error); TextButton(onClick = onReload) { Text("重试") } } }
                items(state.tracks, key = { it.id }) { track ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(track.artist)
                            val selected = playback.trackId == track.id
                            Row {
                                TextButton(onClick = { onPlay(track) }, enabled = !(selected && playback.loading)) {
                                    Text(when {
                                        selected && playback.loading -> "加载中…"
                                        selected && playback.playing -> "暂停"
                                        selected && playback.error != null -> "重试播放"
                                        selected && playback.positionMs > 0 -> "继续播放"
                                        else -> "播放"
                                    })
                                }
                                if (selected) TextButton(onClick = onStop) { Text("停止") }
                            }
                            if (selected) {
                                if (playback.durationMs > 0) {
                                    Slider(value = playback.positionMs.coerceIn(0, playback.durationMs).toFloat(),
                                        valueRange = 0f..playback.durationMs.toFloat(),
                                        onValueChange = { onSeek(it.toLong()) })
                                    Text("${playbackTime(playback.positionMs)} / ${playbackTime(playback.durationMs)}")
                                }
                                playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

private fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
