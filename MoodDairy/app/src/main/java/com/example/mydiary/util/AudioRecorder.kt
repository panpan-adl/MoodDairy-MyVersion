package com.example.mydiary.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * AudioRecorder - 音频录制工具类
 * 
 * 使用MediaRecorder录制音频，支持：
 * - 开始/停止录音
 * - 录音时长计算
 * - 权限处理
 * - 自动转换为 MP3 格式
 * 
 * 需求：5.2, 5.3, 5.4, 5.5
 */
class AudioRecorder(
    private val context: Context,
    private val autoConvertToMp3: Boolean = true // 是否自动转换为 MP3
) {
    
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime: Long = 0
    private var isRecording: Boolean = false
    
    companion object {
        private const val TAG = "AudioRecorder"
    }
    
    /**
     * 录音结果数据类
     */
    data class RecordingResult(
        val file: File,
        val duration: Long, // 录音时长（毫秒）
        val success: Boolean,
        val errorMessage: String? = null,
        val isConverted: Boolean = false // 是否已转换为 MP3
    )
    
    /**
     * 开始录音
     * 
     * @param outputFile 输出文件路径
     * @throws IOException 如果录音初始化失败
     * @throws IllegalStateException 如果已经在录音中
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun startRecording(outputFile: File) {
        if (isRecording) {
            throw IllegalStateException("Already recording")
        }
        
        this.outputFile = outputFile
        
        // 确保输出目录存在
        outputFile.parentFile?.mkdirs()
        
        try {
            // 创建MediaRecorder实例
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                // 设置音频源为麦克风
                // 使用 VOICE_RECOGNITION 可能在模拟器上有更好的兼容性
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                
                // 设置输出格式为 MPEG_4（输出 .m4a 文件，后端支持）
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                
                // 设置音频编码器为 AAC（高质量，后端支持）
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                
                // 设置采样率和比特率以获得更好的音质
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                
                // 设置输出文件
                setOutputFile(outputFile.absolutePath)
                
                // 准备录音
                prepare()
                
                // 开始录音
                start()
                
                // 记录开始时间
                startTime = System.currentTimeMillis()
                isRecording = true
            }
        } catch (e: Exception) {
            // 清理资源
            releaseRecorder()
            throw IOException("Failed to start recording: ${e.message}", e)
        }
    }
    
    /**
     * 停止录音
     * 
     * @return RecordingResult 录音结果，包含文件路径和时长
     */
    fun stopRecording(): RecordingResult {
        if (!isRecording) {
            return RecordingResult(
                file = outputFile ?: File(""),
                duration = 0,
                success = false,
                errorMessage = "Not recording"
            )
        }
        
        return try {
            mediaRecorder?.apply {
                stop()
            }
            
            val duration = System.currentTimeMillis() - startTime
            val file = outputFile ?: File("")
            
            RecordingResult(
                file = file,
                duration = duration,
                success = true,
                isConverted = false
            )
        } catch (e: Exception) {
            RecordingResult(
                file = outputFile ?: File(""),
                duration = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = "Failed to stop recording: ${e.message}"
            )
        } finally {
            releaseRecorder()
            isRecording = false
        }
    }
    
    /**
     * 停止录音并转换为 MP3（异步）
     * 
     * 注意：这是一个挂起函数，需要在协程中调用
     * 
     * @return RecordingResult 录音结果，如果启用了自动转换，file 将指向 MP3 文件
     */
    suspend fun stopRecordingAndConvert(): RecordingResult = withContext(Dispatchers.IO) {
        val result = stopRecording()
        
        if (!result.success || !autoConvertToMp3) {
            return@withContext result
        }
        
        try {
            // 转换为 MP3
            Log.d(TAG, "开始转换音频为 MP3: ${result.file.path}")
            val conversionResult = AudioConverter.convertToMp3(result.file)
            
            if (conversionResult.success && conversionResult.outputFile != null) {
                // 删除原始文件
                result.file.delete()
                
                Log.d(TAG, "音频转换成功: ${conversionResult.outputFile.path}")
                
                RecordingResult(
                    file = conversionResult.outputFile,
                    duration = result.duration,
                    success = true,
                    isConverted = true
                )
            } else {
                // 转换失败，返回原始文件
                Log.w(TAG, "音频转换失败: ${conversionResult.errorMessage}")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频转换异常", e)
            // 转换失败，返回原始文件
            result
        }
    }
    
    /**
     * 获取当前录音时长（毫秒）
     * 
     * @return 录音时长，如果未在录音则返回0
     */
    fun getRecordingDuration(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - startTime
        } else {
            0
        }
    }
    
    /**
     * 是否正在录音
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * 取消录音（删除录音文件）
     */
    fun cancelRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                // 忽略停止时的错误
            }
            releaseRecorder()
            isRecording = false
            
            // 删除录音文件
            outputFile?.delete()
            outputFile = null
        }
    }
    
    /**
     * 释放MediaRecorder资源
     */
    private fun releaseRecorder() {
        mediaRecorder?.apply {
            try {
                reset()
                release()
            } catch (e: Exception) {
                // 忽略释放时的错误
            }
        }
        mediaRecorder = null
    }
    
    /**
     * 清理资源（在Activity/Fragment销毁时调用）
     */
    fun release() {
        if (isRecording) {
            stopRecording()
        }
        releaseRecorder()
    }
}
