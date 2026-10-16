package com.example.mydiary.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mlkit.vision.face.FaceLandmark
import com.example.mydiary.ml.FaceCropGeometry
import com.example.mydiary.ml.FaceBitmapCropper
import kotlin.math.abs
import kotlin.math.atan2
import android.util.Log
import android.util.Size
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.mydiary.ml.EmotionModelConfig
import com.example.mydiary.ml.EmotionProbabilitySmoother
import com.example.mydiary.ml.FaceEmotionAnalyzer
import com.example.mydiary.ml.NegativeEmotionDurationTracker
import com.example.mydiary.ml.PositiveEmotionRefiner
import com.example.mydiary.ml.PositiveExpressionClassifier
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 摄像头表情采集控制器
 *
 * 聊天页前台时启动：
 * - CameraX 绑定前置相机 + ImageAnalysis（640x480，KEEP_ONLY_LATEST，无 Preview 静默帧）
 * - 协程定时器按配置间隔请求采集一帧
 * - 单帧流程：ML Kit 人脸检测 → 裁剪 ROI → RGB 预处理 → ONNX 推理 → 平滑投票 → 回调
 *
 * 帧 ImageProxy 用完立即 close()，全程不落盘。
 * 模型缺失时静默降级：start() 返回 false，仅 Log.w。
 */
