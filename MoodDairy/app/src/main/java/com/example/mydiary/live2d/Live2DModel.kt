package com.example.mydiary.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.io.IOException
import java.lang.OutOfMemoryError
import android.os.Looper

/**
 * 纹理配置参数
 */
data class TextureConfig(
    val minFilter: Int = GLES20.GL_LINEAR,
    val magFilter: Int = GLES20.GL_LINEAR,
    val wrapS: Int = GLES20.GL_CLAMP_TO_EDGE,
    val wrapT: Int = GLES20.GL_CLAMP_TO_EDGE
) {
    companion object {
        /**
         * 高质量纹理配置（适合静态图像）
         */
        val HIGH_QUALITY = TextureConfig(
            minFilter = GLES20.GL_LINEAR_MIPMAP_LINEAR,
            magFilter = GLES20.GL_LINEAR,
            wrapS = GLES20.GL_CLAMP_TO_EDGE,
            wrapT = GLES20.GL_CLAMP_TO_EDGE
        )

        /**
         * 高性能纹理配置（适合动画）
         */
        val HIGH_PERFORMANCE = TextureConfig(
            minFilter = GLES20.GL_NEAREST,
            magFilter = GLES20.GL_NEAREST,
            wrapS = GLES20.GL_CLAMP_TO_EDGE,
            wrapT = GLES20.GL_CLAMP_TO_EDGE
        )

        /**
         * 默认配置（平衡质量和性能）
         */
        val DEFAULT = TextureConfig()
    }
}

/**
 * 纹理相关异常
 */
sealed class TextureException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class FileNotFoundException(message: String, cause: Throwable? = null) : TextureException(message, cause)
    class DecodeFailedException(message: String, cause: Throwable? = null) : TextureException(message, cause)
    class OutOfMemoryException(message: String, cause: Throwable? = null) : TextureException(message, cause)
    class OpenGLErrorException(message: String, cause: Throwable? = null) : TextureException(message, cause)
}

/**
 * Live2D 模型数据类
 */
data class Live2DModelData(
    val modelPath: String,
    val modelDir: String,
    val config: ModelConfig,
    var isLoaded: Boolean = false
)

/**
 * 纹理管理器
 * 用于加载和管理 OpenGL 纹理
 */
