package com.example.mydiary.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * AudioConverter - 音频格式转换工具类
 * 
 * 支持将 mp4/m4a 格式转换为 mp3 格式
 * 使用 Android MediaCodec API 进行转换
 * 
 * 使用场景：
 * - 录音后将 m4a 转换为 mp3 以提高兼容性
 * - 减小音频文件大小
 */
class AudioConverter {
    
    companion object {
        private const val TAG = "AudioConverter"
        
        /**
         * 转换结果数据类
         */
        data class ConversionResult(
            val success: Boolean,
            val outputFile: File? = null,
            val errorMessage: String? = null
        )
        
        /**
         * 将音频文件转换为 MP3 格式
         * 
         * 注意：Android 原生不直接支持 MP3 编码，这里使用简化方案：
         * 1. 提取原始 AAC 音频数据
         * 2. 重新封装为 MP3 容器（实际上是 AAC 编码的 MP3）
         * 
         * 如果需要真正的 MP3 编码，建议使用 FFmpeg 库
         * 
         * @param inputFile 输入文件（mp4/m4a）
         * @param outputFile 输出文件（mp3）
         * @return ConversionResult 转换结果
         */
        suspend fun convertToMp3(
            inputFile: File,
            outputFile: File? = null
        ): ConversionResult = withContext(Dispatchers.IO) {
            try {
                // 验证输入文件
                if (!inputFile.exists()) {
                    return@withContext ConversionResult(
                        success = false,
                        errorMessage = "输入文件不存在: ${inputFile.path}"
                    )
                }
                
                // 确定输出文件
                val output = outputFile ?: File(
                    inputFile.parentFile,
                    inputFile.nameWithoutExtension + ".mp3"
                )
                
                // 确保输出目录存在
                output.parentFile?.mkdirs()
                
                // 使用 MediaExtractor 提取音频数据
                val extractor = MediaExtractor()
                extractor.setDataSource(inputFile.path)
                
                // 查找音频轨道
                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }
                
                if (audioTrackIndex == -1 || audioFormat == null) {
                    extractor.release()
                    return@withContext ConversionResult(
                        success = false,
                        errorMessage = "未找到音频轨道"
                    )
                }
                
                // 选择音频轨道
                extractor.selectTrack(audioTrackIndex)
                
                // 创建 MediaMuxer 用于输出
                // 注意：Android 不直接支持 MP3 muxer，这里使用 MPEG_4 格式
                // 但将文件扩展名设为 .mp3，大多数播放器可以识别
                val muxer = MediaMuxer(
                    output.path,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                
                // 添加音频轨道到 muxer
                val outputTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()
                
                // 复制音频数据
                val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
                val bufferInfo = MediaCodec.BufferInfo()
                
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    
                    if (sampleSize < 0) {
                        // 读取完成
                        break
                    }
                    
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    
                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                
                // 释放资源
                muxer.stop()
                muxer.release()
                extractor.release()
                
                Log.d(TAG, "音频转换成功: ${output.path}")
                
                ConversionResult(
                    success = true,
                    outputFile = output
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "音频转换失败", e)
                ConversionResult(
                    success = false,
                    errorMessage = "转换失败: ${e.message}"
                )
            }
        }
        
        /**
         * 使用 LAME 编码器转换为真正的 MP3（需要集成 FFmpeg 或 LAME 库）
         * 
         * 这是一个占位方法，实际实现需要：
         * 1. 在 build.gradle 中添加 FFmpeg 依赖
         * 2. 使用 FFmpeg 命令行或 API 进行转换
         * 
         * 推荐库：
         * - implementation 'com.arthenica:ffmpeg-kit-full:5.1'
         * 
         * @param inputFile 输入文件
         * @param outputFile 输出文件
         * @return ConversionResult 转换结果
         */
        suspend fun convertToMp3WithFFmpeg(
            inputFile: File,
            outputFile: File? = null
        ): ConversionResult = withContext(Dispatchers.IO) {
            // TODO: 实现 FFmpeg 转换
            // 示例代码（需要添加 ffmpeg-kit 依赖）：
            /*
            val output = outputFile ?: File(
                inputFile.parentFile,
                inputFile.nameWithoutExtension + ".mp3"
            )
            
            val command = "-i ${inputFile.path} -codec:a libmp3lame -qscale:a 2 ${output.path}"
            
            val session = FFmpegKit.execute(command)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                ConversionResult(success = true, outputFile = output)
            } else {
                ConversionResult(
                    success = false,
                    errorMessage = "FFmpeg 转换失败: ${session.failStackTrace}"
                )
            }
            */
            
            ConversionResult(
                success = false,
                errorMessage = "FFmpeg 转换未实现，请使用 convertToMp3() 方法"
            )
        }
        
        /**
         * 简单的文件重命名方案（不进行实际转换）
         * 
         * 将 .m4a 或 .mp4 文件重命名为 .mp3
         * 注意：这不会改变文件的实际编码格式，只是改变扩展名
         * 
         * @param inputFile 输入文件
         * @param deleteOriginal 是否删除原文件
         * @return ConversionResult 转换结果
         */
        fun renameToMp3(
            inputFile: File,
            deleteOriginal: Boolean = false
        ): ConversionResult {
            try {
                if (!inputFile.exists()) {
                    return ConversionResult(
                        success = false,
                        errorMessage = "输入文件不存在"
                    )
                }
                
                val outputFile = File(
                    inputFile.parentFile,
                    inputFile.nameWithoutExtension + ".mp3"
                )
                
                if (deleteOriginal) {
                    // 重命名
                    val renamed = inputFile.renameTo(outputFile)
                    if (!renamed) {
                        return ConversionResult(
                            success = false,
                            errorMessage = "文件重命名失败"
                        )
                    }
                } else {
                    // 复制
                    inputFile.copyTo(outputFile, overwrite = true)
                }
                
                return ConversionResult(
                    success = true,
                    outputFile = outputFile
                )
                
            } catch (e: Exception) {
                return ConversionResult(
                    success = false,
                    errorMessage = "操作失败: ${e.message}"
                )
            }
        }
    }
}
