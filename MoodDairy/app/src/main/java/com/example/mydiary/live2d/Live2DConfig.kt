package com.example.mydiary.live2d

/**
 * Live2D 配置管理类
 * 用于管理模型配置，避免硬编码
 */
object Live2DConfig {

    /**
     * 模型信息数据类
     */
    data class ModelInfo(
        val id: String,
        val path: String,
        val displayName: String,
        val description: String? = null,
        val styleName: String = "",
        val avatarPath: String = ""
    )

    /**
     * 可用的模型列表
     */
    val availableModels = listOf(
        ModelInfo(
            id = "diana",
            path = "live2d/Diana/Diana.model3.json",
            displayName = "然然",
            description = "温柔相伴，记录日常",
            styleName = "暖心陪伴",
            avatarPath = "live2d/Diana/raw.preview.png"
        ),
        ModelInfo(
            id = "ava",
            path = "live2d/Ava/Ava.model3.json",
            displayName = "然然",
            description = "灵动相随，分享心情",
            styleName = "灵动乐伴",
            avatarPath = "live2d/Ava/raw.preview.png"
        )
    )

    /**
     * 默认模型
     */
    val defaultModel = availableModels.first()

    /** Diana 模型路径（与 assets 一致） */
    val dianaModelPath: String get() = getModelById("diana")?.path ?: "live2d/Diana/Diana.model3.json"

    /** Ava 模型路径（与 assets 一致） */
    val avaModelPath: String get() = getModelById("ava")?.path ?: "live2d/Ava/Ava.model3.json"

    /**
     * 根据ID获取模型信息
     */
    fun getModelById(id: String): ModelInfo? {
        return availableModels.find { it.id == id }
    }

    /**
     * 根据路径获取模型信息
     */
    fun getModelByPath(path: String): ModelInfo? {
        return availableModels.find { it.path == path }
    }

    /**
     * 获取所有模型路径
     */
    fun getAllModelPaths(): List<String> {
        return availableModels.map { it.path }
    }

    /**
     * 验证模型路径是否有效
     */
    fun isValidModelPath(path: String): Boolean {
        return availableModels.any { it.path == path }
    }
}