class TextureManager(
    private val context: Context,
    private val textureConfig: TextureConfig = TextureConfig()
) {
    
    companion object {
        private const val TAG = "TextureManager"
    }
    
    // 纹理缓存：存储纹理路径到 OpenGL 纹理 ID 的映射
    private val textures = mutableMapOf<String, Int>()
    
    /**
     * 从 assets 加载纹理
     *
     * @param path 纹理文件路径（相对于 assets）
     * @return OpenGL 纹理 ID，失败返回 0
     */
    fun loadTexture(path: String): Int {
        // 检查缓存
        textures[path]?.let { return it }

        // 检查是否在主线程（GL线程通常是主线程或专门的GL线程）
        // 注意：这只是一个基本检查，实际项目中可能需要更复杂的线程管理
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "纹理加载在主线程中执行，这可能影响UI性能: $path")
        }

        var bitmap: Bitmap? = null
        val textureHandle = IntArray(1)

        return try {
            // 加载并解码位图
            bitmap = loadBitmap(path)

            // 生成纹理
            generateTexture(textureHandle)

            // 配置纹理参数并加载数据
            configureAndLoadTexture(textureHandle[0], bitmap)

            // 缓存纹理ID
            textures[path] = textureHandle[0]
            Log.i(TAG, "纹理加载成功: $path, ID: ${textureHandle[0]}")
            textureHandle[0]

        } catch (e: TextureException) {
            Log.e(TAG, "纹理加载失败: ${e.message}", e)
            cleanupResources(textureHandle, bitmap)
            0
        } catch (e: Exception) {
            Log.e(TAG, "纹理加载出现未知错误: $path", e)
            cleanupResources(textureHandle, bitmap)
            0
        }
    }

    /**
     * 加载位图
     */
    private fun loadBitmap(path: String): Bitmap {
        return try {
            context.assets.open(path).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
                    ?: throw TextureException.DecodeFailedException("位图解码失败: $path")
            }
        } catch (e: IOException) {
            throw TextureException.FileNotFoundException("无法打开纹理文件: $path", e)
        } catch (e: OutOfMemoryError) {
            throw TextureException.OutOfMemoryException("内存不足，无法解码纹理: $path", e)
        }
    }

    /**
     * 生成OpenGL纹理
     */
    private fun generateTexture(textureHandle: IntArray) {
        GLES20.glGenTextures(1, textureHandle, 0)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw TextureException.OpenGLErrorException(
                "生成纹理失败，OpenGL错误: 0x${Integer.toHexString(error)}"
            )
        }

        if (textureHandle[0] == 0) {
            throw TextureException.OpenGLErrorException("纹理句柄生成失败")
        }
    }

    /**
     * 配置纹理参数并加载纹理数据
     */
    private fun configureAndLoadTexture(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // 设置纹理过滤参数
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, textureConfig.minFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, textureConfig.magFilter)

        // 设置纹理包装模式
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, textureConfig.wrapS)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, textureConfig.wrapT)

        // 加载纹理数据
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw TextureException.OpenGLErrorException(
                "加载纹理数据失败，OpenGL错误: 0x${Integer.toHexString(error)}"
            )
        }

        // 释放位图资源
        bitmap.recycle()
    }

    /**
     * 清理资源
     */
    private fun cleanupResources(textureHandle: IntArray, bitmap: Bitmap?) {
        try {
            if (textureHandle[0] != 0) {
                GLES20.glDeleteTextures(1, textureHandle, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理纹理资源时出错", e)
        }

        try {
            bitmap?.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "清理位图资源时出错", e)
        }
    }
    
    /**
     * 检查纹理是否存在
     */
    fun hasTexture(path: String): Boolean = textures.containsKey(path)

    /**
     * 获取纹理ID（如果不存在返回null）
     */
    fun getTextureId(path: String): Int? = textures[path]

    /**
     * 获取已加载的纹理数量
     */
    fun getTextureCount(): Int = textures.size

    /**
     * 获取所有已加载的纹理路径
     */
    fun getLoadedTextures(): Set<String> = textures.keys.toSet()

    /**
     * 删除指定的纹理
     */
    fun removeTexture(path: String) {
        textures[path]?.let { textureId ->
            try {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textures.remove(path)
                Log.i(TAG, "纹理已删除: $path, ID: $textureId")
            } catch (e: Exception) {
                Log.w(TAG, "删除纹理时出错: $path", e)
            }
        }
    }

    /**
     * 释放所有纹理
     */
    fun release() {
        val textureIds = textures.values.toIntArray()
        if (textureIds.isNotEmpty()) {
            try {
                GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
                Log.i(TAG, "已释放 ${textureIds.size} 个纹理")
            } catch (e: Exception) {
                Log.w(TAG, "释放纹理时出错", e)
            }
        }
        textures.clear()
    }

    /**
     * 获取调试信息
     */
    fun getDebugInfo(): String {
        return """
            纹理管理器状态:
            已加载纹理数量: ${textures.size}
            纹理列表: ${textures.entries.joinToString { "${it.key}=${it.value}" }}
            配置: minFilter=${textureConfig.minFilter}, magFilter=${textureConfig.magFilter}
        """.trimIndent()
    }

    /**
     * 验证纹理有效性
     */
    fun validateTexture(path: String): Boolean {
        val textureId = textures[path] ?: return false

        // 绑定纹理检查是否有效
        val currentTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, currentTexture, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val isValid = GLES20.glGetError() == GLES20.GL_NO_ERROR

        // 恢复之前的纹理绑定
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture[0])

        return isValid
    }

    /**
     * 清理无效纹理
     */
    fun cleanupInvalidTextures() {
        val invalidTextures = textures.filterNot { (path, _) ->
            validateTexture(path)
        }

        invalidTextures.forEach { (path, textureId) ->
            Log.w(TAG, "清理无效纹理: $path, ID: $textureId")
            textures.remove(path)
        }

        if (invalidTextures.isNotEmpty()) {
            Log.i(TAG, "已清理 ${invalidTextures.size} 个无效纹理")
        }
    }
}
