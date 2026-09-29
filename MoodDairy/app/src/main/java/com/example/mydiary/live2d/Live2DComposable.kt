package com.example.mydiary.live2d

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import kotlinx.coroutines.*

/**
 * Live2D Compose 组件 - 优化版本
 * 修复：避免重复加载和竞争条件
 */
@Composable
fun Live2DComposable(
    modelPath: String = Live2DConfig.defaultModel.path,
    modifier: Modifier = Modifier,
    alpha: Float = 1.0f,
    onModelLoaded: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    onModelChanged: (String) -> Unit = {},
    onTouch: ((Float, Float) -> Unit)? = null
) {
    // 状态管理
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }  // 同时用于 UI 显示和防止重复加载
    var displayText by remember { mutableStateOf<String?>(null) }
    var textVisible by remember { mutableStateOf(false) }
    var live2DView by remember { mutableStateOf<Live2DView?>(null) }
    
    // 防止重复加载的标志
    var lastLoadedPath by remember { mutableStateOf<String?>(null) }

    // 协程作用域用于管理异步操作
    val scope = rememberCoroutineScope()

    // 监听文本显示，自动隐藏
    LaunchedEffect(displayText) {
        if (displayText != null) {
            textVisible = true
            delay(3000)
            textVisible = false
            delay(300)
            displayText = null
        }
    }

    // 异步加载模型（带防重复加载保护）
    fun loadModelAsync(view: Live2DView, path: String, forceReload: Boolean = false) {
        // 检查是否需要加载（isLoading 同时用于 UI 显示和防止重复加载）
        if (!forceReload && (isLoading || lastLoadedPath == path)) {
            Log.d("Live2DComposable", "跳过重复加载: $path (isLoading=$isLoading, lastLoaded=$lastLoadedPath)")
            return
        }
        
        scope.launch(Dispatchers.Main) {
            try {
                isLoading = true
                loadError = null

                Log.d("Live2DComposable", "开始加载模型: $path")
                
                // 在主线程直接调用，让 Live2DView 内部处理 OpenGL 线程调度
                try {
                    view.loadModel(path)
                    lastLoadedPath = path
                    Log.d("Live2DComposable", "模型加载请求已提交: $path")
                    onModelLoaded?.invoke()
                } catch (e: Exception) {
                    Log.e("Live2DComposable", "加载模型失败: $path", e)
                    val errorMessage = "加载模型失败: $path"
                    loadError = errorMessage
                    onError?.invoke(errorMessage)
                }
            } catch (e: Exception) {
                val errorMessage = "加载失败: ${e.localizedMessage ?: "未知错误"}"
                loadError = errorMessage
                onError?.invoke(errorMessage)
                Log.e("Live2DComposable", "异步加载模型异常", e)
            } finally {
                isLoading = false
            }
        }
    }

    // 模型路径变化时加载（带防抖，但跳过内部切换导致的变化）
    LaunchedEffect(modelPath) {
        live2DView?.let { view ->
            // 如果是内部切换导致的路径变化，不需要重新加载
            if (lastLoadedPath == modelPath) {
                Log.d("Live2DComposable", "路径变化但已加载，跳过: $modelPath")
                return@LaunchedEffect
            }
            
            val currentModel = view.manager.currentModel
            if (currentModel != modelPath) {
                Log.d("Live2DComposable", "模型路径变化: $currentModel -> $modelPath")
                // 添加小延迟避免频繁切换
                delay(100)
                loadModelAsync(view, modelPath)
            }
        }
    }

    // 切页返回后延迟重试加载（Surface 可能晚于 factory 就绪，导致首次未显示）
    LaunchedEffect(Unit) {
        delay(400)
        live2DView?.let { view ->
            // 只有当模型确实未加载时才重试
            if (view.manager.currentModel.isNullOrBlank() && lastLoadedPath != modelPath) {
                Log.d("Live2DComposable", "延迟重试加载模型: $modelPath")
                loadModelAsync(view, modelPath, forceReload = true)
            }
        }
    }

    // 组件卸载时清理资源
    DisposableEffect(Unit) {
        onDispose {
            live2DView?.let { view ->
                Log.d("Live2DComposable", "清理 Live2D 资源")
                view.release()
                lastLoadedPath = null
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                Live2DView(ctx).apply {
                    live2DView = this

                    manager.onTextDisplay = { text ->
                        scope.launch(Dispatchers.Main.immediate) {
                            displayText = text
                            textVisible = true
                        }
                    }

                    // 双击切换角色后，在主线程回调新模型路径以同步 UI 状态
                    onModelSwitched = { newPath ->
                        // 更新 lastLoadedPath 以避免重复加载
                        lastLoadedPath = newPath
                        onModelChanged(newPath)
                    }

                    loadModelAsync(this, modelPath)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                live2DView = view
                view.setRenderAlpha(alpha)
            }
        )

        // 加载指示器
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 动作播放时的文字气泡（显示在左上角）
        if (textVisible && displayText != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 40.dp, start = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xE6000000)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = displayText!!,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .widthIn(max = 200.dp),
                    color = Color.White,
                    fontSize = 15.sp
                )
            }
        }

        // 错误信息显示
        loadError?.let { error ->
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xDDFF4444)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = error,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            live2DView?.let { view ->
                                loadModelAsync(view, modelPath)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Red
                        )
                    ) {
                        Text("重试")
                    }
                }
            }
        }
    }
}
