package com.example.mydiary.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.mydiary.data.models.ChatMessage
import com.example.mydiary.ui.theme.DividerColor
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.Surface as AppSurface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary
import com.example.mydiary.ui.theme.TextTertiary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    userId: Long,
    onNavigateToLive2D: () -> Unit = {},
    onClientAction: (ChatClientAction) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    val messages by viewModel.messages.collectAsState()
    val pendingPlans by viewModel.pendingPlans.collectAsState()
    val serviceBundles by viewModel.serviceBundles.collectAsState()
    val faceEmotionPrompt by viewModel.faceEmotionPrompt.collectAsState()
    val faceEmotionStatus by viewModel.faceEmotionStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val emotionConsent by viewModel.emotionConsentGranted.collectAsState()
    val emotionEnabled by viewModel.emotionCaptureEnabled.collectAsState()
    val shouldAskEmotionConsent by viewModel.shouldAskEmotionConsent.collectAsState()
    val isEmotionCapturing by viewModel.isEmotionCapturing.collectAsState()
    val listState = rememberLazyListState()
    val latestInputText by rememberUpdatedState(inputText)

    var editingPlan by remember { mutableStateOf<PendingToolPlanUi?>(null) }
    var editingText by remember { mutableStateOf("") }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    val onRecognizedText by rememberUpdatedState(newValue = { recognizedText: String ->
        val normalized = recognizedText.trim()
        if (normalized.isNotBlank()) {
            inputText = listOf(latestInputText.trim(), normalized)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    })

    val onRecognitionError by rememberUpdatedState(newValue = { message: String ->
        viewModel.showError(message)
    })

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            selectedImageUris.add(uri)
        }
    }

    val audioFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null && !isLoading) {
            viewModel.sendVoiceMessage(uri.toString())
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            isListening = false
            viewModel.showError("未授予麦克风权限，无法语音输入")
            return@rememberLauncherForActivityResult
        }

        val recognizer = speechRecognizer
        if (recognizer == null) {
            isListening = false
            viewModel.showError("当前设备不支持语音输入")
            return@rememberLauncherForActivityResult
        }

        isListening = true
        startSpeechRecognition(recognizer)
    }

    DisposableEffect(speechRecognizer) {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            onDispose { }
        } else {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (error == SpeechRecognizer.ERROR_CLIENT) return
                    onRecognitionError(mapSpeechRecognizerError(error))
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val recognizedText = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    onRecognizedText(recognizedText)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            onDispose {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }

    // 表情采集生命周期：前台且已授权+开关开启+有相机权限时启动，后台即停止
    DisposableEffect(lifecycleOwner, emotionConsent, emotionEnabled, hasCameraPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (emotionConsent == true && emotionEnabled && hasCameraPermission) {
                        viewModel.startEmotionCapture(lifecycleOwner)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.stopEmotionCapture()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopEmotionCapture()
        }
    }

    LaunchedEffect(userId) {
        viewModel.initializeChat(userId)
    }

    LaunchedEffect(Unit) {
        viewModel.clientActions.collect { action ->
            onClientAction(action)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val targetIndex = messages.lastIndex
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val shouldAnimate = lastVisibleIndex >= targetIndex - 3
            if (shouldAnimate) {
                listState.animateScrollToItem(targetIndex)
            } else {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MintGreen.copy(alpha = 0.3f),
                        PaleYellow.copy(alpha = 0.3f),
                        SoftWhite,
                    ),
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatTopBar(
                onNavigateToLive2D = onNavigateToLive2D,
                isEmotionCapturing = isEmotionCapturing,
            )
            if (isEmotionCapturing) {
                Text(
                    text = faceEmotionStatus,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            errorMessage?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "error",
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::clearError) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "close_error",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (pendingPlans.isNotEmpty()) {
                PendingPlanSection(
                    plans = pendingPlans,
                    onConfirm = { plan ->
                        viewModel.submitPlanConfirmation(plan.planId, approved = true)
                    },
                    onCancel = { plan ->
                        viewModel.submitPlanConfirmation(plan.planId, approved = false)
                    },
                    onEdit = { plan ->
                        editingPlan = plan
                        editingText = plan.argumentsJson
                    },
                )
            }

            if (serviceBundles.isNotEmpty()) {
                EmotionServiceBundleSection(
                    bundles = serviceBundles,
                    onAction = viewModel::performServiceAction,
                    onDismiss = viewModel::dismissServiceBundle,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(
                    items = messages,
                    key = { message ->
                        "${message.timestamp}-${message.isFromUser}-${message.content.hashCode()}-${message.imageUris.size}-${message.isStreaming}"
                    },
                ) { message ->
                    ChatMessageItem(message = message)
                }
            }

            ChatInputBar(
                inputText = inputText,
                selectedImageUris = selectedImageUris,
                enabled = !isLoading,
                isListening = isListening,
                onInputChange = { inputText = it },
                onAddImage = { imagePickerLauncher.launch("image/*") },
                onPickVoiceFile = { audioFilePickerLauncher.launch("audio/*") },
                onVoiceInput = {
                    if (speechRecognizer == null) {
                        viewModel.showError("当前设备不支持语音输入")
                        return@ChatInputBar
                    }

                    if (isListening) {
                        speechRecognizer.stopListening()
                        isListening = false
                        return@ChatInputBar
                    }

                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        isListening = true
                        startSpeechRecognition(speechRecognizer)
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onRemoveImage = { index ->
                    if (index in selectedImageUris.indices) {
                        selectedImageUris.removeAt(index)
                    }
                },
                onSend = {
                    if ((inputText.isNotBlank() || selectedImageUris.isNotEmpty()) && !isLoading) {
                        val uriStrings = selectedImageUris.map(Uri::toString)
                        viewModel.sendMessage(
                            content = inputText,
                            imageUris = uriStrings,
                            imagePreviewUris = uriStrings,
                        )
                        inputText = ""
                        selectedImageUris.clear()
                    }
                },
            )
        }
    }

    if (faceEmotionPrompt != null && !shouldAskEmotionConsent && editingPlan == null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFaceEmotionPrompt(false) },
            title = { Text("想休息一下吗？") },
            text = { Text("我注意到你可能有些不舒服。表情识别也可能有误，你愿意看看音乐、放松和心情记录建议吗？") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissFaceEmotionPrompt(true) }) { Text("看看建议") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissFaceEmotionPrompt(false) }) { Text("暂时不用") }
            },
        )
    }

    if (editingPlan != null) {
        EditPlanDialog(
            initialJson = editingText,
            onDismiss = {
                editingPlan = null
            },
            onConfirm = { editedJson ->
                val plan = editingPlan ?: return@EditPlanDialog
                viewModel.submitPlanConfirmation(
                    planId = plan.planId,
                    approved = true,
                    editedArgumentsJson = editedJson,
                )
                editingPlan = null
            },
        )
    }

    // 表情识别授权弹窗：首次进入聊天页（尚无授权记录）时强制用户做出选择
    if (shouldAskEmotionConsent) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = "表情识别授权") },
            text = {
                Text(
                    text = "为了更准确地理解你的情绪状态，聊天时我们会定时通过前置摄像头进行表情识别。\n\n" +
                        "识别全部在你的设备本地完成，照片不会离开手机，也不会被保存；" +
                        "我们只向服务器上传情绪标签（如 happy、sad）。\n\n" +
                        "你可以随时关闭此功能。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setEmotionCaptureConsent(true)
                        if (!hasCameraPermission) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                ) {
                    Text("同意")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setEmotionCaptureConsent(false) }) {
                    Text("拒绝")
                }
            },
        )
    }
}

