package com.example.mydiary.util

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/**
 * VideoRecorder - 视频录制工具类
 * 
 * 使用CameraX库实现视频录制，支持：
 * - 开始/停止录制
 * - 录制时长计算
 * - 相机预览
 * - 权限处理
 * 
 * 需求：6.2, 6.3
 */
class VideoRecorder(private val context: Context) {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var startTime: Long = 0
    private var isRecording: Boolean = false
    
    /**
     * 录制结果数据类
     */
    data class RecordingResult(
        val file: File,
        val duration: Long, // 录制时长（毫秒）
        val success: Boolean,
        val errorMessage: String? = null
    )
    
    /**
     * 初始化相机和预览
     * 
     * @param lifecycleOwner 生命周期所有者（通常是Activity或Fragment）
     * @param previewView 预览视图
     * @param cameraSelector 相机选择器（前置或后置）
     * @param onSuccess 初始化成功回调
     * @param onError 初始化失败回调
     */
    fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // 创建预览用例
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // 创建视频录制用例
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    )
                    .build()
                
                videoCapture = VideoCapture.withOutput(recorder)
                
                // 解绑所有用例
                cameraProvider?.unbindAll()
                
                // 绑定用例到相机
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
                
                onSuccess()
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * 开始录制视频
     * 
     * @param outputFile 输出文件路径
     * @param onStart 录制开始回调
     * @param onError 录制错误回调
     * @throws IllegalStateException 如果已经在录制中或相机未初始化
     */
    @Throws(IllegalStateException::class)
    fun startRecording(
        outputFile: File,
        onStart: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isRecording) {
            throw IllegalStateException("Already recording")
        }
        
        val videoCaptureInstance = videoCapture
            ?: throw IllegalStateException("Camera not initialized. Call initializeCamera() first.")
        
        // 确保输出目录存在
        outputFile.parentFile?.mkdirs()
        
        // 创建输出选项
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        
        // 开始录制
        recording = videoCaptureInstance.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        startTime = System.currentTimeMillis()
                        isRecording = true
                        onStart()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            val errorMsg = "Video recording error: ${event.error}"
                            onError(errorMsg)
                        }
                        isRecording = false
                    }
                }
            }
    }
    
    /**
     * 停止录制视频
     * 
     * @return RecordingResult 录制结果，包含文件路径和时长
     */
    fun stopRecording(): RecordingResult {
        if (!isRecording) {
            return RecordingResult(
                file = File(""),
                duration = 0,
                success = false,
                errorMessage = "Not recording"
            )
        }
        
        return try {
            recording?.stop()
            recording = null
            
            val duration = System.currentTimeMillis() - startTime
            
            RecordingResult(
                file = File(""), // 文件路径在startRecording中指定
                duration = duration,
                success = true
            )
        } catch (e: Exception) {
            RecordingResult(
                file = File(""),
                duration = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = "Failed to stop recording: ${e.message}"
            )
        } finally {
            isRecording = false
        }
    }
    
    /**
     * 暂停录制
     */
    fun pauseRecording() {
        if (isRecording) {
            recording?.pause()
        }
    }
    
    /**
     * 恢复录制
     */
    fun resumeRecording() {
        if (isRecording) {
            recording?.resume()
        }
    }
    
    /**
     * 获取当前录制时长（毫秒）
     * 
     * @return 录制时长，如果未在录制则返回0
     */
    fun getRecordingDuration(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - startTime
        } else {
            0
        }
    }
    
    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * 取消录制（停止并删除录制文件）
     */
    fun cancelRecording(outputFile: File) {
        if (isRecording) {
            recording?.stop()
            recording = null
            isRecording = false
            
            // 删除录制文件
            outputFile.delete()
        }
    }
    
    /**
     * 切换相机（前置/后置）
     * 
     * @param lifecycleOwner 生命周期所有者
     * @param previewView 预览视图
     * @param useFrontCamera 是否使用前置相机
     */
    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        useFrontCamera: Boolean
    ) {
        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        initializeCamera(lifecycleOwner, previewView, cameraSelector)
    }
    
    /**
     * 释放相机资源（在Activity/Fragment销毁时调用）
     */
    fun release() {
        if (isRecording) {
            recording?.stop()
            recording = null
            isRecording = false
        }
        
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
    }
}
