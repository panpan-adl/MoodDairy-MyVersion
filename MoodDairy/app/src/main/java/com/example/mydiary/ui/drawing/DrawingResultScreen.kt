package com.example.mydiary.ui.drawing

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import android.webkit.MimeTypeMap
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.mydiary.data.network.ApiEndpoints
import coil.compose.AsyncImage
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHARE_CACHE_DIR = "shared_drawing_result"
private const val SAVE_RELATIVE_DIR = "MoodDiary"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingResultScreen(
    imageUrl: String,
    sharedViewModel: SharedDrawingViewModel? = null,
    onNavigateBack: () -> Unit,
    onNavigateToCalendar: () -> Unit = onNavigateBack,
    onNavigateToDrawingList: () -> Unit = {},
    viewModel: DrawingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setGeneratedImage: (String) -> Unit = { generatedImageUrl ->
        if (sharedViewModel != null) sharedViewModel.setGeneratedImage(generatedImageUrl) else viewModel.setGeneratedImage(generatedImageUrl)
    }
    val createVideo: () -> Unit = {
        if (sharedViewModel != null) sharedViewModel.createVideo() else viewModel.createVideo()
    }
    // 优先使用 sharedViewModel 的状态（由 NavGraph 注入）
    val uiState by (sharedViewModel?.uiState ?: viewModel.uiState).collectAsState()

    val resolvedImageUrl = resolveRemoteUrl(imageUrl)
    val resolvedVideoUrl = uiState.videoUrl?.let(::resolveRemoteUrl)
    val activeMediaUrl = resolvedVideoUrl ?: resolvedImageUrl
    val activeMimeType = if (uiState.videoUrl != null) "video/mp4" else "image/png"

    LaunchedEffect(imageUrl) {
        setGeneratedImage(imageUrl)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创作完成", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToCalendar) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    shareRemoteMedia(
                                        context = context,
                                        remoteUrl = activeMediaUrl,
                                        preferredMimeType = activeMimeType,
                                    )
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        "分享失败: ${error.message ?: "未知错误"}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            PaleYellow.copy(alpha = 0.3f),
                            SoftWhite,
                        ),
                    ),
                )
                .padding(paddingValues),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    AsyncImage(
                        model = resolvedImageUrl,
                        contentDescription = "生成的画作",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MintGreen.copy(alpha = 0.15f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircle,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "让画作动起来",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "将静态画作转换为动态视频",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(18.dp))

                        val videoStatusDisplay = uiState.videoStatus?.let { raw ->
                            when (raw.lowercase()) {
                                "pending" -> "请耐心等待"
                                else -> raw
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                            uiState.isCreatingVideo -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = Primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = buildString {
                                            append("正在生成视频")
                                            videoStatusDisplay?.let { append(" · ").append(it) }
                                        },
                                        fontSize = 14.sp,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            uiState.videoUrl != null -> {
                                val videoUrl = resolvedVideoUrl
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(34.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "视频生成完成",
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 内嵌视频播放器
                                    if (videoUrl != null) {
                                        EmbeddedVideoPlayer(
                                            videoUrl = videoUrl,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedButton(
                                        onClick = {
                                            if (videoUrl != null) {
                                                openRemoteVideo(context, videoUrl)
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("在播放器中打开")
                                    }
                                }
                            }

                            else -> {
                                Button(
                                    onClick = createVideo,
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                ) {
                                    Icon(
                                        Icons.Filled.Movie,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("生成视频")
                                }
                            }
                            }
                        }

                        uiState.videoError?.let { error ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = error,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    saveRemoteMedia(
                                        context = context,
                                        remoteUrl = activeMediaUrl,
                                        preferredMimeType = activeMimeType,
                                    )
                                }.onSuccess {
                                    Toast.makeText(context, "已保存到系统相册/文件", Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        "保存失败: ${error.message ?: "未知错误"}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存", fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            sharedViewModel?.reset()
                            onNavigateToDrawingList()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("再创作", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private suspend fun shareRemoteMedia(
    context: Context,
    remoteUrl: String,
    preferredMimeType: String? = null,
) {
    val media = downloadRemoteMedia(remoteUrl, preferredMimeType)
    val extension = resolveExtension(remoteUrl, media.mimeType)

    val sharedFile = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        File(directory, "drawing_result_${System.currentTimeMillis()}.$extension").apply {
            writeBytes(media.bytes)
        }
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        sharedFile,
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = media.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(shareIntent, "分享作品").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private suspend fun saveRemoteMedia(
    context: Context,
    remoteUrl: String,
    preferredMimeType: String? = null,
): Uri {
    val media = downloadRemoteMedia(remoteUrl, preferredMimeType)
    val extension = resolveExtension(remoteUrl, media.mimeType)
    val isVideo = media.mimeType.startsWith("video/")

    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val relativePath = if (isVideo) {
        "${Environment.DIRECTORY_MOVIES}/$SAVE_RELATIVE_DIR"
    } else {
        "${Environment.DIRECTORY_PICTURES}/$SAVE_RELATIVE_DIR"
    }

    val contentValues = ContentValues().apply {
        put(
            MediaStore.MediaColumns.DISPLAY_NAME,
            "drawing_result_${System.currentTimeMillis()}.$extension",
        )
        put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(collection, contentValues)
        ?: throw IOException("无法创建保存目标")

    try {
        resolver.openOutputStream(uri)?.use { output ->
            output.write(media.bytes)
        } ?: throw IOException("无法写入保存文件")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
        }
        return uri
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private fun openRemoteVideo(context: Context, videoUrl: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(videoUrl), "video/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "选择播放器").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        context.startActivity(chooser)
    }.getOrElse {
        Toast.makeText(context, "无法打开视频，请先安装可播放视频的应用", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun downloadRemoteMedia(
    remoteUrl: String,
    preferredMimeType: String? = null,
): DownloadedMedia = withContext(Dispatchers.IO) {
    val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }

    try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IOException("下载失败，HTTP $responseCode")
        }

        val mimeType = preferredMimeType
            ?: connection.contentType?.substringBefore(';')?.trim().orEmpty()
                .ifBlank { guessMimeTypeFromUrl(remoteUrl) }
                .ifBlank { "image/png" }

        val bytes = connection.inputStream.use { input -> input.readBytes() }
        DownloadedMedia(bytes = bytes, mimeType = mimeType)
    } finally {
        connection.disconnect()
    }
}

private fun resolveExtension(remoteUrl: String, mimeType: String): String {
    val byMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    if (!byMime.isNullOrBlank()) {
        return byMime
    }

    val cleanUrl = remoteUrl.substringBefore('?').substringBefore('#')
    val byUrl = cleanUrl.substringAfterLast('.', "").lowercase()
    if (byUrl.isNotBlank()) {
        return byUrl
    }

    return if (mimeType.startsWith("video/")) "mp4" else "png"
}

private fun guessMimeTypeFromUrl(remoteUrl: String): String {
    val extension = remoteUrl
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase()

    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
}

private data class DownloadedMedia(
    val bytes: ByteArray,
    val mimeType: String,
)

private fun resolveRemoteUrl(url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return url
    }

    val normalizedPath = when {
        url.startsWith("/uploads/") -> "/media/${url.removePrefix("/uploads/")}"
        url.startsWith("uploads/") -> "/media/${url.removePrefix("uploads/")}"
        url.startsWith("/") -> url
        else -> "/$url"
    }
    val base = ApiEndpoints.apiBaseUrl.trimEnd('/')
    return "$base$normalizedPath"
}

@Composable
private fun EmbeddedVideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var playerError by rememberSaveable { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playerError = "播放失败: ${error.message ?: "未知错误"}"
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    if (playing) playerError = null
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Error overlay with retry button
            playerError?.let { errorMsg ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Movie,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMsg,
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
                            exoPlayer.setMediaItem(mediaItem)
                            exoPlayer.prepare()
                            playerError = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("重试", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