@Composable
private fun PendingPlanSection(
    plans: List<PendingToolPlanUi>,
    onConfirm: (PendingToolPlanUi) -> Unit,
    onCancel: (PendingToolPlanUi) -> Unit,
    onEdit: (PendingToolPlanUi) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "待确认操作",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        plans.forEach { plan ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AppSurface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "操作：${plan.title}", color = TextPrimary, fontSize = 14.sp)
                    if (plan.preview.isNotBlank()) {
                        Text(text = "内容预览：${plan.preview}", color = TextSecondary, fontSize = 13.sp)
                    }
                    Text(text = "参数：${plan.argumentsJson}", color = TextSecondary, fontSize = 12.sp)
                    plan.expiresAt?.let { expiresAt ->
                        Text(text = "过期时间：$expiresAt", color = TextTertiary, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirm(plan) }) {
                            Text("确认")
                        }
                        TextButton(onClick = { onCancel(plan) }) {
                            Text("取消")
                        }
                        TextButton(onClick = { onEdit(plan) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "edit_plan",
                                tint = Primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("编辑后执行")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditPlanDialog(
    initialJson: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var jsonText by remember(initialJson) { mutableStateOf(initialJson) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑参数 JSON") },
        text = {
            OutlinedTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                maxLines = 12,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(jsonText) }) {
                Text("执行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ChatTopBar(
    onNavigateToLive2D: () -> Unit = {},
    isEmotionCapturing: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = "file:///android_asset/live2d/Diana/raw.preview.png",
                contentDescription = "assistant_avatar",
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                onNavigateToLive2D()
                            },
                        )
                    },
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "小嘉然",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Text(
                    text = "你的日记助手",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 表情采集指示点：采集期间显示小相机图标
            if (isEmotionCapturing) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = "emotion_capture_active",
                    tint = Primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(onClick = { }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "more",
                    tint = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmotionServiceBundleSection(
    bundles: List<EmotionServiceBundleUi>,
    onAction: (EmotionServiceActionUi) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bundles.forEach { bundle ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (bundle.isCrisis) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                } else {
                    AppSurface
                },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bundle.title,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = bundle.message,
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        IconButton(onClick = { onDismiss(bundle.bundleId) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "dismiss_service_bundle",
                                tint = TextSecondary,
                            )
                        }
                    }

                    if (bundle.triggerReason.isNotBlank()) {
                        Text(
                            text = bundle.triggerReason,
                            color = TextTertiary,
                            fontSize = 12.sp,
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bundle.services, key = { service -> "${bundle.bundleId}-${service.id}" }) { service ->
                            Button(
                                onClick = { onAction(service) },
                                modifier = Modifier.width(152.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Icon(
                                    imageVector = serviceActionIcon(service.action, service.id),
                                    contentDescription = service.action,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = service.title, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun serviceActionIcon(action: String, id: String): ImageVector {
    return when {
        action == "play_white_noise" -> Icons.Default.PlayArrow
        action == "open_music" -> Icons.Default.MusicNote
        action == "open_drawing" -> Icons.Default.Palette
        action == "open_test" || id.contains("todo") -> Icons.Default.Checklist
        action == "continue_chat" -> Icons.Default.Chat
        else -> Icons.Default.Send
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.isFromUser) {
            AsyncImage(
                model = "file:///android_asset/live2d/Diana/raw.preview.png",
                contentDescription = "assistant_avatar_small",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (message.isFromUser) 16.dp else 4.dp,
                        topEnd = if (message.isFromUser) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp,
                    ),
                )
                .background(if (message.isFromUser) Primary else AppSurface)
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.imageUris.isNotEmpty()) {
                    message.imageUris.forEach { imageUri ->
                        AsyncImage(
                            model = imageUri,
                            contentDescription = "chat_image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        fontSize = 15.sp,
                        color = if (message.isFromUser) Color.White else TextPrimary,
                        lineHeight = 22.sp,
                    )
                } else if (message.isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Primary,
                    )
                }
            }
        }

        if (message.isFromUser) {
            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "user",
                    tint = Primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    selectedImageUris: List<Uri>,
    enabled: Boolean,
    isListening: Boolean,
    onInputChange: (String) -> Unit,
    onAddImage: () -> Unit,
    onPickVoiceFile: () -> Unit,
    onVoiceInput: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppSurface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedImageUris.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(selectedImageUris) { index, uri ->
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = "selected_chat_image",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { onRemoveImage(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f)),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "remove_image",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onVoiceInput,
                    enabled = enabled,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) Primary.copy(alpha = 0.14f) else Color.Transparent,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = if (isListening) "stop_voice_input" else "voice_input",
                        tint = if (isListening) Color(0xFFE53935) else Primary,
                    )
                }

                IconButton(
                    onClick = onPickVoiceFile,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "pick_voice_file",
                        tint = Primary,
                    )
                }

                IconButton(
                    onClick = onAddImage,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = "add_image",
                        tint = Primary,
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    // 2026-10-02: AI 回复期间也允许输入，仅发送按钮在回复完成后才可用
                    enabled = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    placeholder = {
                        Text(
                            text = if (enabled) "输入消息..." else "正在回复中，可以先输入...",
                            color = TextTertiary,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = DividerColor,
                        disabledBorderColor = DividerColor.copy(alpha = 0.5f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                )

                val canSend = inputText.isNotBlank() || selectedImageUris.isNotEmpty()
                IconButton(
                    onClick = onSend,
                    enabled = enabled && canSend,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (enabled && canSend) Primary else Primary.copy(alpha = 0.3f)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun startSpeechRecognition(recognizer: SpeechRecognizer) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请开始说话")
    }
    recognizer.startListening(intent)
}

private fun mapSpeechRecognizerError(errorCode: Int): String {
    return when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "语音输入失败：录音异常"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音输入失败：缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音输入失败：网络异常"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到有效语音，请再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务异常"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时，请按住后直接说话"
        else -> "语音输入失败，请重试"
    }
}
