package com.example.mydiary.ui.relax

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
fun RelaxScreen(onNavigateBack: () -> Unit, viewModel: RelaxViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    RelaxContent(state = state, onReload = viewModel::load, onNavigateBack = onNavigateBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelaxContent(state: RelaxMusicState, onReload: () -> Unit, onNavigateBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    var playbackError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) { if (tab == 0 && state.tracks.isEmpty()) onReload() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("放松一下") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("音乐") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("白噪音") })
            }
            if (tab == 1) {
                WhiteNoiseScreen(onNavigateBack = onNavigateBack, embedded = true)
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("挑一首喜欢的歌。歌曲在网易云音乐或浏览器中播放，播放与暂停由其控制；不会自动播放。", Modifier.padding(top = 16.dp)) }
                if (state.loading) item { CircularProgressIndicator() }
                state.error?.let { error -> item { Text(error); TextButton(onClick = onReload) { Text("重试") } } }
                playbackError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                items(state.tracks, key = { it.id }) { track ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(track.artist)
                            TextButton(onClick = {
                                val uri = Uri.parse(track.jumpUrl)
                                if (uri.scheme == "https" && uri.host == "music.163.com") {
                                    playbackError = runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }.exceptionOrNull()?.let { "无法打开歌曲，请安装网易云音乐或浏览器。" }
                                } else playbackError = "歌曲链接暂不可用。"
                            }) { Text("去网易云播放") }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
