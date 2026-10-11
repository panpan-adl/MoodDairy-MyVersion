package com.example.mydiary.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.security.MessageDigest

/** 本地表情推理；加载失败时关闭识别，保留聊天功能。 */
class FaceEmotionAnalyzer(context: Context) {
    private val tag = "FaceEmotionAnalyzer"
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    val isAvailable: Boolean get() = session != null

    init {
        try {
            val bytes = context.assets.open(EmotionModelConfig.MODEL_PATH).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == EmotionModelConfig.MODEL_SHA256) { "Model checksum mismatch" }
            val env = OrtEnvironment.getEnvironment()
            val loaded = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                env.createSession(bytes, options)
            }
            try {
                require(loaded.inputInfo.size == 1 && loaded.outputInfo.size == 1)
                val entry = loaded.inputInfo.entries.single()
                val shape = (entry.value.info as TensorInfo).shape
                require(shape.size == 4 && shape[0] in listOf(-1L, 1L) &&
                    shape.drop(1) == listOf(3L, 260L, 260L)) { "Unexpected input shape" }
                val outputShape = (loaded.outputInfo.values.single().info as TensorInfo).shape
                require(outputShape.size == 2 && outputShape[0] in listOf(-1L, 1L) &&
                    outputShape[1] == 7L) { "Unexpected output shape" }
                environment = env
                inputName = entry.key
                session = loaded
                Log.i(tag, "Loaded ${EmotionModelConfig.MODEL_NAME}")
            } catch (e: Exception) {
                loaded.close()
                throw e
            }
        } catch (e: Exception) {
            Log.w(tag, "表情模型加载失败，表情识别关闭", e)
        } catch (e: LinkageError) {
            Log.w(tag, "表情推理运行库不可用，表情识别关闭", e)
        }
    }

    fun analyze(bitmap: Bitmap): Pair<String, Float>? {
        return analyzeProbabilities(bitmap)?.let { EmotionPrediction.fromProbabilities(it) }
    }

    @Synchronized
    fun analyzeProbabilities(bitmap: Bitmap): FloatArray? {
        val active = session ?: return null
        val env = environment ?: return null
        val name = inputName ?: return null
        return try {
            val input = preprocessFaceBitmap(bitmap)
            fun infer(values: FloatArray): FloatArray? =
                OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 3, 260, 260)).use { tensor ->
                    active.run(mapOf(name to tensor)).use { outputs ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = outputs[0].value as Array<FloatArray>
                        EmotionPrediction.probabilities(logits[0])
                    }
                }
            val original = infer(input) ?: return null
            if (!EmotionModelConfig.USE_FLIP_ENSEMBLE) return original
            val flipped = infer(FaceImagePreprocessor.flipRgbNchw(input,
                EmotionModelConfig.INPUT_SIZE, EmotionModelConfig.INPUT_SIZE)) ?: return null
            FloatArray(original.size) { (original[it] + flipped[it]) / 2f }
        } catch (e: Exception) {
            Log.w(tag, "表情推理失败", e)
            null
        }
    }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        inputName = null
        // OrtEnvironment 是进程级共享资源，不在单个采集会话结束时关闭。
        environment = null
    }
}
