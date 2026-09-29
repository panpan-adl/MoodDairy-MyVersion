package com.example.mydiary.live2d

import android.content.Context
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismModelMatrix
import com.live2d.sdk.cubism.framework.model.CubismMoc
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotionManager
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotionManager
import com.live2d.sdk.cubism.framework.physics.CubismPhysics
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class Live2DException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InitializationException(message: String, cause: Throwable? = null) : Live2DException(message, cause)
    class FileReadException(message: String, cause: Throwable? = null) : Live2DException(message, cause)
    class ResourceReleaseException(message: String, cause: Throwable? = null) : Live2DException(message, cause)
    class ConfigurationException(message: String, cause: Throwable? = null) : Live2DException(message, cause)
}

class Live2DManager(private val context: Context) {
    
    companion object {
        private const val TAG = "Live2DManager"
    }
    
    var currentModel: String? = null
        private set
    
    var onTextDisplay: ((String) -> Unit)? = null
    
    private var modelData: Live2DModelData? = null
    private var cubismMoc: CubismMoc? = null
    private var cubismModel: CubismModel? = null
    private var cubismRenderer: CubismRenderer? = null
    private var textureManager: TextureManager? = null
    private var modelMatrix: CubismModelMatrix? = null
    
    private var motionManager: CubismMotionManager? = null
    private val motions = mutableMapOf<String, CubismMotion>()
    
    private var expressionManager: CubismExpressionMotionManager? = null
    private val expressions = mutableMapOf<String, CubismExpressionMotion>()
    
    private var physics: CubismPhysics? = null
    private var eyeBlink: CubismEyeBlink? = null
    private var breath: CubismBreath? = null
    
    private var idParamEyeBallX: CubismId? = null
    private var idParamEyeBallY: CubismId? = null
    private var idParamEyeBallForm: CubismId? = null
    private var eyeBallParametersInitialized: Boolean = false
    private var eyeBallTrackingX: Float = 0.0f
    private var eyeBallTrackingY: Float = 0.0f
    
    private var modelSetting: CubismModelSettingJson? = null
    private var isSDKInitialized = false
    @Volatile
    var isGLContextReady = false
        private set
    private var defaultPartOpacities: Map<String, Float> = emptyMap()
    private val modelLoadLock = ReentrantLock()
    private var touchActionList: List<TouchAction> = emptyList()
    private var pendingPartOpacityChanges: Map<String, Float>? = null
    private val runningPartAnimators = ConcurrentHashMap<String, ValueAnimator>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class PartOpacityAnimation(
        val partId: String,
        val startOpacity: Float,
        val targetOpacity: Float,
        val duration: Float,
        var elapsed: Float = 0f
    )
    private val partOpacityAnimations = ConcurrentHashMap<String, PartOpacityAnimation>()
    
    fun initializeSDK() {
        
        try {
            if (CubismFramework.isStarted()) {
                try {
                    CubismFramework.dispose()
                } catch (_: Exception) {}
            }
            CubismFramework.startUp(CubismFramework.Option())
            CubismFramework.initialize()
            isSDKInitialized = true
            
            if (textureManager == null) {
                textureManager = TextureManager(context)
            }
            Log.d(TAG, "Live2D SDK 初始化完成")
        } catch (e: UnsatisfiedLinkError) {
            throw Live2DException.InitializationException("Live2D SDK 库无法加载", e)
        } catch (e: Exception) {
            throw Live2DException.InitializationException("Live2D SDK 初始化失败", e)
        }
    }

