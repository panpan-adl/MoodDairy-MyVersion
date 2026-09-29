package com.example.mydiary.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件缓存管理器
 * 
 * 职责：
 * - 管理媒体文件的本地缓存
 * - 提供缓存读写接口
 * - 自动清理过期缓存
 * 
 * 性能优化: 任务31 - 实现文件缓存
 */
@Singleton
class CacheManager @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val CACHE_DIR_NAME = "media_cache"
        private const val MAX_CACHE_SIZE_MB = 100L // 最大缓存大小100MB
        private const val CACHE_EXPIRY_DAYS = 7L // 缓存过期时间7天
    }
    
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * 获取缓存文件
     * 
     * @param url 文件URL
     * @return 缓存的文件，如果不存在则返回null
     */
    fun getCachedFile(url: String): File? {
        val cacheKey = generateCacheKey(url)
        val cachedFile = File(cacheDir, cacheKey)
        
        return if (cachedFile.exists() && !isExpired(cachedFile)) {
            // 更新访问时间
            cachedFile.setLastModified(System.currentTimeMillis())
            cachedFile
        } else {
            null
        }
    }
    
    /**
     * 下载并缓存文件
     * 
     * @param url 文件URL
     * @return 缓存的文件
     */
    suspend fun downloadAndCache(url: String): File = withContext(Dispatchers.IO) {
        // 检查是否已缓存
        getCachedFile(url)?.let { return@withContext it }
        
        // 下载文件
        val cacheKey = generateCacheKey(url)
        val cachedFile = File(cacheDir, cacheKey)
        
        try {
            URL(url).openStream().use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // 检查缓存大小，必要时清理
            checkAndCleanCache()
            
            cachedFile
        } catch (e: Exception) {
            // 下载失败，删除部分文件
            if (cachedFile.exists()) {
                cachedFile.delete()
            }
            throw e
        }
    }
    
    /**
     * 缓存图片（压缩版）
     * 
     * @param url 图片URL
     * @param bitmap 图片位图
     * @param quality 压缩质量(0-100)
     * @return 缓存的文件
     */
    suspend fun cacheImage(
        url: String,
        bitmap: Bitmap,
        quality: Int = 85
    ): File = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(url)
        val cachedFile = File(cacheDir, cacheKey)
        
        FileOutputStream(cachedFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }
        
        // 检查缓存大小
        checkAndCleanCache()
        
        cachedFile
    }
    
    /**
     * 获取缓存的图片
     * 
     * @param url 图片URL
     * @return 图片位图，如果不存在则返回null
     */
    fun getCachedImage(url: String): Bitmap? {
        val cachedFile = getCachedFile(url) ?: return null
        
        return try {
            BitmapFactory.decodeFile(cachedFile.absolutePath)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 删除指定URL的缓存
     * 
     * @param url 文件URL
     * @return 是否删除成功
     */
    fun deleteCachedFile(url: String): Boolean {
        val cacheKey = generateCacheKey(url)
        val cachedFile = File(cacheDir, cacheKey)
        
        return if (cachedFile.exists()) {
            cachedFile.delete()
        } else {
            false
        }
    }
    
    /**
     * 清空所有缓存
     */
    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { file ->
            file.delete()
        }
    }
    
    /**
     * 获取缓存大小（字节）
     * 
     * @return 缓存总大小
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * 获取缓存大小（MB）
     * 
     * @return 缓存总大小（MB）
     */
    fun getCacheSizeMB(): Double {
        return getCacheSize() / (1024.0 * 1024.0)
    }
    
    /**
     * 检查并清理缓存
     * 
     * 如果缓存超过最大大小，删除最旧的文件
     */
    private fun checkAndCleanCache() {
        val cacheSize = getCacheSize()
        val maxCacheSize = MAX_CACHE_SIZE_MB * 1024 * 1024
        
        if (cacheSize > maxCacheSize) {
            // 按最后修改时间排序
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            
            var currentSize = cacheSize
            for (file in files) {
                if (currentSize <= maxCacheSize * 0.8) { // 清理到80%
                    break
                }
                
                currentSize -= file.length()
                file.delete()
            }
        }
        
        // 清理过期文件
        cleanExpiredCache()
    }
    
    /**
     * 清理过期缓存
     */
    private fun cleanExpiredCache() {
        val expiryTime = System.currentTimeMillis() - (CACHE_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
        
        cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < expiryTime) {
                file.delete()
            }
        }
    }
    
    /**
     * 检查文件是否过期
     * 
     * @param file 文件
     * @return 是否过期
     */
    private fun isExpired(file: File): Boolean {
        val expiryTime = System.currentTimeMillis() - (CACHE_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
        return file.lastModified() < expiryTime
    }
    
    /**
     * 生成缓存键（URL的MD5哈希）
     * 
     * @param url 文件URL
     * @return 缓存键
     */
    private fun generateCacheKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
