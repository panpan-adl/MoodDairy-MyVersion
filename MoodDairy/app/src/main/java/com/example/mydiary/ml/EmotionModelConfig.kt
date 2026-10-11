package com.example.mydiary.ml

/**
 * 表情识别模型配置常量
 *
 * EmotiEffLib EfficientNet-B2，AffectNet-7 权重，260x260 RGB NCHW 输入。
 * 输出顺序采用作者定义，转换成后端使用的标签。
 */
object EmotionModelConfig {

    /** 模型在 assets 中的路径 */
    const val MODEL_PATH = "emotion/enet_b2_7.onnx"
    const val MODEL_NAME = "EfficientNet-B2 + FER+ classifier fine-tune"
    const val MODEL_SHA256 = "a9f282e093c582cf25f5a4af7ca2ce116525b038e7ad068839f0e066f781aa5c"

    /** 模型输入边长 */
    const val INPUT_SIZE = 260

    // Two views reduce sensitivity to left/right asymmetry; accuracy requires held-out evaluation.
    const val USE_FLIP_ENSEMBLE = true

    /** RGB 输入通道 */
    const val INPUT_CHANNELS = 3

    /**
     * 作者的输出顺序：angry, disgust, fear, happy, neutral, sad, surprise。
     */
    val LABELS = listOf(
        "angry", "disgust", "fear", "happy", "neutral", "sad", "surprise"
    )

    /** 单帧置信度门槛，低于该值的样本不参与投票 */
    const val CONFIDENCE_THRESHOLD = 0.40f

    /** 每两秒分析一帧，持续负面表情另按实际经过时间判断。 */
    const val CAPTURE_INTERVAL_MS = 2_000L

    const val NEGATIVE_PROMPT_DURATION_MS = 10_000L
    const val PROMPT_COOLDOWN_MS = 300_000L

    /** 同标签上报最小间隔（毫秒），用于去重 */
    const val REPORT_MIN_INTERVAL_MS = 60_000L
}