    fun onGLContextReady() {
        isGLContextReady = true
        Log.d(TAG, "OpenGL 上下文已就绪")
    }
    fun onGLContextLost() {
        isGLContextReady = false
        cubismRenderer = null
    }
    fun loadModel(modelPath: String): Boolean {
        if (modelPath.isBlank()) {
            throw Live2DException.ConfigurationException("模型路径不能为空")
        }

        return modelLoadLock.withLock {
            try {
                if (currentModel == modelPath && modelData?.isLoaded == true) {
                    return true
                }

                releaseCurrentModel()
                forceCleanupResources()
                if (!isSDKInitialized) initializeSDK()

                val jsonContent = Live2DHelper.readAssetFile(context, modelPath)
                    ?: throw Live2DException.FileReadException("无法读取模型配置文件: $modelPath")
                val config = Live2DHelper.parseModel3Json(jsonContent)
                    ?: throw Live2DException.ConfigurationException("解析模型配置失败: $modelPath")
                
                modelSetting = CubismModelSettingJson(readAssetBytes(modelPath))
                val modelDir = Live2DHelper.getModelDirectory(modelPath)

                modelData = Live2DModelData(modelPath = modelPath, modelDir = modelDir, config = config)
                val mocPath = Live2DHelper.buildAssetPath(modelDir, config.mocFile)
                currentModel = modelPath

                try {
                    loadModelWithSDK(readAssetBytes(mocPath), config, modelDir)
                } catch (e: Exception) {
                    Log.w(TAG, "SDK加载失败", e)
                    modelData = modelData?.copy(isLoaded = true)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "加载模型失败: $modelPath", e)
                throw e
            }
        }
    }

    private fun forceCleanupResources() {
        try { cubismModel?.let { cubismMoc?.deleteModel(it); it.close() } } catch (_: Exception) {}
        cubismModel = null
        try { cubismMoc?.delete() } catch (_: Exception) {}
        cubismMoc = null
        try { cubismRenderer?.close() } catch (_: Exception) {}
        cubismRenderer = null
    }

    private fun resetModelState() {
        wasMotionPlaying = false
        lastMotionFinished = true
        pendingPartOpacityChanges = null
        motionFinishedTime = -1f
        parameterFadeTime = -1f
        currentMotionGroup = "Idle"
        isWelcomeActionPlaying = false
        eyeBallParametersInitialized = false
        eyeBallTrackingX = 0.0f
        eyeBallTrackingY = 0.0f
        touchActionList = emptyList()
        defaultPartOpacities = emptyMap()
        partOpacityAnimations.clear()
        idParamEyeBallX = null
        idParamEyeBallY = null
        idParamEyeBallForm = null
    }
    
    private fun loadModelWithSDK(mocBytes: ByteArray, config: ModelConfig, modelDir: String) {
        cubismMoc = CubismMoc.create(mocBytes) ?: throw Exception("创建 CubismMoc 失败")
        cubismModel = cubismMoc?.createModel() ?: throw Exception("创建 CubismModel 失败")
        
        val model = cubismModel!!
        modelMatrix = CubismModelMatrix.create(model.getCanvasWidth(), model.getCanvasHeight()).apply {
            loadIdentity()
            setCenterPosition(0.0f, 0.0f)
        }
        
        // 只在 OpenGL 上下文就绪时创建渲染器
        if (isGLContextReady) {
            createRendererForModel(model)
        } else {
            Log.d(TAG, "OpenGL 上下文未就绪，延迟渲染器创建")
        }
        
        motionManager = CubismMotionManager()
        loadMotions(config, modelDir)
        loadPhysics(config, modelDir)
        loadExpressions(config, modelDir)
        initializeEyeBlinkAndBreath()
        initializeEyeBallParameters()
        initializeTouchActionList()

        if (currentModel == Live2DConfig.avaModelPath) {
            initializeAvaHideParts(model)
        }

        // 保存默认状态
        try {
            if (idParamEyeBallX != null && idParamEyeBallY != null) {
                val xIdx = model.getParameterIndex(idParamEyeBallX!!)
                val yIdx = model.getParameterIndex(idParamEyeBallY!!)
                if (xIdx >= 0) model.setParameterValue(xIdx, 0.0f)
                if (yIdx >= 0) model.setParameterValue(yIdx, 0.0f)
            }
            model.saveParameters()
            saveDefaultPartOpacities(model)
        } catch (_: Exception) {}

        // 播放欢迎动作
        if (motionManager != null && motions.isNotEmpty()) {
            when (currentModel) {
                Live2DConfig.dianaModelPath -> {
                    currentMotionGroup = "Tap抱阿草-左手"
                    playAction(TouchAction(
                        text = "嗨～欢迎回来！",
                        motion = "Tap抱阿草-左手"
                    ), isWelcomeAction = true)
                }
                Live2DConfig.avaModelPath -> {
                    currentMotionGroup = "Tap左眼"
                    playAction(TouchAction(
                        text = "早中晚好呀～",
                        motion = "Tap左眼",
                        from = mapOf("Part15" to 1.0f),
                        to = mapOf("Part15" to 0.0f)
                    ), isWelcomeAction = true)
                }
                else -> {
                    (motions["Idle_0"] ?: motions.values.firstOrNull())?.let {
                        currentMotionGroup = "Idle"
                        motionManager?.startMotionPriority(it, 2)
                    }
                }
            }
        }
        
        modelData = modelData?.copy(isLoaded = true)
    }

