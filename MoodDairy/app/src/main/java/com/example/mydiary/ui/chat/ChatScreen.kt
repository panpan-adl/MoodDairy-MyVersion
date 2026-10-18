package com.example.mydiary.ui.chat

import com.example.mydiary.ui.components.PageLazyColumn
import com.example.mydiary.ui.theme.PrimaryDark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.example.mydiary.ui.theme.PageTopBackground
import com.example.mydiary.ui.theme.PageMiddleBackground
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.mydiary.data.local.StoredConversation
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    userId: Long,
    onNavigateToLive2D: () -> Unit = {},
    onNavigateToRecharge: () -> Unit = {},
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
    val selectedMood by viewModel.selectedMood.collectAsState()
    val moodChangeSuggestion by viewModel.moodChangeSuggestion.collectAsState()
    var showMoodPicker by remember { mutableStateOf(false) }
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val emotionConsent by viewModel.emotionConsentGranted.collectAsState()
    val emotionEnabled by viewModel.emotionCaptureEnabled.collectAsState()
    val shouldAskEmotionConsent by viewModel.shouldAskEmotionConsent.collectAsState()
    val isEmotionCapturing by viewModel.isEmotionCapturing.collectAsState()
    val deepThinkingEnabled by viewModel.deepThinkingEnabled.collectAsState()
    val historyConversations by viewModel.historyConversations.collectAsState()
    val listState = rememberLazyListState()
    val latestInputText by rememberUpdatedState(inputText)


    var showHistory by remember { mutableStateOf(false) }
    var exportingConversation by remember { mutableStateOf<StoredConversation?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val conv = exportingConversation
        if (uri != null && conv != null) {
            val text = viewModel.buildConversationExportText(conv.conversationId)
            if (text != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "对话已导出为文件", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "导出失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
        exportingConversation = null
    }

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

    // 改用 Vosk 离线中文识别（模拟器无 Google 语音服务也能收音）。进入聊天页即在后台预热模型。
    LaunchedEffect(Unit) {
        ChatVosk.ensureReady(context.applicationContext) { ok ->
            if (!ok) viewModel.showError("离线语音模型加载失败，可先打字聊")
        }
    }

    /** 启动 Vosk 聆听，识别到的文字并入输入框 */
    fun startVoskListening() {
        isListening = true
        ChatVosk.startListening(
            appContext = context.applicationContext,
            onResult = { text ->
                isListening = false
                val normalized = text.trim()
                if (normalized.isNotBlank()) {
                    inputText = listOf(latestInputText.trim(), normalized)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
            },
            onError = { msg ->
                isListening = false
                viewModel.showError(msg)
            },
        )
    }

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

        startVoskListening()
    }

    // 离开聊天页时停止离线收音
    DisposableEffect(Unit) {
        onDispose { ChatVosk.stop() }
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
    LaunchedEffect(userId, lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            com.example.mydiary.live2d.CompanionChatInput.inputs.collect { input ->
                if (input.userId == userId) viewModel.submitCompanionInput(input)
            }
        }
    }
    LaunchedEffect(isLoading) {
        com.example.mydiary.live2d.PetOverlayService.setChatReplyBusy(isLoading)
    }
    DisposableEffect(viewModel) {
        onDispose { com.example.mydiary.live2d.PetOverlayService.setChatReplyBusy(false) }
    }

    LaunchedEffect(Unit) {
        viewModel.clientActions.collect { action ->
            onClientAction(action)
        }
    }

    val entries = remember(messages) { chatTimelineEntries(messages) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        PageTopBackground,
                        PageMiddleBackground,
                        SoftWhite,
                    ),
                ),
            ),
    ) {
        ChatViewport(
            listState = listState,
            entries = entries,
            header = {
                ChatTopBar(
                    onNavigateToLive2D = onNavigateToLive2D,
                    onOpenRelax = { onClientAction(ChatClientAction("open_relax", emptyMap())) },
                    isEmotionCapturing = isEmotionCapturing,
                    faceEmotionStatus = faceEmotionStatus,
                    selectedMood = selectedMood,
                    onSelectMood = { showMoodPicker = true },
                    onOpenHistory = {
                        viewModel.loadHistoryConversations()
                        showHistory = true
                    },
                    onNewConversation = { viewModel.startNewConversation() },
                    deepThinkingEnabled = deepThinkingEnabled,
                    onToggleDeepThinking = viewModel::toggleDeepThinking,
                )

            },
            composer = {
                ChatInputBar(
                    inputText = inputText,
                    selectedImageUris = selectedImageUris,
                    enabled = !isLoading,
                    isListening = isListening,
                    onInputChange = { inputText = it },
                    onAddImage = { imagePickerLauncher.launch("image/*") },
                    onPickVoiceFile = { audioFilePickerLauncher.launch("audio/*") },
                    onVoiceInput = {
                        if (isListening) {
                            ChatVosk.stop()
                            isListening = false
                            return@ChatInputBar
                        }

                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            startVoskListening()
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
            },
        ) {
            if (errorMessage != null) item(key = "chat_error") {
                val error = errorMessage!!
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

            if (pendingPlans.isNotEmpty()) item(key = "chat_pending_plans") {
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

            if (serviceBundles.isNotEmpty()) item(key = "chat_service_bundles") {
                EmotionServiceBundleSection(
                    bundles = serviceBundles,
                    onAction = viewModel::performServiceAction,
                    onDismiss = viewModel::dismissServiceBundle,
                )
            }

            items(entries.asReversed(), key = { it.key }) { entry ->
                ChatMessageItem(message = entry.message, onNavigateToRecharge = onNavigateToRecharge, onEntryAction = viewModel::performEntryAction)
            }
        }
    }

    // 历史对话弹窗：查看/继续/导出/删除过去的对话
    if (showHistory) {
        ConversationHistoryDialog(
            conversations = historyConversations,
            onDismiss = { showHistory = false },
            onOpen = { conv ->
                showHistory = false
                viewModel.openConversation(conv.conversationId)
            },
            onExport = { conv ->
                exportingConversation = conv
                exportLauncher.launch("心语对话_${conv.title.take(12)}_${conv.updatedAt / 1000}.txt")
            },
            onDelete = { conv -> viewModel.deleteConversation(conv.conversationId) },
        )
    }

    if (showMoodPicker && !shouldAskEmotionConsent) {
        MoodPickerDialog(
            selectedMood = selectedMood,
            onSelect = { mood -> viewModel.selectMood(mood); showMoodPicker = false },
            onDismiss = { showMoodPicker = false },
        )
    }

    if (moodChangeSuggestion != null && !showMoodPicker && !shouldAskEmotionConsent && editingPlan == null) {
        MoodChangeDialog(
            current = FaceEmotionStatusUi.fromObservation(selectedMood, 1f),
            suggested = FaceEmotionStatusUi.fromObservation(moodChangeSuggestion, 1f),
            onRespond = viewModel::respondToMoodChange,
        )
    }

    if (faceEmotionPrompt != null && !showMoodPicker && moodChangeSuggestion == null && !shouldAskEmotionConsent && editingPlan == null) {
        MoodCareDialog(onRespond = viewModel::dismissFaceEmotionPrompt)
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
                    if (plan.tool != "search_social_content") Text(text = "参数：${plan.argumentsJson}", color = TextSecondary, fontSize = 12.sp)
                    plan.expiresAt?.let { expiresAt ->
                        Text(text = "过期时间：$expiresAt", color = TextTertiary, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirm(plan) }) {
                            Text(if (plan.tool == "search_social_content") "同意费用并搜索" else "确认")
                        }
                        TextButton(onClick = { onCancel(plan) }) {
                            Text("取消")
                        }
                        if (plan.tool != "search_social_content") TextButton(onClick = { onEdit(plan) }) {
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
internal fun ChatTopBar(
    onNavigateToLive2D: () -> Unit = {},
    onOpenRelax: () -> Unit = {},
    isEmotionCapturing: Boolean = false,
    faceEmotionStatus: FaceEmotionStatusUi = FaceEmotionStatusUi.Recognizing,
    selectedMood: String? = null,
    onSelectMood: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    deepThinkingEnabled: Boolean = false,
    onToggleDeepThinking: (Boolean) -> Unit = {},
) {
    val moodStatus = selectedMood?.let { FaceEmotionStatusUi.fromObservation(it, 1f) }
        ?: if (isEmotionCapturing) faceEmotionStatus else FaceEmotionStatusUi("🙂", "选择心情", "点击选择此刻的心情")
    val appearance = com.example.mydiary.live2d.rememberCompanionAppearance()
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
                model = "file:///android_asset/${appearance.avatarPath}",
                contentDescription = "assistant_avatar",
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                onNavigateToLive2D()
                            },
                        )
                    },
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "然然",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Text(
                    text = "你的日记助手",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                run {
                    Row(
                        modifier = Modifier.padding(top = 3.dp).fillMaxWidth().heightIn(min = 32.dp)
                            .clickable(onClickLabel = "选择心情", onClick = onSelectMood),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = moodStatus.icon, fontSize = 18.sp, maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = moodStatus.description })
                        Text(
                            text = moodStatus.label + if (selectedMood != null) " · 自选" else "",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

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

            // 历史对话入口
            IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "chat_history",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }

            // 新建对话
            IconButton(onClick = onNewConversation, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "new_conversation",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }

            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "对话设置",
                        tint = TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("放松一下", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = PrimaryDark) },
                        onClick = { menuExpanded = false; onOpenRelax() },
                    )
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("深度思考", color = TextPrimary)
                                Text("分析更深入，回复稍慢", fontSize = 12.sp, color = TextSecondary)
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = if (deepThinkingEnabled) Primary else TextSecondary,
                            )
                        },
                        trailingIcon = {
                            // The entire menu row owns the click, including its switch area.
                            androidx.compose.material3.Switch(
                                checked = deepThinkingEnabled,
                                onCheckedChange = null,
                            )
                        },
                        onClick = { onToggleDeepThinking(!deepThinkingEnabled) },
                    )
                }
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

                    val visibleServices = bundle.services.filterNot {
                        it.id == "healing_video" || it.payload["mode"] == "healing_video"
                    }
                    visibleServices.chunked(2).forEach { rowServices ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowServices.forEach { service ->
                                Button(
                                    onClick = { onAction(service) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = serviceActionIcon(service.action, service.id),
                                        contentDescription = service.action,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = service.title, fontSize = 13.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
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
        action == "open_music" || action == "open_relax" -> Icons.Default.MusicNote
        action == "open_drawing" -> Icons.Default.Palette
        action == "open_test" || id.contains("todo") -> Icons.Default.Checklist
        action == "continue_chat" -> Icons.Default.Chat
        else -> Icons.Default.Send
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageItem(message: ChatMessage, onNavigateToRecharge: () -> Unit = {}, onEntryAction: (com.example.mydiary.data.models.ChatServiceAction) -> Unit = {}) {
    val appearance = com.example.mydiary.live2d.rememberCompanionAppearance()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.isFromUser) {
            AsyncImage(
                model = "file:///android_asset/${appearance.avatarPath}",
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
                .combinedClickable(
                    onClick = {},
                    // 2026-10-13 长按消息气泡复制内容
                    onLongClick = {
                        if (message.content.isNotBlank()) {
                            clipboard.setText(AnnotatedString(message.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
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

    if (!message.isFromUser && !message.entryActions.isNullOrEmpty()) {
        Column(Modifier.fillMaxWidth().padding(start = 44.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            message.entryActions.orEmpty().forEach { action ->
                OutlinedButton(onClick = { onEntryAction(action) }, modifier = Modifier.fillMaxWidth()) { Text(action.title) }
            }
        }
    }

    // 社交搜索结果卡片：AI 消息附带，显示在气泡下方
    if (!message.isFromUser && !message.socialSearchResults.isNullOrEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            // 留出头像位置对齐气泡
            Spacer(modifier = Modifier.width(44.dp))
            SocialSearchResultsRow(
                keyword = message.socialSearchKeyword,
                items = message.socialSearchResults.orEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
    }

    // 搜索功能锁定：未绑定密钥/余额不足时显示引导卡片
    if (!message.isFromUser && message.socialSearchLocked) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(44.dp))
            VipUnlockCard(
                onRecharge = onNavigateToRecharge,
                modifier = Modifier.weight(1f),
                errorCode = message.socialSearchErrorCode,
            )
        }
    }
    }
}

@Composable
internal fun ChatInputBar(
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

/** 历史对话弹窗：查看/继续/导出/删除过去的对话 */
@Composable
private fun ConversationHistoryDialog(
    conversations: List<StoredConversation>,
    onDismiss: () -> Unit,
    onOpen: (StoredConversation) -> Unit,
    onExport: (StoredConversation) -> Unit,
    onDelete: (StoredConversation) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(20.dp),
            color = AppSurface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "历史对话",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextSecondary)
                    }
                }

                if (conversations.isEmpty()) {
                    Text(
                        text = "还没有历史对话\n\n每轮对话结束后稍作休息（约 10 分钟），\n就会自动归档到这里，方便以后查找。",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    PageLazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(conversations, key = { it.conversationId }) { conv ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SoftWhite,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(
                                        text = conv.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = fmt.format(Date(conv.updatedAt)),
                                        fontSize = 12.sp,
                                        color = TextTertiary,
                                    )
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { onOpen(conv) }) { Text("打开", fontSize = 13.sp) }
                                        TextButton(onClick = { onExport(conv) }) { Text("导出", fontSize = 13.sp) }
                                        TextButton(onClick = { onDelete(conv) }) {
                                            Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
