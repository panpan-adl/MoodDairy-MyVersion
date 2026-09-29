package com.example.mydiary.data.repository

import com.example.mydiary.data.models.MediaUploadResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.util.CacheManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File

/**
 * MediaRepository 单元测试
 * 
 * 验证需求: 4.3, 5.5, 6.3
 */
class MediaRepositoryTest {
    
    private lateinit var apiService: DiaryApiService
    private lateinit var cacheManager: CacheManager
    private lateinit var repository: MediaRepository
    
    @Before
    fun setup() {
        apiService = mockk()
        cacheManager = mockk(relaxed = true) // relaxed = true 表示所有方法都有默认返回值
        repository = MediaRepository(apiService, cacheManager)
    }
    
    @Test
    fun `validateFile should return null for valid image file`() {
        // 创建临时测试文件
        val testFile = File.createTempFile("test_image", ".jpg")
        testFile.writeText("fake image content")
        
        try {
            val error = repository.validateFile(testFile, "image")
            assertNull("Valid file should pass validation", error)
        } finally {
            testFile.delete()
        }
    }
    
    @Test
    fun `validateFile should return error for non-existent file`() {
        val nonExistentFile = File("/path/to/nonexistent/file.jpg")
        
        val error = repository.validateFile(nonExistentFile, "image")
        
        assertNotNull("Non-existent file should fail validation", error)
        assertEquals("文件不存在", error)
    }
    
    @Test
    fun `validateFile should return error for invalid extension`() {
        val testFile = File.createTempFile("test_file", ".txt")
        testFile.writeText("text content")
        
        try {
            val error = repository.validateFile(testFile, "image")
            
            assertNotNull("Invalid extension should fail validation", error)
            assertTrue("Error should mention unsupported format", 
                error?.contains("不支持的文件格式") == true)
        } finally {
            testFile.delete()
        }
    }
    
    @Test
    fun `uploadMedia should emit Success on successful upload`() = runTest {
        // 准备测试数据
        val testFile = File.createTempFile("test_image", ".jpg")
        testFile.writeText("fake image content")
        
        val mockResponse = MediaUploadResponse(
            mediaId = 123L,
            mediaUrl = "http://example.com/media/123.jpg",
            thumbnailUrl = null,
            fileSize = testFile.length(),
            message = "Upload successful"
        )
        
        coEvery { 
            apiService.uploadMedia(any(), any(), any()) 
        } returns Response.success(mockResponse)
        
        try {
            // 执行上传
            val results = repository.uploadMedia(testFile, 1L, "image").toList()
            
            // 验证结果 - 现在只 emit 一次最终结果
            assertEquals(1, results.size)
            assertTrue("Result should be Success", results[0] is ApiResult.Success)
            
            val successResult = results[0] as ApiResult.Success
            assertEquals(123L, successResult.data.mediaId)
            assertEquals("http://example.com/media/123.jpg", successResult.data.mediaUrl)
        } finally {
            testFile.delete()
        }
    }
    
    @Test
    fun `uploadMedia should emit Error on failed upload`() = runTest {
        val testFile = File.createTempFile("test_image", ".jpg")
        testFile.writeText("fake image content")
        
        coEvery { 
            apiService.uploadMedia(any(), any(), any()) 
        } returns Response.error(500, "Server error".toResponseBody())
        
        try {
            val results = repository.uploadMedia(testFile, 1L, "image").toList()
            
            // 验证结果 - 现在只 emit 一次最终结果
            assertEquals(1, results.size)
            assertTrue("Result should be Error", results[0] is ApiResult.Error)
        } finally {
            testFile.delete()
        }
    }
    
    @Test
    fun `uploadMedia should handle different media types correctly`() {
        val testFile = File.createTempFile("test", ".mp3")
        testFile.writeText("fake audio content")
        
        try {
            // 测试音频类型
            val audioError = repository.validateFile(testFile, "audio")
            assertNull("Valid audio file should pass", audioError)
            
            // 测试视频类型（虽然扩展名不匹配，但测试逻辑）
            val videoFile = File.createTempFile("test", ".mp4")
            videoFile.writeText("fake video content")
            try {
                val videoError = repository.validateFile(videoFile, "video")
                assertNull("Valid video file should pass", videoError)
            } finally {
                videoFile.delete()
            }
        } finally {
            testFile.delete()
        }
    }
}
