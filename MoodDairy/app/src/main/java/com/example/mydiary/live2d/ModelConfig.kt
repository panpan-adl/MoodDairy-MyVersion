package com.example.mydiary.live2d

/**
 * Live2D 模型配置文件数据类
 * 表示从 model3.json 文件解析出的配置信息
 */
data class ModelConfig(
    val mocFile: String,
    val textures: List<String>,
    val physics: String?,
    val motions: Map<String, List<String>>,
    val expressions: Map<String, String> = emptyMap()
)
