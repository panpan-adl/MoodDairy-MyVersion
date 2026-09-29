package com.example.mydiary.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mydiary.data.models.EmotionAnalysis
import com.example.mydiary.data.models.FusionInsightResponse
import com.example.mydiary.data.models.MediaItem
import com.example.mydiary.data.models.ModalityInsightResponse
import com.example.mydiary.data.models.VoiceTranscription
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.ui.theme.*
import com.example.mydiary.util.ErrorHandler
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 日记编辑器界面
 * 创建和编辑日记，支持多媒体内容
 * 
 * 需求：2.1, 2.2, 2.5, 3.1, 4.1, 5.1, 6.1, 12.4
 * 用户需求Priority 1: 支持通过diaryId编辑已有日记
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditorScreen(
    date: LocalDate,
    diaryId: Long? = null,
    onNavigateBack: () -> Unit,
    onNavigateToDrawing: (Long?) -> Unit = {},
    onDrawingMediaSaved: () -> Unit = {},
    backStackEntry: androidx.navigation.NavBackStackEntry? = null,
    viewModel: DiaryEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val editorDebugLog = remember { java.io.File(context.filesDir, "debug_editor.log") }
    val uiState by viewModel.uiState.collectAsState()

    // #region agent debug log - 21cff5 (H2: diaryId null crash)
    val TAG_EDITOR = "DiaryEditor"
    fun logEditorEvent(message: String, data: Map<String, Any?> = emptyMap()) {
        try {
            val logFile = editorDebugLog
            val dataStr = data.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            val entry = "{\"id\":\"log_${System.currentTimeMillis()}\",\"timestamp\":${System.currentTimeMillis()},\"location\":\"DiaryEditorScreen.kt\",\"message\":\"$message\",\"data\":{$dataStr},\"hypothesisId\":\"H2\",\"runId\":\"debug-run-1\"}\n"
            logFile.appendText(entry)
        } catch (_: Exception) {}
    }
    // #endregion

    LaunchedEffect(date, diaryId) {
        logEditorEvent("LaunchedEffect triggered", mapOf("date" to date.toString(), "diaryId" to (diaryId?.toString() ?: "null")))
        viewModel.initialize(date, diaryId)
    }

    LaunchedEffect(uiState.diaryId) {
        uiState.diaryId?.let(viewModel::refreshMultimodalInsight)
    }

    // 监听从绘画结果页返回时传递的已保存媒体项
    LaunchedEffect(backStackEntry) {
        val handle = backStackEntry?.savedStateHandle ?: return@LaunchedEffect
        val key = "drawing_saved_media"
        val data = handle.get<List<String>>(key)
        if (!data.isNullOrEmpty()) {
            handle.remove<List<String>>(key)
            logEditorEvent("Detected drawing saved media", mapOf("dataSize" to data.size))
            android.util.Log.d(TAG_EDITOR, "检测到绘画保存的媒体: ${data.size} 项，开始刷新日记")
            if (uiState.diaryId != null) {
                logEditorEvent("Calling refreshMediaItems", mapOf("diaryId" to uiState.diaryId.toString()))
                viewModel.refreshMediaItems(uiState.diaryId!!)
            } else {
                logEditorEvent("WARNING: diaryId is null, cannot refresh", mapOf("diaryId" to "null"))
            }
        }
    }
    
    // 本地UI状态
    var showMediaMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // 图片选择器 - 选择后上传到后端
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 将 content:// URI 转换为临时文件并上传
            scope.launch {
                try {
                    // 获取文件的 MIME 类型
                    val mimeType = context.contentResolver.getType(it)
                    val extension = when {
                        mimeType?.startsWith("image/") == true -> {
                            when {
                                mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                                mimeType.contains("png") -> ".png"
                                mimeType.contains("gif") -> ".gif"
                                mimeType.contains("webp") -> ".webp"
                                else -> ".jpg" // 默认
                            }
                        }
                        else -> {
                            android.util.Log.e("DiaryEditor", "选择的文件不是图片格式: $mimeType")
                            android.widget.Toast.makeText(
                                context,
                                "请选择图片文件（支持: jpg, png, gif, webp）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                    }
                    
                    val inputStream = context.contentResolver.openInputStream(it)
                    if (inputStream != null) {
                        val tempFile = File.createTempFile("upload_image_", extension, context.cacheDir)
                        tempFile.outputStream().use { output ->
                            inputStream.copyTo(output)
                        }
                        inputStream.close()
                        viewModel.uploadAndAddMedia(tempFile, "image")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DiaryEditor", "图片上传失败", e)
                    android.widget.Toast.makeText(
                        context,
                        "图片上传失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    // 音频选择器 - 选择后上传到后端
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 将 content:// URI 转换为临时文件并上传
            scope.launch {
                try {
                    // 获取文件的 MIME 类型和扩展名
                    val mimeType = context.contentResolver.getType(it)
                    val extension = when {
                        mimeType?.startsWith("audio/") == true -> {
                            // 根据 MIME 类型确定扩展名（与后端支持的格式保持一致）
                            when {
                                mimeType.contains("mpeg") || mimeType.contains("mp3") -> ".mp3"
                                mimeType.contains("mp4") || mimeType.contains("m4a") -> ".m4a"
                                mimeType.contains("wav") -> ".wav"
                                mimeType.contains("ogg") -> ".ogg"
                                mimeType.contains("flac") -> ".flac"
                                mimeType.contains("webm") -> ".webm"
                                else -> ".m4a" // 默认
                            }
                        }
                        else -> {
                            // 如果不是音频类型，显示错误
                            android.util.Log.e("DiaryEditor", "选择的文件不是音频格式: $mimeType")
                            android.widget.Toast.makeText(
                                context,
                                "请选择音频文件（支持: mp3, m4a, wav, ogg, flac, webm）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                    }
                    
                    val inputStream = context.contentResolver.openInputStream(it)
                    if (inputStream != null) {
                        val tempFile = File.createTempFile("upload_audio_", extension, context.cacheDir)
                        tempFile.outputStream().use { output ->
                            inputStream.copyTo(output)
                        }
                        inputStream.close()
                        viewModel.uploadAndAddMedia(tempFile, "audio")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DiaryEditor", "音频上传失败", e)
                    android.widget.Toast.makeText(
                        context,
                        "音频上传失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    // 视频选择器 - 选择后上传到后端
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 将 content:// URI 转换为临时文件并上传
            scope.launch {
                try {
                    // 获取文件的 MIME 类型
                    val mimeType = context.contentResolver.getType(it)
                    val extension = when {
                        mimeType?.startsWith("video/") == true -> {
                            when {
                                mimeType.contains("mp4") -> ".mp4"
                                mimeType.contains("avi") -> ".avi"
                                mimeType.contains("mov") || mimeType.contains("quicktime") -> ".mov"
                                mimeType.contains("mkv") -> ".mkv"
                                else -> ".mp4" // 默认
                            }
                        }
                        else -> {
                            android.util.Log.e("DiaryEditor", "选择的文件不是视频格式: $mimeType")
                            android.widget.Toast.makeText(
                                context,
                                "请选择视频文件（支持: mp4, avi, mov, mkv）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                    }
                    
                    val inputStream = context.contentResolver.openInputStream(it)
                    if (inputStream != null) {
                        val tempFile = File.createTempFile("upload_video_", extension, context.cacheDir)
                        tempFile.outputStream().use { output ->
                            inputStream.copyTo(output)
                        }
                        inputStream.close()
                        viewModel.uploadAndAddMedia(tempFile, "video")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DiaryEditor", "视频上传失败", e)
                    android.widget.Toast.makeText(
                        context,
                        "视频上传失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    // 临时文件URI（用于拍照和录像）
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var tempVideoUri by remember { mutableStateOf<Uri?>(null) }
    // 临时文件路径（用于上传）
    var tempPhotoFile by remember { mutableStateOf<File?>(null) }
    var tempVideoFile by remember { mutableStateOf<File?>(null) }
    
    // 创建临时文件的函数
    fun createTempImageFile(): Uri {
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", storageDir)
        tempPhotoFile = file  // 保存文件引用用于上传
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    
    fun createTempVideoFile(): Uri {
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val file = File.createTempFile("video_${System.currentTimeMillis()}", ".mp4", storageDir)
        tempVideoFile = file  // 保存文件引用用于上传
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    
    // 拍照启动器 - 拍照后上传到后端
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoFile != null) {
            viewModel.uploadAndAddMedia(tempPhotoFile!!, "image")
        }
    }
    
    // 录像启动器 - 录像后上传到后端
    val takeVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success && tempVideoFile != null) {
            viewModel.uploadAndAddMedia(tempVideoFile!!, "video")
        }
    }
    
    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，根据当前操作启动相机
        }
    }
    
    // 录音权限请求
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，可以开始录音
        }
    }
    
    // 录音状态
    var isRecordingAudio by remember { mutableStateOf(false) }
    var showAudioRecordingDialog by remember { mutableStateOf(false) }
    
    // 语音保存选项对话框状态 - 需求 5.1: 录音完成后弹出保存选项对话框
    var showVoiceSaveOptionDialog by remember { mutableStateOf(false) }
    var pendingAudioFile by remember { mutableStateOf<File?>(null) }
    var pendingAudioDuration by remember { mutableStateOf(0L) }
    
    // 语音处理（带选项）状态
    val voiceProcessingWithOptionsState by viewModel.voiceProcessingWithOptionsState.collectAsState()
    
    // 画画引导对话框状态 - 需求：在记完日记之后，必定给出选项，是否要画画
    var showDrawingPromptDialog by remember { mutableStateOf(false) }
    var savedDiaryId by remember { mutableStateOf<Long?>(null) }

    // AI 绘图功能启用状态（控制是否显示绘画引导对话框）
    val drawingEnabled by viewModel.drawingEnabled.collectAsState()

    // 初始化时获取 AI 绘图配置
    LaunchedEffect(Unit) {
        viewModel.fetchDrawingEnabled()
    }

    // 显示保存成功提示并弹出画画引导
    LaunchedEffect(uiState.saveSuccess, uiState.diaryId, drawingEnabled) {
        if (uiState.saveSuccess && drawingEnabled) {
            ErrorHandler.showSuccess(
                message = "保存成功",
                snackbarHostState = snackbarHostState,
                scope = scope
            )
            // 保存日记ID并显示画画引导对话框
            savedDiaryId = uiState.diaryId ?: diaryId
            showDrawingPromptDialog = true
            viewModel.resetSaveSuccess()
        } else if (uiState.saveSuccess && !drawingEnabled) {
            // AI 绘图功能已禁用，仅显示保存成功提示
            ErrorHandler.showSuccess(
                message = "保存成功",
                snackbarHostState = snackbarHostState,
                scope = scope
            )
            viewModel.resetSaveSuccess()
        }
    }
    
    // 显示错误提示
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = error,
                    duration = SnackbarDuration.Short
                )
                viewModel.clearError()
            }
        }
    }
    
    // 处理语音处理错误
    LaunchedEffect(uiState.voiceProcessingState) {
        if (uiState.voiceProcessingState is VoiceProcessingState.Error) {
            val errorState = uiState.voiceProcessingState as VoiceProcessingState.Error
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = errorState.message,
                    duration = SnackbarDuration.Long
                )
            }
        }
    }
    
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 顶部栏 - 绿色延伸到状态栏
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Primary)
            ) {
                // 状态栏占位（绿色背景）
                Spacer(modifier = Modifier.statusBarsPadding())
                
                // 导航栏内容
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 返回按钮
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    
                    // 日期标题
                    Text(
                        text = formatDate(date),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // 高光时刻按钮（灯泡图标）
                    IconButton(
                        onClick = { viewModel.toggleHighlight() }
                    ) {
                        Icon(
                            imageVector = if (uiState.isHighlight) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                            contentDescription = "高光时刻",
                            tint = if (uiState.isHighlight) Color(0xFFFFD700) else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        )
                    }
                    
                    // 小确幸按钮（四叶草/Spa图标）
                    IconButton(
                        onClick = { viewModel.toggleLittleJoy() }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Spa,
                            contentDescription = "小确幸",
                            tint = if (uiState.isLittleJoy) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        )
                    }
                    
                    // 保存按钮
                    IconButton(
                        onClick = { viewModel.saveDiary() },
                        enabled = !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "保存",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            // 媒体添加按钮
            FloatingActionButton(
                onClick = { showMediaMenu = !showMediaMenu },
                containerColor = Secondary,
                contentColor = OnPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加媒体"
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MintGreen.copy(alpha = 0.3f),
                            PaleYellow.copy(alpha = 0.3f),
                            SoftWhite
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // 加载指示器
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // 标题输入区域
                    TextField(
                        value = uiState.title,
                        onValueChange = { viewModel.updateTitle(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = {
                            Text(
                                text = "标题（可选）",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        singleLine = true
                    )
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    uiState.multimodalInsight?.let { insight ->
                        MultimodalInsightCard(
                            fusion = insight.fusionAnalysis,
                            textAnalysis = insight.textAnalysis,
                            voiceAnalysis = insight.voiceAnalysis,
                            imageAnalysis = insight.imageAnalysis,
                            videoAnalysis = insight.videoAnalysis,
                            drawingAnalysis = insight.drawingAnalysis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    
                    // 媒体列表显示区域
                    if (uiState.mediaItems.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(uiState.mediaItems.sortedBy { it.order }) { mediaItem ->
                                when (mediaItem) {
                                    is MediaItem.Text -> TextMediaItem(
                                        mediaItem = mediaItem,
                                        onContentChange = { newContent ->
                                            viewModel.updateTextContent(mediaItem.id, newContent)
                                        },
                                        onDelete = { viewModel.removeMediaItem(mediaItem.id) }
                                    )
                                    is MediaItem.Image -> ImageMediaItem(
                                        mediaItem = mediaItem,
                                        onDelete = { viewModel.removeMediaItem(mediaItem.id) }
                                    )
                                    is MediaItem.Audio -> AudioMediaItem(
                                        mediaItem = mediaItem,
                                        onDelete = { viewModel.removeMediaItem(mediaItem.id) }
                                    )
                                    is MediaItem.Video -> VideoMediaItem(
                                        mediaItem = mediaItem,
                                        onDelete = { viewModel.removeMediaItem(mediaItem.id) }
                                    )
                                }
                            }
                        }
                    } else {
                        // 空状态提示
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "点击右下角按钮添加内容",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
                
                // 媒体添加菜单
                if (showMediaMenu) {
                    MediaAddMenu(
                        onDismiss = { showMediaMenu = false },
                        // 图片选项
                        onTakePhoto = {
                            // 检查相机权限
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                                == PackageManager.PERMISSION_GRANTED) {
                                tempPhotoUri = createTempImageFile()
                                tempPhotoUri?.let { takePictureLauncher.launch(it) }
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            showMediaMenu = false
                        },
                        onPickImage = {
                            imagePickerLauncher.launch("image/*")
                            showMediaMenu = false
                        },
                        // 语音选项
                        onRecordAudio = {
                            // 检查录音权限
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                                == PackageManager.PERMISSION_GRANTED) {
                                showAudioRecordingDialog = true
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            showMediaMenu = false
                        },
                        onPickAudio = {
                            audioPickerLauncher.launch("audio/*")
                            showMediaMenu = false
                        },
                        // 视频选项
                        onRecordVideo = {
                            // 检查相机权限
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                                == PackageManager.PERMISSION_GRANTED) {
                                tempVideoUri = createTempVideoFile()
                                tempVideoUri?.let { takeVideoLauncher.launch(it) }
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            showMediaMenu = false
                        },
                        onPickVideo = {
                            videoPickerLauncher.launch("video/*")
                            showMediaMenu = false
                        },
                        // 文字选项
                        onTextClick = {
                            val textItem = MediaItem.Text(
                                id = "text_${System.currentTimeMillis()}",  // 使用前缀确保是临时 ID
                                content = "",
                                order = 0,
                                createdAt = Instant.now()
                            )
                            viewModel.addMediaItem(textItem)
                            showMediaMenu = false
                        }
                    )
                }
                
                // 录音对话框
                if (showAudioRecordingDialog) {
                    AudioRecordingDialog(
                        onDismiss = { showAudioRecordingDialog = false },
                        onRecordingComplete = { audioUri, duration ->
                            // 需求 5.1: 录音完成后显示选项对话框
                            // 将 URI 转换为 File 并保存
                            scope.launch {
                                try {
                                    val inputStream = context.contentResolver.openInputStream(audioUri)
                                    if (inputStream != null) {
                                        // 使用 .mp3 格式（已由 AudioRecorder 转换）
                                        val tempFile = File.createTempFile("voice_recording_", ".mp3", context.cacheDir)
                                        tempFile.outputStream().use { output ->
                                            inputStream.copyTo(output)
                                        }
                                        inputStream.close()
                                        
                                        // 保存录音文件和时长，显示选项对话框
                                        pendingAudioFile = tempFile
                                        pendingAudioDuration = duration
                                        showVoiceSaveOptionDialog = true
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("DiaryEditor", "处理录音文件失败", e)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "处理录音文件失败: ${e.message}",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            }
                            showAudioRecordingDialog = false
                        }
                    )
                }
                
                // 语音保存选项对话框 - 需求 1.1, 5.1
                if (showVoiceSaveOptionDialog && pendingAudioFile != null) {
                    VoiceSaveOptionDialog(
                        onDismiss = {
                            showVoiceSaveOptionDialog = false
                            // 清理临时文件
                            pendingAudioFile?.delete()
                            pendingAudioFile = null
                            pendingAudioDuration = 0L
                        },
                        onConfirm = { saveAudio, convertToText ->
                            showVoiceSaveOptionDialog = false
                            
                            // 需求 1.2, 1.3, 1.4: 根据选项调用处理方法
                            pendingAudioFile?.let { audioFile ->
                                viewModel.processVoiceWithOptions(
                                    audioFile = audioFile,
                                    saveAudio = saveAudio,
                                    convertToText = convertToText
                                )
                            }
                        }
                    )
                }
                
                // 语音处理（带选项）进度指示器 - 需求 2.4, 5.2
                if (voiceProcessingWithOptionsState.isProcessing) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "正在处理语音...",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = when (voiceProcessingWithOptionsState.saveMode) {
                                            1 -> "保存原语音中..."
                                            2 -> "转换成流畅文字中..."
                                            3 -> "保存语音并转换文字中..."
                                            else -> "处理中..."
                                        },
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 语音处理（带选项）结果对话框 - 需求 5.3, 5.5, 5.6
                // 只有当有文本结果时才显示对话框（save_mode=2 或 3）
                if (voiceProcessingWithOptionsState.processedText != null && 
                    voiceProcessingWithOptionsState.originalText != null &&
                    !voiceProcessingWithOptionsState.isProcessing) {
                    VoiceProcessingWithOptionsResultDialog(
                        originalText = voiceProcessingWithOptionsState.originalText!!,
                        processedText = voiceProcessingWithOptionsState.processedText!!,
                        emotion = voiceProcessingWithOptionsState.emotion,
                        onSave = {
                            // 保存已完成（媒体项已在 ViewModel 中添加）
                            viewModel.clearVoiceProcessingWithOptionsState()
                            // 清理临时文件
                            pendingAudioFile?.delete()
                            pendingAudioFile = null
                            pendingAudioDuration = 0L
                        },
                        onRetry = {
                            // 需求 5.6: 支持重试操作
                            viewModel.clearVoiceProcessingWithOptionsState()
                            pendingAudioFile?.let { audioFile ->
                                // 重新显示选项对话框
                                showVoiceSaveOptionDialog = true
                            }
                        },
                        onDismiss = {
                            viewModel.clearVoiceProcessingWithOptionsState()
                            // 清理临时文件
                            pendingAudioFile?.delete()
                            pendingAudioFile = null
                            pendingAudioDuration = 0L
                        }
                    )
                }
                
                // save_mode=1 时（仅保存音频），处理完成后显示成功提示
                // 当 mediaId 不为空且没有文本结果时，说明是仅保存音频的情况
                LaunchedEffect(
                    voiceProcessingWithOptionsState.mediaId,
                    voiceProcessingWithOptionsState.isProcessing,
                    voiceProcessingWithOptionsState.processedText
                ) {
                    if (voiceProcessingWithOptionsState.mediaId != null &&
                        !voiceProcessingWithOptionsState.isProcessing &&
                        voiceProcessingWithOptionsState.processedText == null &&
                        voiceProcessingWithOptionsState.saveMode == 1) {
                        // 仅保存音频成功
                        snackbarHostState.showSnackbar(
                            message = "语音已保存",
                            duration = SnackbarDuration.Short
                        )
                        // 清理状态和临时文件
                        viewModel.clearVoiceProcessingWithOptionsState()
                        pendingAudioFile?.delete()
                        pendingAudioFile = null
                        pendingAudioDuration = 0L
                    }
                }
                
                // 语音处理（带选项）错误提示
                LaunchedEffect(voiceProcessingWithOptionsState.error) {
                    voiceProcessingWithOptionsState.error?.let { error ->
                        snackbarHostState.showSnackbar(
                            message = error,
                            duration = SnackbarDuration.Long
                        )
                    }
                }
            }
            
            // 错误提示已经通过 SnackbarHost 处理，移除旧的 Snackbar
            
            // 语音处理结果对话框
            // 需求：9.6, 10.3, 10.4, 11.3
            if (uiState.voiceProcessingState is VoiceProcessingState.Success) {
                val result = (uiState.voiceProcessingState as VoiceProcessingState.Success).result
                
                VoiceProcessingResultDialog(
                    voiceTranscription = result,
                    onUseOriginal = {
                        // 使用原始文本
                        val textItem = MediaItem.Text(
                            id = "text_${System.currentTimeMillis()}",
                            content = result.originalText,
                            order = 0, // Will be recalculated by ViewModel
                            createdAt = Instant.now()
                        )
                        viewModel.addMediaItem(textItem)
                        viewModel.clearVoiceProcessingState()
                    },
                    onUseProcessed = {
                        // 使用处理后的文本
                        val textItem = MediaItem.Text(
                            id = "text_${System.currentTimeMillis()}",
                            content = result.processedText,
                            order = 0, // Will be recalculated by ViewModel
                            createdAt = Instant.now()
                        )
                        viewModel.addMediaItem(textItem)
                        viewModel.clearVoiceProcessingState()
                    },
                    onDismiss = {
                        viewModel.clearVoiceProcessingState()
                    }
                )
            }
            
            // 语音处理中的加载指示器
            if (uiState.voiceProcessingState is VoiceProcessingState.Processing) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "正在处理语音...",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // 画画引导对话框 - 需求：在记完日记之后，必定给出选项，是否要画画
    if (showDrawingPromptDialog) {
        DrawingPromptDialog(
            onDismiss = {
                showDrawingPromptDialog = false
            },
            onGoToDrawing = {
                showDrawingPromptDialog = false
                onNavigateToDrawing(savedDiaryId)
            }
        )
    }
}

/**
 * 媒体添加菜单
 * 显示文字、图片、语音、视频四个选项，每个选项有子菜单
 */
@Composable
private fun MediaAddMenu(
    onDismiss: () -> Unit,
    onTextClick: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onRecordAudio: () -> Unit,
    onPickAudio: () -> Unit,
    onRecordVideo: () -> Unit,
    onPickVideo: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "添加媒体",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            // 文字按钮
            MediaMenuItem(
                icon = Icons.Default.TextFields,
                text = "文字",
                onClick = onTextClick
            )
            
            // 图片选项（拍照 / 从相册选择）
            MediaMenuItemWithOptions(
                icon = Icons.Default.Image,
                text = "图片",
                option1Text = "拍照",
                option1Icon = Icons.Default.CameraAlt,
                onOption1Click = onTakePhoto,
                option2Text = "从相册选择",
                option2Icon = Icons.Default.Folder,
                onOption2Click = onPickImage
            )
            
            // 语音选项（录音 / 从文件选择）
            MediaMenuItemWithOptions(
                icon = Icons.Default.Mic,
                text = "语音",
                option1Text = "录音",
                option1Icon = Icons.Default.Mic,
                onOption1Click = onRecordAudio,
                option2Text = "从文件选择",
                option2Icon = Icons.Default.Folder,
                onOption2Click = onPickAudio
            )
            
            // 视频选项（录像 / 从文件选择）
            MediaMenuItemWithOptions(
                icon = Icons.Default.Videocam,
                text = "视频",
                option1Text = "录像",
                option1Icon = Icons.Default.Videocam,
                onOption1Click = onRecordVideo,
                option2Text = "从文件选择",
                option2Icon = Icons.Default.Folder,
                onOption2Click = onPickVideo
            )
            
            // 取消按钮
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("取消")
            }
        }
    }
}

/**
 * 带子选项的媒体菜单项
 */
@Composable
private fun MediaMenuItemWithOptions(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    option1Text: String,
    option1Icon: androidx.compose.ui.graphics.vector.ImageVector,
    onOption1Click: () -> Unit,
    option2Text: String,
    option2Icon: androidx.compose.ui.graphics.vector.ImageVector,
    onOption2Click: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // 选项按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 选项1
                OutlinedButton(
                    onClick = onOption1Click,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = option1Icon,
                        contentDescription = option1Text,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = option1Text,
                        fontSize = 13.sp
                    )
                }
                
                // 选项2
                OutlinedButton(
                    onClick = onOption2Click,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = option2Icon,
                        contentDescription = option2Text,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = option2Text,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * 媒体菜单项
 */
@Composable
private fun MediaMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 录音对话框
 */
@Composable
private fun AudioRecordingDialog(
    onDismiss: () -> Unit,
    onRecordingComplete: (Uri, Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    
    // 使用 AudioRecorder
    val audioRecorder = remember { com.example.mydiary.util.AudioRecorder(context) }
    
    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            if (audioRecorder.isRecording()) {
                audioRecorder.cancelRecording()
            }
            audioRecorder.release()
        }
    }
    
    // 录音计时器
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordingDuration += 1000
            }
        }
    }
    
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            if (!isRecording) {
                onDismiss()
            }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "录音",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 录音时长显示
                Text(
                    text = formatRecordingDuration(recordingDuration),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light,
                    color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 录音按钮
                IconButton(
                    onClick = {
                        if (isRecording) {
                            // 停止录音并转换为 MP3
                            scope.launch {
                                try {
                                    val result = audioRecorder.stopRecordingAndConvert()
                                    isRecording = false
                                    if (result.success && audioFile != null) {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            result.file // 使用转换后的文件
                                        )
                                        onRecordingComplete(uri, result.duration)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    isRecording = false
                                }
                            }
                        } else {
                            // 开始录音
                            try {
                                val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                                // 修复：使用 .m4a 格式，与 AudioRecorder 配置一致
                                audioFile = File.createTempFile("audio_${System.currentTimeMillis()}", ".m4a", storageDir)
                                audioFile?.let { file ->
                                    audioRecorder.startRecording(file)
                                    recordingDuration = 0
                                    isRecording = true
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    modifier = Modifier.size(80.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Pause else Icons.Default.Mic,
                                contentDescription = if (isRecording) "停止录音" else "开始录音",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = if (isRecording) "点击停止录音" else "点击开始录音",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 取消按钮
                if (!isRecording) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

/**
 * 格式化录音时长
 */
private fun formatRecordingDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / 1000) / 60
    return String.format("%02d:%02d", minutes, seconds)
}

/**
 * 格式化日期显示
 */
private fun formatDate(date: LocalDate): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日")
    return date.format(formatter)
}


// ============================================================================
// 媒体项组件 (Media Item Components)
// ============================================================================

/**
 * 文字媒体项组件
 * 需求：2.5, 3.1
 */
@Composable
fun TextMediaItem(
    mediaItem: MediaItem.Text,
    onContentChange: (String) -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var isEditing by remember { mutableStateOf(mediaItem.content.isEmpty()) }
    var editText by remember(mediaItem.content) { mutableStateOf(mediaItem.content) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.TextFields,
                        contentDescription = "文字",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "文字",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 可编辑的文本输入框
            TextField(
                value = editText,
                onValueChange = { newText ->
                    editText = newText
                    onContentChange(newText)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                placeholder = {
                    Text(
                        text = "在这里输入文字...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

/**
 * 图片媒体项组件
 * 需求：4.4, 4.5
 */
@Composable
fun ImageMediaItem(
    mediaItem: MediaItem.Image,
    onDelete: () -> Unit = {}
) {
    var showFullScreen by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "图片",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "图片",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 图片缩略图（点击查看全屏）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.outline,
                onClick = { showFullScreen = true }
            ) {
                // 使用Coil加载图片
                val imageUrl = mediaItem.thumbnailUrl ?: mediaItem.url
                android.util.Log.d("ImageMediaItem", "=====================================")
                android.util.Log.d("ImageMediaItem", "MediaItem.Image 详情:")
                android.util.Log.d("ImageMediaItem", "  id: ${mediaItem.id}")
                android.util.Log.d("ImageMediaItem", "  assetId: ${mediaItem.assetId}")
                android.util.Log.d("ImageMediaItem", "  url: ${mediaItem.url}")
                android.util.Log.d("ImageMediaItem", "  thumbnailUrl: ${mediaItem.thumbnailUrl}")
                android.util.Log.d("ImageMediaItem", "  最终加载URL: $imageUrl")
                android.util.Log.d("ImageMediaItem", "  URL是否为http(s): ${imageUrl.startsWith("http")}")
                android.util.Log.d("ImageMediaItem", "=====================================")
                
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .listener(
                                onStart = { request ->
                                    loadError = null
                                    android.util.Log.d("ImageMediaItem", "开始加载图片: ${request.data}")
                                },
                                onError = { request, result ->
                                    loadError = result.throwable.message ?: "未知错误"
                                    android.util.Log.e("ImageMediaItem", "=====================================")
                                    android.util.Log.e("ImageMediaItem", "图片加载失败!")
                                    android.util.Log.e("ImageMediaItem", "  URL: ${request.data}")
                                    android.util.Log.e("ImageMediaItem", "  错误: ${result.throwable.message}")
                                    android.util.Log.e("ImageMediaItem", "  错误类型: ${result.throwable.javaClass.simpleName}")
                                    result.throwable.printStackTrace()
                                    android.util.Log.e("ImageMediaItem", "=====================================")
                                },
                                onSuccess = { request, _ ->
                                    loadError = null
                                    android.util.Log.d("ImageMediaItem", "图片加载成功: ${request.data}")
                                }
                            )
                            .build(),
                        contentDescription = "图片预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    
                    // 显示加载错误信息
                    if (loadError != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "加载失败",
                                tint = Color.Red,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "图片加载失败",
                                color = Color.Red,
                                fontSize = 12.sp
                            )
                            Text(
                                text = loadError ?: "",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                maxLines = 2
                            )
                            Text(
                                text = "URL: ${imageUrl.take(50)}...",
                                color = Color.Gray,
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
    
    // 全屏预览对话框
    if (showFullScreen) {
        ImageFullScreenDialog(
            imageUrl = mediaItem.url,
            onDismiss = { showFullScreen = false }
        )
    }
}

/**
 * 图片全屏预览对话框
 * 需求：4.5
 */
@Composable
private fun ImageFullScreenDialog(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // 全屏图片显示
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "全屏图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                
                // 关闭按钮
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * 音频媒体项组件
 * 需求：5.6
 * 
 * 修复：使用 prepareAsync() 避免 ANR
 */
@Composable
fun AudioMediaItem(
    mediaItem: MediaItem.Audio,
    onDelete: () -> Unit = {}
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) }
    var totalDuration by remember { mutableStateOf(mediaItem.duration) }
    var isPrepared by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    
    // MediaPlayer 实例
    val mediaPlayer = remember {
        android.media.MediaPlayer().apply {
            // 设置音频流类型为音乐
            setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
        }
    }
    
    // 异步加载音频
    LaunchedEffect(mediaItem.url) {
        try {
            android.util.Log.d("AudioMediaItem", "=== 开始加载音频 ===")
            android.util.Log.d("AudioMediaItem", "URL: ${mediaItem.url}")
            android.util.Log.d("AudioMediaItem", "assetId: ${mediaItem.assetId}")
            
            isLoading = true
            loadError = null
            
            // 在主线程中设置 MediaPlayer
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    mediaPlayer.apply {
                        reset()
                        
                        // 设置准备完成监听器（必须在 setDataSource 之前）
                        setOnPreparedListener { mp ->
                            totalDuration = mp.duration / 1000 // 转换为秒
                            isPrepared = true
                            isLoading = false
                            android.util.Log.d("AudioMediaItem", "✅ 音频加载成功，时长: $totalDuration 秒")
                        }
                        
                        // 设置错误监听器（必须在 setDataSource 之前）
                        setOnErrorListener { mp, what, extra ->
                            val errorMsg = when (what) {
                                android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN -> "未知错误"
                                android.media.MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "服务器错误"
                                else -> "错误码: $what"
                            }
                            loadError = "音频加载失败: $errorMsg (extra=$extra)"
                            isLoading = false
                            isPrepared = false
                            android.util.Log.e("AudioMediaItem", "❌ 音频加载失败")
                            android.util.Log.e("AudioMediaItem", "  URL: ${mediaItem.url}")
                            android.util.Log.e("AudioMediaItem", "  what=$what, extra=$extra")
                            android.util.Log.e("AudioMediaItem", "  错误: $errorMsg")
                            true
                        }
                        
                        // 尝试直接使用 URL 字符串而不是 Uri
                        try {
                            android.util.Log.d("AudioMediaItem", "尝试方式1: 使用 URL 字符串")
                            setDataSource(mediaItem.url)
                            android.util.Log.d("AudioMediaItem", "setDataSource 成功，开始 prepareAsync")
                        } catch (e: Exception) {
                            android.util.Log.e("AudioMediaItem", "方式1失败，尝试方式2: 使用 Uri", e)
                            reset()
                setDataSource(context, Uri.parse(mediaItem.url))
                            android.util.Log.d("AudioMediaItem", "setDataSource (Uri) 成功，开始 prepareAsync")
                        }
                        
                        // 异步准备（不会阻塞主线程）
                        prepareAsync()
                    }
            } catch (e: Exception) {
                    loadError = "设置音频源失败: ${e.message}"
                    isLoading = false
                    isPrepared = false
                    android.util.Log.e("AudioMediaItem", "❌ 设置音频源异常", e)
                    android.util.Log.e("AudioMediaItem", "  URL: ${mediaItem.url}")
                    android.util.Log.e("AudioMediaItem", "  异常类型: ${e.javaClass.simpleName}")
                    android.util.Log.e("AudioMediaItem", "  异常信息: ${e.message}")
                }
            }
        } catch (e: Exception) {
            loadError = "音频加载异常: ${e.message}"
            isLoading = false
            isPrepared = false
            android.util.Log.e("AudioMediaItem", "❌ 外层异常", e)
        }
    }
    
    // 播放完成监听
    DisposableEffect(mediaPlayer) {
        mediaPlayer.setOnCompletionListener {
            isPlaying = false
            currentPosition = 0
        }
        
        onDispose {
            if (isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer.release()
        }
    }
    
    // 更新播放进度
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = mediaPlayer.currentPosition / 1000
            kotlinx.coroutines.delay(500)
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "音频",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "音频",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(
                    onClick = {
                        // 停止播放并释放资源
                        if (isPlaying) {
                            mediaPlayer.pause()
                            isPlaying = false
                        }
                        onDelete()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 显示加载状态或错误信息
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "加载中...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (loadError != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "加载失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = loadError ?: "加载失败",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "URL: ${mediaItem.url.take(50)}...",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
            // 音频播放控件
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 播放/暂停按钮
                IconButton(
                    onClick = {
                            if (!isPrepared) return@IconButton
                        try {
                            if (isPlaying) {
                                mediaPlayer.pause()
                                isPlaying = false
                            } else {
                                mediaPlayer.start()
                                isPlaying = true
                            }
                        } catch (e: Exception) {
                                android.util.Log.e("AudioMediaItem", "播放控制失败", e)
                        }
                    },
                        enabled = isPrepared,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = if (isPrepared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                // 进度条
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    LinearProgressIndicator(
                        progress = { 
                            if (totalDuration > 0) currentPosition.toFloat() / totalDuration 
                            else 0f 
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(currentPosition),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDuration(totalDuration),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        }
                    }
                }
            }
            
            // 显示转录文本（如果有）
            mediaItem.transcription?.let { transcription ->
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "转录文本：",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transcription.processedText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 视频媒体项组件
 * 需求：6.4, 6.5
 */
@Composable
fun VideoMediaItem(
    mediaItem: MediaItem.Video,
    onDelete: () -> Unit = {}
) {
    var isPlaying by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "视频",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "视频",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 视频缩略图和播放控件
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black,
                onClick = {
                    isPlaying = !isPlaying
                    // 播放逻辑将在后续实现
                }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // 视频缩略图
                    // AsyncImage(
                    //     model = mediaItem.thumbnailUrl,
                    //     contentDescription = "视频缩略图",
                    //     modifier = Modifier.fillMaxSize(),
                    //     contentScale = ContentScale.Crop
                    // )
                    
                    // 临时占位符
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "视频缩略图",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // 播放按钮覆盖层
                    if (!isPlaying) {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color.Black.copy(alpha = 0.6f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 视频时长
            Text(
                text = "时长: ${formatDuration(mediaItem.duration)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 格式化时长（秒转为 mm:ss 格式）
 */
private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}

// ============================================================================
// 语音处理结果对话框 (Voice Processing Result Dialog)
// ============================================================================

/**
 * 语音处理结果对话框
 * 显示原始文本、优化后的文本和情感分析结果
 * 允许用户选择使用哪个版本，支持重试功能
 * 
 * 需求：2.5, 5.3, 5.4, 9.6, 10.3, 10.4, 11.3
 * - 2.5: 处理完成后同时展示原始文本和流畅文本供用户对比
 * - 5.3: 显示原文与流畅文字对比
 * - 5.4: 显示情绪类型和评分
 * 
 * @param voiceTranscription 语音转录结果
 * @param onUseOriginal 选择使用原始文本的回调
 * @param onUseProcessed 选择使用处理后文本的回调
 * @param onRetry 重新处理的回调（可选）
 * @param onDismiss 关闭对话框的回调
 */
@Composable
fun VoiceProcessingResultDialog(
    voiceTranscription: VoiceTranscription,
    onUseOriginal: () -> Unit,
    onUseProcessed: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "语音处理结果",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 情感分析结果卡片 - 需求 5.4: 显示情绪类型和评分
                EmotionAnalysisCard(emotion = voiceTranscription.emotion)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 文本对比区域 - 需求 2.5, 5.3: 同时展示原始文本和流畅文本供用户对比
                TextComparisonSection(
                    originalText = voiceTranscription.originalText,
                    processedText = voiceTranscription.processedText,
                    onUseOriginal = onUseOriginal,
                    onUseProcessed = onUseProcessed
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 底部按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 重新处理按钮（如果提供了回调）
                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Primary
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "重新处理",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    
                    // 关闭按钮
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "关闭",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 文本对比区域
 * 并排显示原始文本和流畅文本，方便用户对比选择
 * 
 * 需求：2.5, 5.3
 * 
 * @param originalText 原始文本
 * @param processedText 处理后的流畅文本
 * @param onUseOriginal 选择使用原始文本的回调
 * @param onUseProcessed 选择使用处理后文本的回调
 */
@Composable
private fun TextComparisonSection(
    originalText: String,
    processedText: String,
    onUseOriginal: () -> Unit,
    onUseProcessed: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 原始文本卡片
        TextVersionCard(
            title = "原始文本",
            text = originalText,
            onSelect = onUseOriginal,
            isRecommended = false
        )
        
        // 优化后的文本卡片（推荐）
        TextVersionCard(
            title = "流畅文本",
            text = processedText,
            onSelect = onUseProcessed,
            isRecommended = true
        )
    }
}

/**
 * 情感分析结果卡片
 * 显示情感类型、评分和置信度
 * 
 * 需求：5.4, 10.3, 10.4
 * - 5.4: 显示情绪类型和评分
 * 
 * @param emotion 情感分析结果
 */
@Composable
private fun EmotionAnalysisCard(emotion: EmotionAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Secondary.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "情感分析",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                // 情感类型标签
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = getEmotionColor(emotion.emotionType)
                ) {
                    Text(
                        text = emotion.emotionType,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 情感评分
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "评分：",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                
                // 评分进度条
                LinearProgressIndicator(
                    progress = { emotion.score / 100f },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = getEmotionColor(emotion.emotionType),
                    trackColor = Color.LightGray,
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "${emotion.score}/100",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 置信度
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "置信度：",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "${(emotion.confidence * 100).toInt()}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
        }
    }
}

/**
 * 文本版本卡片
 * 显示文本内容并提供选择按钮
 * 
 * 需求：2.5, 5.3, 9.6, 11.3
 * - 2.5, 5.3: 展示原始文本和流畅文本供用户对比
 * 
 * @param title 标题
 * @param text 文本内容
 * @param onSelect 选择该版本的回调
 * @param isRecommended 是否为推荐版本
 */
@Composable
private fun TextVersionCard(
    title: String,
    text: String,
    onSelect: () -> Unit,
    isRecommended: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecommended) {
                Primary.copy(alpha = 0.1f)
            } else {
                Color(0xFFF5F5F5)
            }
        ),
        border = if (isRecommended) {
            androidx.compose.foundation.BorderStroke(2.dp, Primary)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                if (isRecommended) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Primary
                    ) {
                        Text(
                            text = "推荐",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnPrimary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 文本内容
            Text(
                text = text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 使用按钮
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecommended) Primary else TextSecondary,
                    contentColor = if (isRecommended) OnPrimary else Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "使用此版本",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 根据情感类型获取对应的颜色
 * 
 * 需求：3.2 - 支持识别以下情绪类型
 * - 积极：开心、兴奋、满足、感激
 * - 消极：低落、沮丧、伤心、焦虑、愤怒
 * - 中性：平静、思考
 * 
 * @param emotionType 情感类型
 * @return 对应的颜色
 */
private fun getEmotionColor(emotionType: String): Color {
    return when (emotionType.lowercase()) {
        // 积极情绪 - 绿色系
        "开心", "快乐", "happy", "joy", "positive", "积极" -> Color(0xFF4CAF50) // 绿色
        "兴奋", "excited", "excitement" -> Color(0xFFE91E63) // 粉色
        "满足", "satisfied", "content" -> Color(0xFF8BC34A) // 浅绿色
        "感激", "grateful", "thankful" -> Color(0xFF009688) // 青色
        
        // 消极情绪 - 冷色系
        "低落", "down", "depressed" -> Color(0xFF607D8B) // 蓝灰色
        "沮丧", "frustrated", "upset" -> Color(0xFF795548) // 棕色
        "伤心", "悲伤", "sad", "sadness" -> Color(0xFF2196F3) // 蓝色
        "焦虑", "anxious", "anxiety" -> Color(0xFFFF9800) // 橙色
        "愤怒", "angry", "anger" -> Color(0xFFF44336) // 红色
        "恐惧", "fear", "scared" -> Color(0xFF9C27B0) // 紫色
        
        // 中性情绪 - 灰色系
        "平静", "calm", "neutral", "中性" -> Color(0xFF9E9E9E) // 灰色
        "思考", "thinking", "contemplative" -> Color(0xFF78909C) // 蓝灰色
        
        else -> Color(0xFF607D8B) // 默认蓝灰色
    }
}


// ============================================================================
// 语音处理（带选项）结果对话框 (Voice Processing With Options Result Dialog)
// ============================================================================

/**
 * 语音处理（带选项）结果对话框
 * 显示原始文本、优化后的文本和情感分析结果
 * 支持保存和重试操作
 * 
 * 需求：2.5, 5.3, 5.4, 5.5, 5.6
 * - 2.5: 处理完成后同时展示原始文本和流畅文本供用户对比
 * - 5.3: 显示原文与流畅文字对比
 * - 5.4: 显示情绪类型和评分
 * - 5.5: 用户点击取消中止当前操作
 * - 5.6: 发生错误时显示友好提示并支持重试
 * 
 * @param originalText 原始转录文本
 * @param processedText 处理后的流畅文本
 * @param emotion 情绪分析结果（可选）
 * @param onSave 保存回调
 * @param onRetry 重试回调
 * @param onDismiss 关闭对话框回调
 */
@Composable
fun VoiceProcessingWithOptionsResultDialog(
    originalText: String,
    processedText: String,
    emotion: VoiceEmotionResult?,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "处理完成",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 情感分析结果卡片 - 需求 5.4: 显示情绪类型和评分
                if (emotion != null) {
                    VoiceEmotionCard(emotion = emotion)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // 原始文本区域
                TextDisplayCard(
                    title = "原文",
                    text = originalText,
                    backgroundColor = Color(0xFFF5F5F5)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 流畅文本区域（推荐）- 需求 2.5, 5.3
                TextDisplayCard(
                    title = "流畅文字",
                    text = processedText,
                    backgroundColor = Primary.copy(alpha = 0.1f),
                    isHighlighted = true
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 底部按钮行 - 需求 5.5, 5.6
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 重新处理按钮 - 需求 5.6
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Primary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "重新处理",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // 保存按钮
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "保存",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 语音情绪分析结果卡片
 * 
 * 需求：5.4 - 显示情绪类型和评分
 * 
 * @param emotion 情绪分析结果
 */
@Composable
private fun VoiceEmotionCard(emotion: VoiceEmotionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Secondary.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "情绪分析",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                // 情绪类型标签
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = getEmotionColorForResult(emotion.type)
                ) {
                    Text(
                        text = emotion.type,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 情绪评分
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "评分：",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                
                // 评分进度条
                LinearProgressIndicator(
                    progress = { emotion.score / 100f },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = getEmotionColorForResult(emotion.type),
                    trackColor = Color.LightGray,
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "${emotion.score}/100",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun MultimodalInsightCard(
    fusion: FusionInsightResponse,
    textAnalysis: ModalityInsightResponse,
    voiceAnalysis: ModalityInsightResponse,
    imageAnalysis: ModalityInsightResponse,
    videoAnalysis: ModalityInsightResponse,
    drawingAnalysis: ModalityInsightResponse,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("多模态分析", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(fusion.explanation, fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightMetricChip("情绪 ${fusion.overallEmotion}", getEmotionColorForResult(fusion.overallEmotion))
                InsightMetricChip("压力 ${fusion.stressScore}", Color(0xFFFFB74D))
                InsightMetricChip("积极 ${fusion.positiveEventScore}", Color(0xFF66BB6A))
            }

            InsightModalityRow("文字", textAnalysis)
            InsightModalityRow("语音", voiceAnalysis)
            InsightModalityRow("图片", imageAnalysis)
            InsightModalityRow("视频", videoAnalysis)
            InsightModalityRow("绘画", drawingAnalysis)

            if (fusion.growthKeywords.isNotEmpty()) {
                Text("成长关键词：${fusion.growthKeywords.joinToString("、")}", color = TextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun InsightModalityRow(title: String, analysis: ModalityInsightResponse) {
    if (!analysis.available) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$title · ${analysis.emotion ?: "中性"}  ${analysis.emotionScore ?: 0}/100",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        analysis.summary?.let {
            Text(text = it, fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun InsightMetricChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 文本显示卡片
 * 
 * @param title 标题
 * @param text 文本内容
 * @param backgroundColor 背景颜色
 * @param isHighlighted 是否高亮显示
 */
@Composable
private fun TextDisplayCard(
    title: String,
    text: String,
    backgroundColor: Color,
    isHighlighted: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = if (isHighlighted) {
            androidx.compose.foundation.BorderStroke(2.dp, Primary)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                if (isHighlighted) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Primary
                    ) {
                        Text(
                            text = "推荐",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnPrimary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 文本内容
            Text(
                text = text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
            )
        }
    }
}

/**
 * 根据情绪类型获取对应的颜色（用于 VoiceEmotionResult）
 * 
 * @param emotionType 情绪类型
 * @return 对应的颜色
 */
private fun getEmotionColorForResult(emotionType: String): Color {
    return when (emotionType.lowercase()) {
        // 积极情绪 - 绿色系
        "开心", "快乐", "happy", "joy", "positive", "积极" -> Color(0xFF4CAF50)
        "兴奋", "excited", "excitement" -> Color(0xFFE91E63)
        "满足", "satisfied", "content" -> Color(0xFF8BC34A)
        "感激", "grateful", "thankful" -> Color(0xFF009688)
        
        // 消极情绪 - 冷色系
        "低落", "down", "depressed" -> Color(0xFF607D8B)
        "沮丧", "frustrated", "upset" -> Color(0xFF795548)
        "伤心", "悲伤", "sad", "sadness" -> Color(0xFF2196F3)
        "焦虑", "anxious", "anxiety" -> Color(0xFFFF9800)
        "愤怒", "angry", "anger" -> Color(0xFFF44336)
        "恐惧", "fear", "scared" -> Color(0xFF9C27B0)
        
        // 中性情绪 - 灰色系
        "平静", "calm", "neutral", "中性" -> Color(0xFF9E9E9E)
        "思考", "thinking", "contemplative" -> Color(0xFF78909C)
        
        else -> Color(0xFF607D8B) // 默认蓝灰色
    }
}

/**
 * 画画引导对话框
 * 在日记保存后提示用户是否要去画画
 * 
 * 需求：在记完日记之后，必定给出选项，是否要画画
 */
@Composable
private fun DrawingPromptDialog(
    onDismiss: () -> Unit,
    onGoToDrawing: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标
                Icon(
                    imageVector = Icons.Outlined.Palette,
                    contentDescription = "画画",
                    tint = Primary,
                    modifier = Modifier.size(64.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 标题
                Text(
                    text = "日记已保存",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 描述
                Text(
                    text = "要为今天的日记画一幅画吗？",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 稍后再说
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("稍后再说")
                    }
                    
                    // 去画画
                    Button(
                        onClick = onGoToDrawing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary
                        )
                    ) {
                        Text("去画画")
                    }
                }
            }
        }
    }
}