@Singleton
class EmotionCaptureController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val tag = "EmotionCaptureController"

    private val running = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    private var captureScope: CoroutineScope? = null

    @Volatile
    private var emotionAnalyzer: FaceEmotionAnalyzer? = null

    @Volatile
    private var faceDetector: FaceDetector? = null

    @Volatile
    private var smoother: EmotionProbabilitySmoother? = null

    /**
     * 启动表情采集
     *
     * @param lifecycleOwner 生命周期所有者（聊天页所在 Activity/NavBackStackEntry）
     * @param onResult 平滑后稳定标签回调 (label, confidence)，在分析线程触发
     * @return 是否成功启动（模型不可用时返回 false）
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        onResult: (label: String, confidence: Float, sustainedNegativeMs: Long, facialExpression: String?) -> Unit,
        onObservation: (label: String?, confidence: Float) -> Unit = { _, _ -> },
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            return true // 已在运行
        }

        val analyzer = FaceEmotionAnalyzer(context)
        if (!analyzer.isAvailable) {
            Log.w(tag, "表情模型不可用，采集未启动")
            running.set(false)
            return false
        }
        emotionAnalyzer = analyzer
        val sessionSmoother = EmotionProbabilitySmoother()
        val durationTracker = NegativeEmotionDurationTracker()
        val positiveRefiner = PositiveEmotionRefiner()
        smoother = sessionSmoother

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
        faceDetector = detector

        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        captureScope = scope

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!running.get() || emotionAnalyzer !== analyzer) {
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis = analysis
                analysis.setAnalyzer(executor) { imageProxy ->
                    if (emotionAnalyzer === analyzer && running.get()) {
                        processFrame(imageProxy, analyzer, detector, sessionSmoother, durationTracker, positiveRefiner, onResult, onObservation)
                    } else {
                        imageProxy.close()
                    }
                }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                Log.d(tag, "表情采集已启动")
            } catch (e: Exception) {
                Log.w(tag, "相机绑定失败: ${e.message}")
                stop()
            }
        }, ContextCompat.getMainExecutor(context))

        // 按配置请求采集，不处理其余帧。
        scope.launch {
            while (isActive && emotionAnalyzer === analyzer) {
                captureRequested.set(true)
                delay(EmotionModelConfig.CAPTURE_INTERVAL_MS)
            }
        }

        return true
    }

    /** 停止采集并解绑相机 */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        captureRequested.set(false)
        captureScope?.cancel()
        captureScope = null
        imageAnalysis?.let { analysis ->
            analysis.clearAnalyzer()
            cameraProvider?.unbind(analysis)
        }
        imageAnalysis = null
        cameraProvider = null
        val retiredAnalyzer = emotionAnalyzer
        val retiredDetector = faceDetector
        val retiredExecutor = analysisExecutor
        emotionAnalyzer = null
        faceDetector = null
        smoother = null
        analysisExecutor = null
        // 与 processFrame 共用单线程队列：等待正在执行的原生推理结束后再释放。
        // 不在主线程等待，避免关闭页面时出现 ANR 或 Interpreter use-after-free。
        if (retiredExecutor != null) {
            retiredExecutor.execute {
                try {
                    retiredAnalyzer?.close()
                } finally {
                    retiredDetector?.close()
                }
            }
            retiredExecutor.shutdown()
        }
        Log.d(tag, "表情采集已停止")
    }

    /** 是否正在采集 */
    fun isRunning(): Boolean = running.get()

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(
        imageProxy: ImageProxy,
        analyzer: FaceEmotionAnalyzer,
        detector: FaceDetector,
        sessionSmoother: EmotionProbabilitySmoother,
        durationTracker: NegativeEmotionDurationTracker,
        positiveRefiner: PositiveEmotionRefiner,
        onResult: (String, Float, Long, String?) -> Unit,
        onObservation: (String?, Float) -> Unit,
    ) {
        // 未被定时器请求的帧直接丢弃
        if (!captureRequested.compareAndSet(true, false)) {
            imageProxy.close()
            return
        }

        if (!running.get() || emotionAnalyzer !== analyzer || !analyzer.isAvailable) {
            imageProxy.close()
            return
        }

        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees,
            )
            val faces = Tasks.await(detector.process(inputImage))
            val face = faces.maxByOrNull {
                it.boundingBox.width() * it.boundingBox.height()
            }
            if (face == null) {
                durationTracker.reset()
                sessionSmoother.reset(); positiveRefiner.reset()
                if (emotionAnalyzer === analyzer && running.get()) onObservation(null, 0f)
                Log.d(tag, "未检测到人脸，丢帧")
                return
            }

            // toBitmap() 不带旋转信息，先旋转到正向再按人脸框裁剪
            val rawBitmap = imageProxy.toBitmap()
            val upright = rotateBitmap(rawBitmap, imageProxy.imageInfo.rotationDegrees)
            if (upright != rawBitmap) {
                rawBitmap.recycle()
            }

            val qualityIssue = when {
                minOf(face.boundingBox.width(), face.boundingBox.height()) < 64 -> "face_too_small"
                abs(face.headEulerAngleY) > 40f || abs(face.headEulerAngleX) > 35f -> "face_turned"
                else -> null
            }
            if (qualityIssue != null) {
                upright.recycle()
                durationTracker.reset()
                sessionSmoother.reset(); positiveRefiner.reset()
                if (emotionAnalyzer === analyzer && running.get()) onObservation(qualityIssue, 0f)
                Log.d(tag, "quality=$qualityIssue box=${face.boundingBox} yaw=${face.headEulerAngleY} pitch=${face.headEulerAngleX}")
                return
            }
            val box = face.boundingBox
            val bounds = FaceCropGeometry.square(box.left, box.top, box.right, box.bottom, upright.width, upright.height)
            if (bounds == null) {
                upright.recycle()
                durationTracker.reset()
                sessionSmoother.reset(); positiveRefiner.reset()
                if (emotionAnalyzer === analyzer && running.get()) onObservation("incomplete_face", 0f)
                Log.d(tag, "crop rejected: box=$box image=${imageProxy.width}x${imageProxy.height} rotation=${imageProxy.imageInfo.rotationDegrees}")
                return
            }
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val eyeAngle = if (leftEye != null && rightEye != null) {
                val a = if (leftEye.x < rightEye.x) leftEye else rightEye
                val b = if (leftEye.x < rightEye.x) rightEye else leftEye
                Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            } else 0f
            val rawScores = try {
                val faceBitmap = FaceBitmapCropper.crop(upright, bounds, eyeAngle)
                try { analyzer.analyzeProbabilities(faceBitmap) } finally { faceBitmap.recycle() }
            } finally { upright.recycle() }
            val scores = rawScores?.let { sessionSmoother.add(it, SystemClock.elapsedRealtime()) }
            val result = scores?.let {
                positiveRefiner.refine(
                    scores, face.smilingProbability, face.headEulerAngleY, face.headEulerAngleX,
                    SystemClock.elapsedRealtime()
                )
            }
            if (result != null) {
                val expression = PositiveExpressionClassifier.classify(
                    result, scores, face.smilingProbability, face.headEulerAngleY, face.headEulerAngleX
                )
                val displayLabel = expression ?: result.first
                val rawPrediction = rawScores?.let { com.example.mydiary.ml.EmotionPrediction.fromProbabilities(it) }
                val clearNonNegative = rawPrediction != null && rawPrediction.second >= 0.65f &&
                    rawPrediction.first in setOf("happy", "neutral", "surprise")
                val duration = if (clearNonNegative) {
                    durationTracker.reset(); 0L
                } else durationTracker.observe(result.first, result.second, SystemClock.elapsedRealtime())
                if (emotionAnalyzer === analyzer && running.get()) onObservation(displayLabel, result.second)
                Log.d(
                    tag,
                    "融合推理: ${result.first} ${"%.2f".format(result.second)} " +
                        "box=${face.boundingBox.width()}x${face.boundingBox.height()}",
                )
                val stable = (displayLabel to result.second).takeIf {
                    result.second >= EmotionModelConfig.CONFIDENCE_THRESHOLD && !clearNonNegative
                } ?: (displayLabel to result.second).takeIf {
                    result.first in setOf("happy", "neutral", "surprise") && result.second >= EmotionModelConfig.CONFIDENCE_THRESHOLD
                }
                if (stable != null && running.get() && emotionAnalyzer === analyzer) {
                    Log.d(tag, "平滑结果: ${stable.first}，触发上报")
                    onResult(
                        PositiveExpressionClassifier.baseEmotion(stable.first), stable.second,
                        duration.coerceAtMost(60_000L),
                        stable.first.takeIf { it in PositiveExpressionClassifier.names }
                    )
                }
            } else {
                durationTracker.reset()
                if (rawScores == null) {
                    sessionSmoother.reset(); positiveRefiner.reset()
                    if (emotionAnalyzer === analyzer && running.get()) onObservation("inference_failed", 0f)
                } else {
                    val initial = com.example.mydiary.ml.EmotionPrediction.fromProbabilities(rawScores)
                    if (emotionAnalyzer === analyzer && running.get()) onObservation("stabilizing_face", initial?.second ?: 0f)
                }
            }
        } catch (e: Exception) {
            durationTracker.reset()
            sessionSmoother.reset(); positiveRefiner.reset()
            if (emotionAnalyzer === analyzer && running.get()) onObservation("frame_failed", 0f)
            Log.w(tag, "帧处理失败，丢帧: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) {
            return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

}