    private fun createRendererForModel(model: CubismModel) {
        try { cubismRenderer?.close() } catch (_: Exception) {}
        cubismRenderer = CubismRendererAndroid.create()?.apply {
            initialize(model)
            setModelColor(1.0f, 1.0f, 1.0f, 1.0f)
        }
        Log.d(TAG, "渲染器已创建")
    }
    
    fun ensureRendererCreated() {
        val model = cubismModel ?: return
        if (cubismRenderer == null && isGLContextReady) {
            Log.d(TAG, "延迟创建渲染器")
            createRendererForModel(model)
        }
    }
    
    private fun readAssetBytes(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
    
    private fun loadMotions(@Suppress("UNUSED_PARAMETER") config: ModelConfig, modelDir: String) {
        val setting = modelSetting ?: return
        
        try {
            val eyeBlinkIds = (0 until setting.getEyeBlinkParameterCount()).map { setting.getEyeBlinkParameterId(it) }
            val lipSyncIds = (0 until setting.getLipSyncParameterCount()).map { setting.getLipSyncParameterId(it) }
            
            for (groupIndex in 0 until setting.getMotionGroupCount()) {
                val groupName = setting.getMotionGroupName(groupIndex)
                
                for (motionIndex in 0 until setting.getMotionCount(groupName)) {
                    val motionFileName = setting.getMotionFileName(groupName, motionIndex)
                    if (motionFileName.isEmpty()) continue
                    
                    val motionPath = Live2DHelper.buildAssetPath(modelDir, motionFileName)
                    val motionKey = "${groupName}_$motionIndex"
                    
                    try {
                        val motion = CubismMotion.create(readAssetBytes(motionPath), null, null, false)
                        if (motion != null) {
                            try {
                                val fadeIn = setting.getMotionFadeInTimeValue(groupName, motionIndex)
                                val fadeOut = setting.getMotionFadeOutTimeValue(groupName, motionIndex)
                                if (fadeIn >= 0f) motion.fadeInTime = fadeIn
                                if (fadeOut >= 0f) motion.fadeOutTime = fadeOut
                                if (groupName != "Idle") motion.setEffectIds(eyeBlinkIds, lipSyncIds)
                            } catch (_: Exception) {}
                            motions[motionKey] = motion
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "加载动作失败: $motionKey", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载动作出错", e)
        }
    }
    
    fun playMotion(motionName: String, priority: Int = 2) {
        val motionMgr = motionManager ?: return
        val model = cubismModel ?: return
        
        val previousGroup = currentMotionGroup
        val wasPlaying = !motionMgr.isFinished && motionMgr.currentPriority > 0
        
        if (wasPlaying && previousGroup != motionName) {
            try { resetToDefaultState(model) } catch (_: Exception) {}
        }
        
        val motion = motions["${motionName}_0"]
        if (motion != null) {
            val motionId = motionMgr.startMotionPriority(motion, priority)
            if (motionId >= 0) currentMotionGroup = motionName
        }
    }
    
    private fun hitTest(hitAreaName: String, screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int): Boolean {
        val model = cubismModel ?: return false
        val setting = modelSetting ?: return false
        val matrix = modelMatrix ?: return false
        
        try {
            for (i in 0 until setting.getHitAreasCount()) {
                if (setting.getHitAreaName(i) != hitAreaName) continue
                
                val drawIndex = model.getDrawableIndex(setting.getHitAreaId(i))
                if (drawIndex < 0) return false
                
                val vertexCount = model.getDrawableVertexCount(drawIndex)
                if (vertexCount == 0) return false
                
                val vertices = model.getDrawableVertices(drawIndex)
                var left = vertices[0]; var right = vertices[0]
                var top = vertices[1]; var bottom = vertices[1]
                
                for (j in 1 until vertexCount) {
                    val vx = vertices[j * 2]; val vy = vertices[j * 2 + 1]
                    if (vx < left) left = vx; if (vx > right) right = vx
                    if (vy < top) top = vy; if (vy > bottom) bottom = vy
                }
                
                val logicalX = (screenX / viewWidth) * 2.0f - 1.0f
                val logicalY = 1.0f - (screenY / viewHeight) * 2.0f
                val modelX = matrix.invertTransformX(logicalX)
                val modelY = matrix.invertTransformY(logicalY)
                
                return left <= modelX && modelX <= right && top <= modelY && modelY <= bottom
            }
        } catch (_: Exception) {}
        return false
    }
    
    private fun hitTestAnyArea(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int): Boolean {
        val setting = modelSetting ?: return false
        return (0 until setting.getHitAreasCount()).any { hitTest(setting.getHitAreaName(it), screenX, screenY, viewWidth, viewHeight) }
    }
    
    fun handleTouch(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Boolean {
        if (isWelcomeActionPlaying) return false
        
        val motionMgr = motionManager
        val isFinished = motionMgr?.isFinished ?: true
        val priority = motionMgr?.currentPriority ?: 0
        
        if (!isIdleState() || !isFinished || priority > 0) return false
        if (!hitTestAnyArea(x, y, viewWidth, viewHeight)) return false
        
        if (touchActionList.isNotEmpty()) {
            playAction(touchActionList.random(), isWelcomeAction = false)
            return true
        }
        return handleTouchFallback()
    }
    
    private fun initializeTouchActionList() {
        touchActionList = when (currentModel) {
            Live2DConfig.dianaModelPath -> listOf(
                TouchAction(text = "嘉心糖屁用没有", motion = "Tap生气 -领结"),
                TouchAction(text = "有人急了，但我不说是谁~", motion = "Tap= =  左蝴蝶结"),
                TouchAction(text = "呜呜...呜呜呜....", motion = "Tap哭 -眼角"),
                TouchAction(text = "想然然了没有呀~", motion = "Tap害羞-中间刘海"),
                TouchAction(text = "阿草好软呀~", motion = "Tap抱阿草-左手"),
                TouchAction(text = "不要再戳啦！好痒！", motion = "Tap摇头- 身体"),
                TouchAction(text = "嗷呜~~~", motion = "Tap耳朵-发卡"),
                TouchAction(text = "zzZ。。。", motion = "Leave"),
                TouchAction(text = "哇！好吃的！", motion = "Tap右头发")
            )
            Live2DConfig.avaModelPath -> listOf(
                TouchAction(text = "水母 水母~ 只是普通的生物", motion = "Tap右手"),
                TouchAction(text = "可爱的鸽子鸽子~我喜欢你~", motion = "Tap胸口项链", from = mapOf("Part12" to 1.0f), to = mapOf("Part12" to 0.0f)),
                TouchAction(text = "好...好兄弟之间喜欢很正常啦", motion = "Tap中间刘海", from = mapOf("Part12" to 1.0f), to = mapOf("Part12" to 0.0f)),
                TouchAction(text = "啊啊啊！怎么推流辣", motion = "Tap右眼", from = mapOf("Part16" to 1.0f), to = mapOf("Part16" to 0.0f)),
                TouchAction(text = "你怎么老摸我，我的身体是不是可有魅力", motion = "Tap嘴"),
                @Suppress("SpellCheckingInspection")
                TouchAction(text = "AAAAAAAAAAvvvvAAA 向晚！", motion = "Tap左眼", from = mapOf("Part15" to 1.0f), to = mapOf("Part15" to 0.0f))
            )
            else -> emptyList()
        }
    }

    private fun initializeAvaHideParts(model: CubismModel) {
        val idManager = CubismFramework.getIdManager()
        listOf("Part5", "Part15", "Part21", "Part22", "Part", "Part16", "Part12", "neko", "game").forEach { partIdStr ->
            try {
                val partIndex = model.getPartIndex(idManager.getId(partIdStr))
                if (partIndex >= 0) model.setPartOpacity(partIndex, 0f)
            } catch (_: Exception) {}
        }
    }

    private fun playAction(action: TouchAction, isWelcomeAction: Boolean = false) {
        if (!isWelcomeAction && isWelcomeActionPlaying) return
        
        if (!isWelcomeAction) {
            val motionMgr = motionManager
            if (!isIdleState() || !(motionMgr?.isFinished ?: true) || (motionMgr?.currentPriority ?: 0) > 0) return
        }
        
        if (isWelcomeAction) isWelcomeActionPlaying = true
        
        pendingPartOpacityChanges = null
        cancelAllAnimatorsSafely()
        
        action.text?.let { onTextDisplay?.invoke(it) }
        action.motion?.let { playMotion(it, 2) }
        
        if (action.from.isNotEmpty()) setPartOpacities(action.from)
        pendingPartOpacityChanges = action.to.takeIf { it.isNotEmpty() }
    }
    
    private fun setPartOpacities(partOpacities: Map<String, Float>, duration: Float = 0.6f) {
        val model = cubismModel ?: return
        val idManager = CubismFramework.getIdManager()
        
        partOpacities.forEach { (partIdStr, targetOpacity) ->
            try {
                val partIndex = model.getPartIndex(idManager.getId(partIdStr))
                if (partIndex < 0) return@forEach
                
                val currentOpacity = model.getPartOpacity(partIndex)
                val target = targetOpacity.coerceIn(0f, 1f)
                
                if (kotlin.math.abs(currentOpacity - target) < 0.01f || duration <= 0f) {
                    model.setPartOpacity(partIndex, target)
                    partOpacityAnimations.remove(partIdStr)
                    return@forEach
                }
            partOpacityAnimations[partIdStr] = PartOpacityAnimation(
                    partId = partIdStr,
                    startOpacity = currentOpacity,
                    targetOpacity = target,
                    duration = duration,
                    elapsed = 0f
                )
            } catch (_: Exception) {}
        }
    }
    
    private fun updatePartOpacityAnimations(deltaTime: Float) {
        if (partOpacityAnimations.isEmpty()) return
        
        val model = cubismModel ?: return
        val idManager = CubismFramework.getIdManager()
        val completedAnimations = mutableListOf<String>()
        
        partOpacityAnimations.forEach { (partIdStr, anim) ->
            try {
                val partIndex = model.getPartIndex(idManager.getId(partIdStr))
                if (partIndex < 0) {
                    completedAnimations.add(partIdStr)
                    return@forEach
                }
                
                anim.elapsed += deltaTime
                val progress = (anim.elapsed / anim.duration).coerceIn(0f, 1f)
                val eased = 1f - (1f - progress) * (1f - progress)
                val newOpacity = anim.startOpacity + (anim.targetOpacity - anim.startOpacity) * eased
                
                model.setPartOpacity(partIndex, newOpacity.coerceIn(0f, 1f))
                
                if (progress >= 1f) {
                    model.setPartOpacity(partIndex, anim.targetOpacity)
                    completedAnimations.add(partIdStr)
                }
            } catch (_: Exception) {
                completedAnimations.add(partIdStr)
            }
                
        }
        completedAnimations.forEach { partOpacityAnimations.remove(it) }
    }
    
    private fun handleTouchFallback(): Boolean {
        if (touchActionList.isEmpty()) {
            when (currentModel) {
                Live2DConfig.dianaModelPath -> {
                    playMotion("Tap抱阿草-左手", 2)
                    onTextDisplay?.invoke("阿草好软呀~")
                    return true
                }
                Live2DConfig.avaModelPath -> {
                    playAction(TouchAction(motion = "Tap左眼", from = mapOf("Part15" to 1.0f), to = mapOf("Part15" to 0.0f)))
                    return true
                }
            }
        }
        return false
    }
    
    fun switchModel() {
        val models = Live2DConfig.getAllModelPaths()
        if (models.isEmpty()) return
        val currentIndex = models.indexOf(currentModel ?: models.first())
        val nextIndex = if (currentIndex < 0 || currentIndex >= models.size - 1) 0 else currentIndex + 1
        loadModel(models[nextIndex])
    }
    
    private fun cancelAllAnimatorsSafely() {
        partOpacityAnimations.clear()
        val animators = runningPartAnimators.values.toList()
        runningPartAnimators.clear()
        if (animators.isNotEmpty()) {
            mainHandler.post {
                animators.forEach { try { it.cancel() } catch (_: Exception) {} }
            }
        }
    }
    private fun releaseCurrentModel() {
        cancelAllAnimatorsSafely()
        resetModelState()
        
        motions.clear()
        motionManager = null
        expressions.clear()
        expressionManager = null
        physics = null
        eyeBlink = null
        breath = null
        idParamEyeBallX = null
        idParamEyeBallY = null
        idParamEyeBallForm = null
        
        try { cubismRenderer?.close() } catch (_: Exception) {}
        cubismRenderer = null
        try { cubismModel?.let { cubismMoc?.deleteModel(it); it.close() } } catch (_: Exception) {}
        cubismModel = null
        try { cubismMoc?.delete() } catch (_: Exception) {}
        cubismMoc = null
        
        modelSetting = null
        modelMatrix = null
        textureManager?.release()
        modelData = modelData?.copy(isLoaded = false)
    }
    
    fun release() {
        releaseCurrentModel()
        textureManager = null
        modelData = null
        currentModel = null
        isGLContextReady = false
        // 清理 SDK 以便下次完全重新初始化
        if (isSDKInitialized) {
            try { CubismFramework.dispose() } catch (_: Exception) {}
            isSDKInitialized = false
        }
    }
    
    fun destroy() {
        release()
    }

    fun validateModelState(): Boolean {
        val hasModel = cubismModel != null
        val hasMoc = cubismMoc != null
        val hasRenderer = cubismRenderer != null
        // 渲染器可能在 GL 上下文就绪后才创建，所以不强制检查
        if (hasModel && (!hasMoc || modelData == null)) return false
        if (!hasModel && hasMoc) return false
        if (hasModel && !isSDKInitialized) return false
        return true
    }

    fun getModelData(): Live2DModelData? = modelData
    fun getModel(): CubismModel? = cubismModel
    fun getRenderer(): CubismRenderer? = cubismRenderer
    fun getModelMatrix(): CubismModelMatrix? = modelMatrix
    
    private fun saveDefaultPartOpacities(model: CubismModel) {
        try {
            val opacities = mutableMapOf<String, Float>()
            for (i in 0 until model.getPartCount()) {
                try {
                    opacities[model.getPartId(i).getString()] = model.getPartOpacity(i)
                } catch (_: Exception) {}
            }
            defaultPartOpacities = opacities
        } catch (_: Exception) {
            defaultPartOpacities = emptyMap()
        }
    }
    
    private fun resetToDefaultState(model: CubismModel) {
        for (i in 0 until model.getParameterCount()) {
            model.setParameterValue(i, model.getParameterDefaultValue(i))
        }
        restoreDefaultPartOpacities(model)
        model.saveParameters()
    }
    
    private fun restoreDefaultPartOpacities(model: CubismModel) {
        if (defaultPartOpacities.isEmpty()) return
        val idManager = CubismFramework.getIdManager()
        defaultPartOpacities.forEach { (partIdStr, opacity) ->
            try {
                val idx = model.getPartIndex(idManager.getId(partIdStr))
                if (idx >= 0) model.setPartOpacity(idx, opacity)
            } catch (_: Exception) {}
        }
    }
    
    private fun loadExpressions(config: ModelConfig, modelDir: String) {
        if (config.expressions.isEmpty()) return
        expressionManager = CubismExpressionMotionManager()
        config.expressions.forEach { (name, file) ->
            try {
                CubismExpressionMotion.create(readAssetBytes(Live2DHelper.buildAssetPath(modelDir, file)))?.let {
                    expressions[name] = it
                }
            } catch (_: Exception) {}
        }
    }
    
    private fun loadPhysics(config: ModelConfig, modelDir: String) {
        config.physics?.let {
            try { physics = CubismPhysics.create(readAssetBytes(Live2DHelper.buildAssetPath(modelDir, it))) } catch (_: Exception) {}
        }
    }
    
    private fun initializeEyeBlinkAndBreath() {
        val setting = modelSetting ?: return
        
        if (setting.getEyeBlinkParameterCount() > 0) {
            eyeBlink = CubismEyeBlink.create(setting)
        }
        
        breath = CubismBreath.create()?.also { b ->
            try {
                val idManager = CubismFramework.getIdManager()
                val params = mutableListOf(
                    CubismBreath.BreathParameterData(idManager.getId("ParamAngleX"), 0.0f, 15.0f, 6.5345f, 0.5f),
                    CubismBreath.BreathParameterData(idManager.getId("ParamAngleY"), 0.0f, 8.0f, 3.5345f, 0.5f),
                    CubismBreath.BreathParameterData(idManager.getId("ParamAngleZ"), 0.0f, 10.0f, 5.5345f, 0.5f),
                    CubismBreath.BreathParameterData(idManager.getId("ParamBodyAngleX"), 0.0f, 4.0f, 15.5345f, 0.5f)
                )
                cubismModel?.let { model ->
                    val breathId = idManager.getId("ParamBreath")
                    if (model.getParameterIndex(breathId) >= 0) {
                        params.add(CubismBreath.BreathParameterData(breathId, 0.5f, 0.5f, 3.2345f, 0.5f))
                    }
                }
                b.setParameters(params)
            } catch (_: Exception) {}
        }
    }
    
    private fun initializeEyeBallParameters() {
        try {
            val idManager = CubismFramework.getIdManager()
            idParamEyeBallX = idManager.getId("ParamEyeBallX")
            idParamEyeBallY = idManager.getId("ParamEyeBallY")
            try { idParamEyeBallForm = idManager.getId("ParamEyeBallForm") } catch (_: Exception) { idParamEyeBallForm = null }
        } catch (_: Exception) {
            idParamEyeBallX = null
            idParamEyeBallY = null
            idParamEyeBallForm = null
        }
    }
    
    fun initializeEyeBallParametersAfterLoad(model: CubismModel) {
        if (idParamEyeBallX == null || idParamEyeBallY == null) return
        try {
            val xIdx = model.getParameterIndex(idParamEyeBallX!!)
            val yIdx = model.getParameterIndex(idParamEyeBallY!!)
            if (xIdx >= 0) model.setParameterValue(xIdx, 0.0f)
            if (yIdx >= 0) model.setParameterValue(yIdx, 0.0f)
            eyeBallParametersInitialized = true
        } catch (_: Exception) {}
    }
    
    fun updateEyeBallTracking(normalizedX: Float, normalizedY: Float) {
        val smoothFactor = 0.2f
        eyeBallTrackingX += (normalizedX - eyeBallTrackingX) * smoothFactor
        eyeBallTrackingY += (normalizedY - eyeBallTrackingY) * smoothFactor
    }
    
    private fun updateEyeBallSizeBasedOnBlink(model: CubismModel) {
        val blink = eyeBlink ?: return
        val formId = idParamEyeBallForm ?: return
        
        try {
            val blinkIds = blink.getParameterIds()
            if (blinkIds.isEmpty()) return
            
            val blinkIdx = model.getParameterIndex(blinkIds[0])
            if (blinkIdx < 0) return
            
            val eyeOpen = model.getParameterValue(blinkIdx).coerceIn(0f, 1f)
            val formIdx = model.getParameterIndex(formId)
            if (formIdx < 0) return
            
            val defaultForm = model.getParameterDefaultValue(formIdx)
            val currentForm = model.getParameterValue(formIdx)
            val targetForm = defaultForm * (0.3f + eyeOpen * 0.7f)
            model.setParameterValue(formIdx, currentForm + (targetForm - currentForm) * 0.1f)
        } catch (_: Exception) {}
    }
    
    private var wasMotionPlaying = false
    private var lastMotionFinished = true
    private var motionFinishedTime: Float = -1f
    private val motionFadeoutDelay = 0.3f
    private val parameterFadeDuration = 0.5f
    private var parameterFadeTime: Float = -1f
    private var currentMotionGroup: String = "Idle"
    private var isWelcomeActionPlaying = false
    
    fun updateMotions(deltaTime: Float): Boolean {
        val motionMgr = motionManager ?: return false
        val model = cubismModel ?: return false
        
        return try {
            val isUpdated = motionMgr.updateMotion(model, deltaTime)
            val isFinished = motionMgr.isFinished
            val priority = motionMgr.currentPriority
            val isPlaying = !isFinished && priority > 0
            
            // 动作完成时执行 to 部分透明度变化
            if (!lastMotionFinished && isFinished && pendingPartOpacityChanges != null) {
                setPartOpacities(pendingPartOpacityChanges!!)
                pendingPartOpacityChanges = null
            }
            
            // 动作完成进入 Idle 状态
            if (isFinished && priority == 0) {
                if (currentMotionGroup != "Idle") {
                    currentMotionGroup = "Idle"
                    if (motionFinishedTime < 0f) motionFinishedTime = 0f
                }
            } else {
                if (motionFinishedTime >= 0f) motionFinishedTime = -1f
            }
            
            // 平滑过渡到默认状态
            if (motionFinishedTime >= 0f && isFinished && priority == 0) {
                motionFinishedTime += deltaTime
                
                if (motionFinishedTime >= motionFadeoutDelay) {
                    if (parameterFadeTime < 0f) parameterFadeTime = 0f
                    parameterFadeTime += deltaTime
                    
                    if (parameterFadeTime < parameterFadeDuration) {
                        val progress = parameterFadeTime / parameterFadeDuration
                        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
                        for (i in 0 until model.getParameterCount()) {
                            val curr = model.getParameterValue(i)
                            val def = model.getParameterDefaultValue(i)
                            model.setParameterValue(i, curr + (def - curr) * eased)
                        }
                    } else {
                        for (i in 0 until model.getParameterCount()) {
                            model.setParameterValue(i, model.getParameterDefaultValue(i))
                        }
                        restoreDefaultPartOpacities(model)
                        model.saveParameters()
                        motionFinishedTime = -1f
                        parameterFadeTime = -1f
                    }
                }
            } else {
                if (motionFinishedTime >= 0f || parameterFadeTime >= 0f) {
                    motionFinishedTime = -1f
                    parameterFadeTime = -1f
                }
            }
            
            // 欢迎动作完成清理
            if (isFinished && priority == 0 && isWelcomeActionPlaying) {
                cancelAllAnimatorsSafely()
                pendingPartOpacityChanges = null
                isWelcomeActionPlaying = false
            }
            
            lastMotionFinished = isFinished
            wasMotionPlaying = isPlaying
            isUpdated
        } catch (_: Exception) { false }
    }
    
    fun isIdleState(): Boolean {
        val motionMgr = motionManager ?: return true
        if (!motionMgr.isFinished || motionMgr.currentPriority > 0) return false
        return currentMotionGroup == "Idle"
    }
    
    fun updateEffects(deltaTime: Float, isMotionUpdated: Boolean) {
        val model = cubismModel ?: return
        
        try {
            physics?.evaluate(model, deltaTime)
            
            if (!isMotionUpdated) {
                eyeBlink?.updateParameters(model, deltaTime)
                updateEyeBallSizeBasedOnBlink(model)
            }
            
            breath?.updateParameters(model, deltaTime)
            
            // 眼珠跟踪
            if (idParamEyeBallX != null && idParamEyeBallY != null) {
                try {
                    val xIdx = model.getParameterIndex(idParamEyeBallX!!)
                    val yIdx = model.getParameterIndex(idParamEyeBallY!!)
                    if (xIdx >= 0) model.addParameterValue(xIdx, eyeBallTrackingX)
                    if (yIdx >= 0) model.addParameterValue(yIdx, eyeBallTrackingY)
                    
                    // 异常值检查
                    if (xIdx >= 0) {
                        val x = model.getParameterValue(xIdx)
                        if (x.isNaN() || x.isInfinite() || kotlin.math.abs(x) > 10f) model.setParameterValue(xIdx, 0f)
                    }
                    if (yIdx >= 0) {
                        val y = model.getParameterValue(yIdx)
                        if (y.isNaN() || y.isInfinite() || kotlin.math.abs(y) > 10f) model.setParameterValue(yIdx, 0f)
                    }
                } catch (_: Exception) {}
            }
            
            expressionManager?.updateMotion(model, deltaTime)
            updatePartOpacityAnimations(deltaTime)
        } catch (_: Exception) {}
    }
    
    fun loadAndBindTextures() {
        val renderer = cubismRenderer as? CubismRendererAndroid ?: return
        val config = modelData?.config ?: return
        val modelDir = modelData?.modelDir ?: return

        if (textureManager == null) textureManager = TextureManager(context)

        textureManager?.let { tm ->
            config.textures.forEachIndexed { index, texturePath ->
                val fullPath = Live2DHelper.buildAssetPath(modelDir, texturePath)
                val textureId = tm.loadTexture(fullPath)
                if (textureId != 0) {
                    renderer.bindTexture(index, textureId)
                    renderer.isPremultipliedAlpha(true)
                }
            }
        }
    }
}

data class TouchAction(
    val text: String? = null,
    val motion: String? = null,
    val from: Map<String, Float> = emptyMap(),
    val to: Map<String, Float> = emptyMap()
)
