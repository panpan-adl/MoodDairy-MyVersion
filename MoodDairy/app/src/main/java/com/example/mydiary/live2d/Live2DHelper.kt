package com.example.mydiary.live2d

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Live2D 辅助工具类
 */
object Live2DHelper {
    
    private const val TAG = "Live2DHelper"
    
    /**
     * 从 assets 读取文件内容 - 支持多种编码
     */
    fun readAssetFile(context: Context, path: String, charset: Charset = Charsets.UTF_8): String? {
        return try {
            context.assets.open(path).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, charset)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "读取文件失败 (IO错误): $path", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取文件失败 (未知错误): $path", e)
            null
        }
    }

    /**
     * 解析 model3.json 文件
     */
    fun parseModel3Json(jsonContent: String?): ModelConfig? {
        if (jsonContent.isNullOrBlank()) {
            Log.e(TAG, "JSON 内容为空")
            return null
        }

        return try {
            val json = JSONObject(jsonContent)

            // 检查必需的顶层结构
            if (!json.has("FileReferences")) {
                Log.e(TAG, "JSON 缺少必需的 FileReferences 字段")
                return null
            }

            val fileRefs = json.getJSONObject("FileReferences")

            // 验证必需字段（optString 第二参数为 String，不可为 null）
            val mocFile = fileRefs.optString("Moc", "").takeIf { it.isNotBlank() }
            if (mocFile == null) {
                Log.e(TAG, "JSON 缺少必需的 Moc 字段")
                return null
            }

            if (!fileRefs.has("Textures")) {
                Log.e(TAG, "JSON 缺少必需的 Textures 字段")
                return null
            }

            val texturesArray = fileRefs.optJSONArray("Textures")
            if (texturesArray == null || texturesArray.length() == 0) {
                Log.e(TAG, "Textures 字段为空或无效")
                return null
            }

            val textures = parseStringArraySafe(texturesArray)
            if (textures.isEmpty()) {
                Log.e(TAG, "无法解析有效的纹理文件列表")
                return null
            }

            // Expressions 可能是数组（标准格式）或对象（非标准格式），需要分别处理
            val expressionsMap = when {
                fileRefs.has("Expressions") && fileRefs.opt("Expressions") is JSONArray -> {
                    parseExpressionsArraySafe(fileRefs.optJSONArray("Expressions"))
                }
                fileRefs.has("Expressions") && fileRefs.opt("Expressions") is JSONObject -> {
                    parseExpressionsObjectSafe(fileRefs.optJSONObject("Expressions"))
                }
                else -> emptyMap()
            }

            ModelConfig(
                mocFile = mocFile,
                textures = textures,
                physics = fileRefs.optString("Physics", "").takeIf { it.isNotBlank() },
                motions = parseMotionsSafe(fileRefs.optJSONObject("Motions")),
                expressions = expressionsMap
            )
        } catch (e: JSONException) {
            Log.e(TAG, "JSON 格式错误: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "解析 model3.json 时发生未知错误", e)
            null
        }
    }

    fun getModelDirectory(modelPath: String): String {
        val lastSlashIndex = modelPath.lastIndexOf("/")
        return if (lastSlashIndex >= 0) {
            modelPath.substring(0, lastSlashIndex)
        } else {
            // 如果没有 "/"，返回空字符串或当前目录
            ""
        }
    }
    
    fun buildAssetPath(modelDir: String, fileName: String): String {
        return if (modelDir.isBlank()) {
            fileName
        } else {
            "$modelDir/$fileName"
        }
    }
    
    /**
     * 安全解析字符串数组 - 优化性能版
     */
    private fun parseStringArraySafe(array: JSONArray): List<String> {
        return try {
            (0 until array.length()).mapNotNull { index ->
                try {
                    array.optString(index, "").takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.w(TAG, "跳过无效的数组元素 at index $index", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析字符串数组失败", e)
            emptyList()
        }
    }
    
    /**
     * 安全解析Motions字段 - 带异常处理和格式验证
     */
    private fun parseMotionsSafe(motionsObj: JSONObject?): Map<String, List<String>> {
        if (motionsObj == null) return emptyMap()

        val motions = mutableMapOf<String, List<String>>()
        try {
            motionsObj.keys().forEach { key ->
                try {
                    val motionArray = motionsObj.optJSONArray(key)
                    if (motionArray == null) {
                        Log.w(TAG, "跳过无效的motion组: $key")
                        return@forEach
                    }

                    val motionFiles = (0 until motionArray.length()).mapNotNull { index ->
                        try {
                            val motionObj = motionArray.optJSONObject(index)
                            motionObj?.optString("File", "")?.takeIf { it.isNotBlank() }
                        } catch (e: Exception) {
                            Log.w(TAG, "跳过无效的motion对象 at $key[$index]", e)
                            null
                        }
                    }

                    if (motionFiles.isNotEmpty()) {
                        motions[key] = motionFiles
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析motion组失败: $key", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析motions字段时发生错误", e)
        }
        return motions
    }
    
    /**
     * 安全解析Expressions数组格式（标准Live2D格式）
     * 格式: [{ "Name": "f01", "File": "expressions/f01.exp3.json" }]
     */
    private fun parseExpressionsArraySafe(expressionsArray: JSONArray?): Map<String, String> {
        if (expressionsArray == null) return emptyMap()

        val expressions = mutableMapOf<String, String>()
        try {
            for (i in 0 until expressionsArray.length()) {
                try {
                    val expressionObj = expressionsArray.optJSONObject(i) ?: continue
                    val name = expressionObj.optString("Name", "").takeIf { it.isNotBlank() }
                    val file = expressionObj.optString("File", "").takeIf { it.isNotBlank() }
                    if (name != null && file != null) {
                        expressions[name] = file
                    } else {
                        Log.w(TAG, "跳过无效的expression对象 at index $i: name=$name, file=$file")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析expression对象失败 at index $i", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析expressions数组时发生错误", e)
        }
        return expressions
    }

    /**
     * 安全解析Expressions对象格式（非标准格式，兼容旧配置）
     * 格式: { "f01": "expressions/f01.exp3.json" }
     */
    private fun parseExpressionsObjectSafe(expressionsObj: JSONObject?): Map<String, String> {
        if (expressionsObj == null) return emptyMap()

        val expressions = mutableMapOf<String, String>()
        try {
            expressionsObj.keys().forEach { key ->
                try {
                    val expressionFile = expressionsObj.optString(key, "").takeIf { it.isNotBlank() }
                    if (expressionFile != null) {
                        expressions[key] = expressionFile
                    } else {
                        Log.w(TAG, "跳过空的expression文件: $key")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析expression失败: $key", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析expressions对象时发生错误", e)
        }
        return expressions
    }
}
