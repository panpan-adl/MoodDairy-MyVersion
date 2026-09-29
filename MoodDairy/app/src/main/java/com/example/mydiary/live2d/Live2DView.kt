package com.example.mydiary.live2d

import android.graphics.Color
import android.graphics.PixelFormat
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.math.CubismModelMatrix
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D 渲染视图
 */
class Live2DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    
    companion object {
        private const val TAG = "Live2DView"
        private const val DOUBLE_TAP_TIMEOUT_MS = 350L
        private const val DOUBLE_TAP_SLOP_DP = 100f
    }
    
    private val live2DManager = Live2DManager(context)
    private var renderer: Live2DRenderer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /** 双击切换模型后在主线程回调新模型路径 */
    var onModelSwitched: ((String) -> Unit)? = null
    
    /** 渲染透明度（用于淡入淡出效果）*/
    @Volatile
    private var renderAlpha: Float = 1.0f
    
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val doubleTapSlopPx = DOUBLE_TAP_SLOP_DP * context.resources.displayMetrics.density
    private var hasBeenVisible = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            // 恢复渲染
            renderer?.isRenderingEnabled = true
            if (hasBeenVisible) {
                val path = live2DManager.currentModel
                if (!path.isNullOrBlank()) {
                    Log.d(TAG, "窗口再次可见，刷新模型: $path")
                    queueEvent {
                        live2DManager.loadModel(path)
                        performModelRendering("onWindowVisibilityChanged")
                    }
                }
            } else {
                hasBeenVisible = true
            }
        } else {
            // 窗口不可见时立即停止渲染（让页面退出动画更自然）
            renderer?.isRenderingEnabled = false
        }
    }
    
    override fun onDetachedFromWindow() {
        // 分离时立即停止渲染
        renderer?.isRenderingEnabled = false
        super.onDetachedFromWindow()
    }

    init {
        setEGLContextClientVersion(2)
        // 与官方 TranslucentGLSurfaceView 一致：RGBA 8+8+8+8，depth 16，stencil 0
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setBackgroundColor(Color.TRANSPARENT)
        // 多数设备上透明必须设为 true，Surface 叠在窗口之上，清 (0,0,0,0) 才能透出下层
        setZOrderOnTop(true)
        renderer = Live2DRenderer(live2DManager) { onSurfaceRecreated() }
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 切页返回后重设透明与置顶，避免人物消失、黑底
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setBackgroundColor(Color.TRANSPARENT)
        // 延迟在 GL 线程重载当前模型（Surface 就绪后再执行，解决切回后人物不显示）
        // 捕获当前路径，用于延迟后的一致性检查
        val pathAtAttach = live2DManager.currentModel
        mainHandler.postDelayed({
            val currentPath = live2DManager.currentModel
            // 仅当路径未改变时才重载，避免覆盖用户切换的模型
            if (!currentPath.isNullOrBlank() && currentPath == pathAtAttach) {
                Log.d(TAG, "onAttachedToWindow 延迟重载模型: $currentPath")
                queueEvent {
                    live2DManager.loadModel(currentPath)
                    performModelRendering("onAttachedToWindow")
                }
            } else if (currentPath != pathAtAttach) {
                Log.d(TAG, "onAttachedToWindow 跳过重载，模型已切换: $pathAtAttach -> $currentPath")
            }
        }, 250)
    }
    
    /**
     * Surface 被重新创建时调用（如切页返回后），重新加载当前模型以恢复显示
     */
    private fun onSurfaceRecreated() {
        val path = live2DManager.currentModel
        if (!path.isNullOrBlank()) {
            Log.d(TAG, "Surface 重新创建，刷新模型: $path")
            queueEvent {
                live2DManager.loadModel(path)
                performModelRendering("onSurfaceRecreated")
            }
        }
    }

    /**
     * 执行模型渲染，应在 OpenGL 线程中调用（通常在 queueEvent 中）
     */
    private fun performModelRendering(actionName: String) {
        Log.d(TAG, "执行模型渲染: $actionName")
        
        val r = renderer
        if (r == null || !r.isContextReady) {
            Log.d(TAG, "$actionName: OpenGL 上下文未就绪，延迟纹理加载")
            r?.requestTextureLoad()
            return
        }
        
        // 清除缓冲区（通过渲染器）
        r.clearBuffers()
        live2DManager.loadAndBindTextures()

        if (r.viewWidth > 0 && r.viewHeight > 0) {
            Log.d(TAG, "$actionName 后更新投影矩阵: ${r.viewWidth}x${r.viewHeight}")
            r.updateProjectionMatrix(r.viewWidth, r.viewHeight)
        }
        
        Log.d(TAG, "$actionName 渲染完成，当前模型: ${live2DManager.currentModel}")
    }
    
    /**
     * 加载模型
     */
    fun loadModel(modelPath: String) {
        Log.d(TAG, "loadModel 被调用: $modelPath, 当前模型: ${live2DManager.currentModel}")
        queueEvent {
            Log.d(TAG, "开始加载模型（OpenGL 线程）: $modelPath")

            val success = live2DManager.loadModel(modelPath)
            if (success) {
                Log.d(TAG, "模型加载成功: $modelPath")
                performModelRendering("加载模型")
            } else {
                Log.e(TAG, "模型加载失败: $modelPath")
            }
        }
    }
    
    /**
     * 切换为下一个模型，完成后在主线程回调新模型路径
     */
    fun switchModel(onSwitched: ((String) -> Unit)? = null) {
        Log.d(TAG, "switchModel 被调用，当前模型: ${live2DManager.currentModel}")
        queueEvent {
            Log.d(TAG, "开始切换模型（OpenGL 线程），当前模型: ${live2DManager.currentModel}")
            live2DManager.switchModel()
            val newPath = live2DManager.currentModel
            Log.d(TAG, "切换模型完成，新模型: $newPath")
            performModelRendering("切换模型")
            if (newPath != null) {
                mainHandler.post {
                    onSwitched?.invoke(newPath)
                    this@Live2DView.onModelSwitched?.invoke(newPath)
                }
            }
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 先捕获事件数据，避免在 queueEvent 中访问可能被回收的 event 对象
        val action = event.action
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                val viewWidth = renderer?.viewWidth ?: this.width
                val viewHeight = renderer?.viewHeight ?: this.height
                val isActionDown = (action == MotionEvent.ACTION_DOWN)
                
                if (isActionDown) {
                    val now = System.currentTimeMillis()
                    val isDoubleTap = (now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS) &&
                        (kotlin.math.abs(x - lastTapX) <= doubleTapSlopPx) &&
                        (kotlin.math.abs(y - lastTapY) <= doubleTapSlopPx)
                    lastTapTime = now
                    lastTapX = x
                    lastTapY = y
                    if (isDoubleTap) {
                        Log.d(TAG, "检测到双击，切换角色")
                        switchModel()
                        return true
                    }
                }
                
                queueEvent {
                    val normalizedX = (x / viewWidth) * 2.0f - 1.0f
                    val normalizedY = 1.0f - (y / viewHeight) * 2.0f
                    live2DManager.updateEyeBallTracking(normalizedX, normalizedY)
                    if (isActionDown) {
                        Log.d(TAG, "onTouchEvent: ACTION_DOWN at ($x, $y), viewSize: ${viewWidth}x${viewHeight}")
                        live2DManager.handleTouch(x, y, viewWidth, viewHeight)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                queueEvent {
                    live2DManager.updateEyeBallTracking(0.0f, 0.0f)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    /**
     * 释放所有资源
     */
    /**
     * 设置渲染透明度（用于淡出动画）
     * @param alpha 透明度值 0.0-1.0
     */
    fun setRenderAlpha(alpha: Float) {
        renderAlpha = alpha.coerceIn(0f, 1f)
        renderer?.renderAlpha = renderAlpha
    }
    
    /**
     * 获取当前渲染透明度
     */
    fun getRenderAlpha(): Float = renderAlpha
    
    fun release() {
        // 立即禁用渲染，使视图变透明（不等待 queueEvent 执行）
        renderer?.isRenderingEnabled = false
        queueEvent {
            live2DManager.release()
        }
    }
    
    // 暴露 manager 供外部访问（用于更新）
    val manager: Live2DManager
        get() = live2DManager
    
    // 暴露 renderer 供外部访问（用于更新投影矩阵）
    fun getRenderer(): Live2DRenderer? = renderer
}

/**
 * Live2D 渲染器
 * @param onSurfaceRecreated 当 Surface 非首次创建时回调（切页返回后 GL 上下文重建，需重载模型）
 */
class Live2DRenderer(
    private val live2DManager: Live2DManager,
    private val onSurfaceRecreated: (() -> Unit)? = null
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "Live2DRenderer"
    }

    private var surfaceCreatedCount = 0
    private var previousTime = System.nanoTime()
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    private var frameCount = 0
    private var projectionMatrix: CubismMatrix44? = null
    
    @Volatile
    var isContextReady = false
        private set
    
    @Volatile
    private var pendingTextureLoad = false
    
    /**
     * 控制是否渲染模型
     * 当页面退出时设为 false，使 GLSurfaceView 立即透明，与页面退出动画同步
     */
    @Volatile
    var isRenderingEnabled = true
    
    /**
     * 渲染透明度（0.0-1.0），用于淡入淡出效果
     */
    @Volatile
    var renderAlpha: Float = 1.0f
    
    /**
     * 清除 OpenGL 缓冲区
     * 注意：此方法应在 OpenGL 线程中调用
     */
    fun clearBuffers() {
        GLES20.glClearColor(0f, 0f, 0f, 0f) // 透明，使页面背景透出
        GLES20.glClearDepthf(1.0f)
        GLES20.glDepthMask(true)
        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT or
            GLES20.GL_DEPTH_BUFFER_BIT or
            GLES20.GL_STENCIL_BUFFER_BIT
        )
    }
    
    override fun onSurfaceCreated(gl: GL10?, @Suppress("UNUSED_PARAMETER") config: EGLConfig?) {
        surfaceCreatedCount++
        val isRecreate = surfaceCreatedCount > 1
        Log.d(TAG, "Surface 创建 - OpenGL ES 2.0 上下文已创建 (第 ${surfaceCreatedCount} 次, isRecreate=$isRecreate)")
        
        // 标记 OpenGL 上下文已准备好
        isContextReady = true

        if (isRecreate) {
            onSurfaceRecreated?.invoke()
        }

        // 初始化 OpenGL 状态（每次 Surface 创建都要执行）
        
        // 启用混合模式（用于透明度）
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(
            GLES20.GL_ONE,
            GLES20.GL_ONE_MINUS_SRC_ALPHA
        )

        // 启用深度测试（防止部件穿插重叠）
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)

        // 设置清除颜色和深度（透明背景，使页面渐变透出）
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClearDepthf(1.0f)
        
        // 初始化 Live2D SDK
        live2DManager.initializeSDK()
        
        // 标记 OpenGL 上下文已就绪（必须在 initializeSDK 之后）
        live2DManager.onGLContextReady()

        // 处理待处理的纹理加载或已加载模型的纹理绑定
        if (live2DManager.getModel() != null && live2DManager.getModelData() != null) {
            Log.d(TAG, "OpenGL 上下文就绪，加载纹理: ${live2DManager.currentModel}")
            // 确保渲染器已创建（可能在上下文就绪前加载的模型）
            live2DManager.ensureRendererCreated()
            clearBuffers()
            live2DManager.loadAndBindTextures()
            pendingTextureLoad = false
            if (viewWidth > 0 && viewHeight > 0) {
                Log.d(TAG, "onSurfaceCreated 中更新投影矩阵: ${viewWidth}x${viewHeight}")
                updateProjectionMatrix(viewWidth, viewHeight)
            }
        }
    }
    
    fun requestTextureLoad() {
        pendingTextureLoad = true
    }
    
    override fun onSurfaceChanged(@Suppress("UNUSED_PARAMETER") gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "Surface 尺寸变化: ${width}x${height}")
        
        viewWidth = width
        viewHeight = height
        
        // 设置视口
        GLES20.glViewport(0, 0, width, height)
        Log.d(TAG, "视口已设置: 0, 0, $width, $height")
        
        // 更新投影矩阵以适配新视口尺寸
        try {
            // 更新投影矩阵（只有在视图尺寸有效时）
            if (width > 0 && height > 0) {
                updateProjectionMatrix(width, height)
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置投影矩阵失败", e)
        }
    }
    
    /**
     * 更新投影矩阵和模型矩阵
     * 根据视口宽高比和模型尺寸计算变换矩阵
     */
    fun updateProjectionMatrix(width: Int, height: Int) {
        try {
            val model = live2DManager.getModel() ?: return
            val modelMatrix = live2DManager.getModelMatrix() ?: return
            
            // 创建投影矩阵
            projectionMatrix = CubismMatrix44.create()
            projectionMatrix?.loadIdentity()
            
            // 根据视口宽高比和模型尺寸计算缩放
            val canvasWidth = model.getCanvasWidth()
            val canvasHeight = model.getCanvasHeight()
            
            // 设置模型矩阵和投影矩阵
            
            val targetScale = 0.8f // 模型大小约为屏幕的一半
            modelMatrix.loadIdentity()
            // 注意：SDK 示例中，这种情况下不设置模型矩阵的高度，而是直接缩放投影矩阵
            // 但为了控制模型大小，我们仍然设置高度
            modelMatrix.setHeight(2.0f * targetScale) // 设置为 2.0（屏幕高度的一半）
            // 投影矩阵：保持宽高比（按照 SDK 示例）
            projectionMatrix?.scale(height.toFloat() / width.toFloat(), 1.0f)
            
            
            // 确保模型居中
            modelMatrix.setCenterPosition(0.0f, 0.0f)
            modelMatrix.translateX(modelMatrix.getTranslateX() + 0.8f)
            modelMatrix.translateY(modelMatrix.getTranslateY() + 0.6f)
            Log.d(TAG, "投影矩阵已更新: 视口=${width}x${height}, 画布=${canvasWidth}x${canvasHeight}")
        } catch (e: Exception) {
            Log.w(TAG, "设置投影矩阵失败", e)
        }
    }
    
    override fun onDrawFrame(@Suppress("UNUSED_PARAMETER") gl: GL10?) {
        // 如果渲染被禁用（页面退出时），只清除为透明
        if (!isRenderingEnabled) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }
        
        // 计算时间差
        val now = System.nanoTime()
        var deltaTime = (now - previousTime) / 1_000_000_000f // 转换为秒
        previousTime = now
        
        // 限制 deltaTime 的最大值，避免异常大的时间差导致动画跳跃
        if (deltaTime > 0.1f) {
            deltaTime = 0.1f
        }
        
        frameCount++
        
        // 处理待处理的纹理加载
        if (pendingTextureLoad && isContextReady) {
            val model = live2DManager.getModel()
            val modelData = live2DManager.getModelData()
            if (model != null && modelData != null) {
                Log.d(TAG, "处理待处理的纹理加载: ${live2DManager.currentModel}")
                live2DManager.ensureRendererCreated()
                live2DManager.loadAndBindTextures()
                if (viewWidth > 0 && viewHeight > 0) {
                    updateProjectionMatrix(viewWidth, viewHeight)
                }
            }
            pendingTextureLoad = false
        }
        
        // 清除缓冲区，防止叠影
        clearBuffers()
        
        // 更新和绘制模型
        try {
            val model = live2DManager.getModel()
            val modelData = live2DManager.getModelData()
            
            if (model != null && modelData != null && modelData.isLoaded) {
                updateModel(deltaTime)
                drawModel()
            } else {
                drawTestShape()
            }
        } catch (e: Exception) {
            if (frameCount % 60 == 0) {
                Log.e(TAG, "渲染时出错", e)
                e.printStackTrace()
            }
            // 出错时也清除缓冲区（透明）
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClearDepthf(1.0f)
            GLES20.glDepthMask(true)
            GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT or 
                GLES20.GL_DEPTH_BUFFER_BIT
            )
        }
    }
    
    /**
     * 更新模型
     */
    private fun updateModel(deltaTime: Float) {
        try {
            val model = live2DManager.getModel() ?: return
            
            // 1. 加载参数（从上次保存的状态恢复）
            // loadParameters 会恢复上次保存的默认状态
            model.loadParameters()
            
            // 初始化眼珠参数
            live2DManager.initializeEyeBallParametersAfterLoad(model)
            
            // 2. 更新动作（必须在 loadParameters 之后）
            // 更新动作
            val isMotionUpdated = live2DManager.updateMotions(deltaTime)
            
            // 3. 保存参数（用于下次更新）
            model.saveParameters()
            
            // 4. 更新物理、眨眼、呼吸、表情
            live2DManager.updateEffects(deltaTime, isMotionUpdated)
            
            // 5. 应用所有参数变化到模型
            model.update()
        } catch (e: Exception) {
            if (frameCount % 60 == 0) {
                Log.w(TAG, "更新模型失败", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 绘制模型
     */
    private fun drawModel() {
        try {
            val model = live2DManager.getModel() ?: run {
                Log.w(TAG, "模型对象为空，无法绘制")
                return
            }
            
            val renderer = live2DManager.getRenderer() ?: run {
                Log.w(TAG, "Renderer 为空，无法绘制")
                return
            }
            
            // 设置 OpenGL 状态
            // 启用深度测试
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glDepthMask(true)
            GLES20.glDepthRangef(0.0f, 1.0f)
            
            // 设置混合模式
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(
                GLES20.GL_ONE,
                GLES20.GL_ONE_MINUS_SRC_ALPHA
            )
            
            // 禁用面剔除（Live2D 模型需要双面渲染）
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            
            // 获取投影矩阵和模型矩阵
            val projection = projectionMatrix ?: run {
                Log.w(TAG, "投影矩阵未初始化，使用默认值")
                val proj = CubismMatrix44.create()
                proj.loadIdentity()
                proj.scale(viewHeight.toFloat() / viewWidth.toFloat(), 1.0f)
                proj
            }
            
            val modelMatrix = live2DManager.getModelMatrix() ?: run {
                Log.w(TAG, "模型矩阵未初始化")
                return
            }
            
            // 计算 MVP 矩阵（与官方 Sample 一致：modelMatrix * projection）
            val mvpMatrix = CubismMatrix44.create()
            CubismMatrix44.multiply(
                modelMatrix.getArray(),
                projection.getArray(),
                mvpMatrix.getArray()
            )
            
            // 设置 MVP 矩阵到渲染器
            renderer.setMvpMatrix(mvpMatrix)
            
            // 应用渲染透明度（用于淡入淡出效果）
            renderer.setModelColor(1.0f, 1.0f, 1.0f, renderAlpha)

            renderer.drawModel()
            
        } catch (e: Exception) {
            Log.e(TAG, "绘制模型时出错", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 模型未加载时清除为透明背景（让页面背景透出）
     */
    private fun drawTestShape() {
        try {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClearDepthf(1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        } catch (e: Exception) {
            Log.e(TAG, "清除缓冲区失败", e)
        }
    }
}
