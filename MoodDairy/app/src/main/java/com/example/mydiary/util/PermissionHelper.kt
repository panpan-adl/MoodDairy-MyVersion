package com.example.mydiary.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * PermissionHelper - 权限请求辅助类
 * 
 * 处理运行时权限请求，特别是麦克风和相机权限
 * 
 * 需求：5.2, 6.2
 */
object PermissionHelper {
    
    /**
     * 检查是否已授予麦克风权限
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查是否已授予相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查是否已授予存储权限（用于读取图片/视频）
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用细粒度媒体权限
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 及以下使用READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 创建麦克风权限请求启动器
     * 
     * 使用示例：
     * ```
     * val micPermissionLauncher = PermissionHelper.createMicrophonePermissionLauncher(
     *     activity = this,
     *     onGranted = { startRecording() },
     *     onDenied = { showPermissionDeniedMessage() }
     * )
     * 
     * // 请求权限
     * micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
     * ```
     */
    fun createMicrophonePermissionLauncher(
        activity: ComponentActivity,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ): ActivityResultLauncher<String> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onGranted()
            } else {
                onDenied()
            }
        }
    }
    
    /**
     * 创建相机权限请求启动器
     */
    fun createCameraPermissionLauncher(
        activity: ComponentActivity,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ): ActivityResultLauncher<String> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onGranted()
            } else {
                onDenied()
            }
        }
    }
    
    /**
     * 创建多个权限请求启动器
     */
    fun createMultiplePermissionsLauncher(
        activity: ComponentActivity,
        onAllGranted: () -> Unit,
        onSomeDenied: (List<String>) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val deniedPermissions = permissions.filter { !it.value }.keys.toList()
            if (deniedPermissions.isEmpty()) {
                onAllGranted()
            } else {
                onSomeDenied(deniedPermissions)
            }
        }
    }
    
    /**
     * 获取存储权限列表（根据Android版本）
     */
    fun getStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
