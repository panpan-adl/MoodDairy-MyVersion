package com.example.mydiary.ui.drawing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.mydiary.data.models.DrawingMode
import com.example.mydiary.data.models.DrawingStyle
import com.example.mydiary.ui.theme.DividerColor
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.Surface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary
import com.example.mydiary.ui.theme.TextTertiary
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingScreen(
    diaryId: Long? = null,
    initialPrompt: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToResult: (String, Long?) -> Unit,
    sharedViewModel: SharedDrawingViewModel? = null,
    viewModel: DrawingViewModel = hiltViewModel(),
) {
    // 如果有共享 ViewModel，同步状态
    LaunchedEffect(diaryId) {
        sharedViewModel?.setDiaryId(diaryId)
    }
    val context = LocalContext.current
    // 优先使用 sharedViewModel 的状态（由 NavGraph 注入）
    val uiState by (sharedViewModel?.uiState ?: viewModel.uiState).collectAsState()
    val setMode: (DrawingMode) -> Unit = { mode ->
        if (sharedViewModel != null) sharedViewModel.setMode(mode) else viewModel.setMode(mode)
    }
    val setPrompt: (String) -> Unit = { prompt ->
        if (sharedViewModel != null) sharedViewModel.setPrompt(prompt) else viewModel.setPrompt(prompt)
    }
    val setStyle: (DrawingStyle) -> Unit = { style ->
        if (sharedViewModel != null) sharedViewModel.setStyle(style) else viewModel.setStyle(style)
    }
    val setSketchBitmap: (Bitmap?) -> Unit = { bitmap ->
        if (sharedViewModel != null) sharedViewModel.setSketchBitmap(bitmap) else viewModel.setSketchBitmap(bitmap)
    }
    val showError: (String) -> Unit = { message ->
        if (sharedViewModel != null) sharedViewModel.showError(message) else viewModel.showError(message)
    }
    val generateFromText: () -> Unit = {
        if (sharedViewModel != null) sharedViewModel.generateFromText() else viewModel.generateFromText()
    }
    val enhanceSketch: (String, String) -> Unit = { imageDataUrl, prompt ->
        if (sharedViewModel != null) {
            sharedViewModel.enhanceSketch(imageDataUrl, prompt)
        } else {
            viewModel.enhanceSketch(imageDataUrl, prompt)
        }
    }
    val generateFromDiary: (Long) -> Unit = { currentDiaryId ->
        if (sharedViewModel != null) sharedViewModel.generateFromDiary(currentDiaryId) else viewModel.generateFromDiary(currentDiaryId)
    }
    val clearError: () -> Unit = {
        if (sharedViewModel != null) sharedViewModel.clearError() else viewModel.clearError()
    }
    var selectedSketchTab by remember { mutableIntStateOf(0) }
    var uploadedSketchUri by remember { mutableStateOf<Uri?>(null) }
    var canvasBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val sketchPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uploadedSketchUri = uri
    }

    LaunchedEffect(diaryId) {
        if (diaryId != null) {
            setMode(DrawingMode.DIARY_TO_IMAGE)
        }
    }

    LaunchedEffect(initialPrompt) {
        val prompt = initialPrompt?.trim().orEmpty()
        if (prompt.isNotBlank() && uiState.prompt.isBlank()) {
            setMode(DrawingMode.TEXT_TO_IMAGE)
            setPrompt(prompt)
        }
    }

    LaunchedEffect(uiState.generatedImageUrl) {
        uiState.generatedImageUrl?.let { url ->
            // 如果 sharedViewModel 记录该 URL 已自动跳转过，则不再触发导航
            if (sharedViewModel != null) {
                if (!sharedViewModel.shouldAutoNavigateToDrawingResult(url)) {
                    return@LaunchedEffect
                }
            }
            onNavigateToResult(url, diaryId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("绘画", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
                            MintGreen.copy(alpha = 0.2f),
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
                ModeSelector(
                    selectedMode = uiState.mode,
                    onModeSelected = setMode,
                )

                when (uiState.mode) {
                    DrawingMode.TEXT_TO_IMAGE -> TextToImageContent(
                        prompt = uiState.prompt,
                        onPromptChange = setPrompt,
                        isGenerating = uiState.isGenerating,
                        onGenerate = generateFromText,
                    )

                    DrawingMode.SKETCH_ENHANCE -> SketchEnhanceContent(
                        prompt = uiState.prompt,
                        onPromptChange = setPrompt,
                        selectedTab = selectedSketchTab,
                        onSelectedTabChange = { selectedSketchTab = it },
                        uploadedSketchUri = uploadedSketchUri,
                        onSelectUpload = { sketchPickerLauncher.launch("image/*") },
                        onClearUpload = { uploadedSketchUri = null },
                        onCanvasBitmapChange = {
                            canvasBitmap = it
                            setSketchBitmap(it)
                        },
                        isGenerating = uiState.isGenerating,
                        canEnhance = if (selectedSketchTab == 0) {
                            canvasBitmap != null
                        } else {
                            uploadedSketchUri != null
                        },
                        onEnhance = {
                            val imageDataUrl = if (selectedSketchTab == 0) {
                                canvasBitmap?.toDataUrl()
                            } else {
                                uploadedSketchUri?.toDataUrl(context)
                            }

                            if (imageDataUrl == null) {
                                showError(
                                    if (selectedSketchTab == 0) "请先在画板上画一笔" else "请先选择要上传的图片",
                                )
                            } else {
                                enhanceSketch(imageDataUrl, uiState.prompt)
                            }
                        },
                    )

                    DrawingMode.DIARY_TO_IMAGE -> DiaryToImageContent(
                        diaryId = diaryId,
                        style = uiState.style,
                        onStyleChange = setStyle,
                        isGenerating = uiState.isGenerating,
                        onGenerate = { diaryId?.let(generateFromDiary) },
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            AnimatedVisibility(
                visible = uiState.isGenerating,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = Primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "AI 正在创作中…",
                                fontSize = 16.sp,
                                color = TextPrimary,
                            )
                            Text(
                                text = "预计需要 10-30 秒",
                                fontSize = 12.sp,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = clearError) {
                            Text("关闭")
                        }
                    },
                ) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: DrawingMode,
    onModeSelected: (DrawingMode) -> Unit,
) {
    val modes = listOf(
        Triple(DrawingMode.TEXT_TO_IMAGE, Icons.Outlined.Edit, "文字描述"),
        Triple(DrawingMode.SKETCH_ENHANCE, Icons.Outlined.Brush, "简笔画"),
        Triple(DrawingMode.DIARY_TO_IMAGE, Icons.Outlined.MenuBook, "从日记"),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        modes.forEach { (mode, icon, label) ->
            val isSelected = selectedMode == mode

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onModeSelected(mode) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) Primary else Surface,
                shadowElevation = if (isSelected) 4.dp else 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) Color.White else TextSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextToImageContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "描述你想画的内容",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            placeholder = {
                Text(
                    "例如：一只可爱的小猫在阳光下打盹，水彩风格，温暖安静",
                    color = TextTertiary,
                )
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = DividerColor,
            ),
        )

        Text(
            text = "试试这些提示词：",
            fontSize = 14.sp,
            color = TextSecondary,
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val suggestions = listOf("可爱的小动物", "梦幻森林", "治愈系风景", "温馨的房间", "星空夜晚")
            items(suggestions) { suggestion ->
                SuggestionChip(
                    onClick = { onPromptChange(suggestion) },
                    label = { Text(suggestion) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MintGreen.copy(alpha = 0.2f),
                        labelColor = Primary,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onGenerate,
            enabled = prompt.isNotBlank() && !isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("开始创作", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SketchEnhanceContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    uploadedSketchUri: Uri?,
    onSelectUpload: () -> Unit,
    onClearUpload: () -> Unit,
    onCanvasBitmapChange: (Bitmap?) -> Unit,
    isGenerating: Boolean,
    canEnhance: Boolean,
    onEnhance: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { onSelectedTabChange(0) },
                label = { Text("画板绘制") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Brush,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White,
                ),
            )

            FilterChip(
                selected = selectedTab == 1,
                onClick = { onSelectedTabChange(1) },
                label = { Text("上传图片") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White,
                ),
            )
        }

        if (selectedTab == 0) {
            Text(
                text = "在画板上画出你的草图",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )

            DrawingCanvas(
                modifier = Modifier.fillMaxWidth(),
                onBitmapReady = onCanvasBitmapChange,
            )
        } else {
            Text(
                text = "上传你的简笔画或草图",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable(onClick = onSelectUpload),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                if (uploadedSketchUri != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = uploadedSketchUri,
                            contentDescription = "uploaded_sketch",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )

                        TextButton(
                            onClick = onClearUpload,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        ) {
                            Text("清除")
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.CloudUpload,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(64.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击选择图片",
                                color = TextSecondary,
                            )
                            Text(
                                text = "支持简笔画、草图、线稿",
                                fontSize = 12.sp,
                                color = TextTertiary,
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("增强描述（可选）") },
            placeholder = { Text("例如：暖色水彩、童话感、柔和光影") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = DividerColor,
            ),
        )

        Button(
            onClick = onEnhance,
            enabled = canEnhance && !isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoFixHigh,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("增强画作", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiaryToImageContent(
    diaryId: Long?,
    style: DrawingStyle,
    onStyleChange: (DrawingStyle) -> Unit,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (diaryId != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MintGreen.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = Primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "将根据日记内容自动生成画作",
                        color = TextPrimary,
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "请从日记编辑页进入这个功能",
                        color = Color(0xFFE65100),
                    )
                }
            }
        }

        Text(
            text = "选择画作风格",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DrawingStyle.entries.forEach { currentStyle ->
                StyleCard(
                    style = currentStyle,
                    isSelected = style == currentStyle,
                    onClick = { onStyleChange(currentStyle) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onGenerate,
            enabled = diaryId != null && !isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("生成画作", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StyleCard(
    style: DrawingStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emoji = when (style) {
        DrawingStyle.WATERCOLOR -> "🎨"
        DrawingStyle.OIL -> "🖼️"
        DrawingStyle.CARTOON -> "🌈"
        DrawingStyle.REALISTIC -> "📷"
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) Primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        color = if (isSelected) Primary.copy(alpha = 0.1f) else Surface,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = style.displayName,
                fontSize = 12.sp,
                color = if (isSelected) Primary else TextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

private fun Bitmap.toDataUrl(): String? {
    return runCatching {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, output)
        "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}

private fun Uri.toDataUrl(context: Context): String? {
    return runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(this) ?: "image/jpeg"
        val rawBytes = resolver.openInputStream(this)?.use { it.readBytes() }
            ?: error("Failed to open selected image")
        "data:$mimeType;base64," + Base64.encodeToString(rawBytes, Base64.NO_WRAP)
    }.getOrNull()
}
