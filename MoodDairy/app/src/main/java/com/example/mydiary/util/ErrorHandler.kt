package com.example.mydiary.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.example.mydiary.data.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 错误处理工具类
 * 提供统一的错误处理和用户提示
 * 
 * 需求：7.3
 */
object ErrorHandler {
    
    /**
     * 错误类型
     */
    enum class ErrorType {
        NETWORK,        // 网络错误
        PERMISSION,     // 权限错误
        FILE,           // 文件错误
        SERVER,         // 服务器错误
        VALIDATION,     // 验证错误
        UNKNOWN         // 未知错误
    }
    
    /**
     * 错误信息
     */
    data class ErrorInfo(
        val type: ErrorType,
        val message: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    )
    
    /**
     * 处理API错误并显示Snackbar
     * 
     * @param error API错误结果
     * @param snackbarHostState Snackbar状态
     * @param scope 协程作用域
     * @param context 上下文（用于权限引导）
     * @param onRetry 重试回调
     */
    fun handleApiError(
        error: ApiResult.Error,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope,
        context: Context? = null,
        onRetry: (() -> Unit)? = null
    ) {
        val errorInfo = parseApiError(error)
        
        showErrorSnackbar(
            errorInfo = errorInfo,
            snackbarHostState = snackbarHostState,
            scope = scope,
            context = context,
            onRetry = onRetry
        )
    }
    
    /**
     * 解析API错误
     * 
     * @param error API错误结果
     * @return 错误信息
     */
    private fun parseApiError(error: ApiResult.Error): ErrorInfo {
        return when {
            // 网络连接错误
            error.exception is UnknownHostException -> {
                ErrorInfo(
                    type = ErrorType.NETWORK,
                    message = "无法连接到服务器，请检查网络连接",
                    actionLabel = "重试"
                )
            }
            error.exception is SocketTimeoutException -> {
                ErrorInfo(
                    type = ErrorType.NETWORK,
                    message = "网络请求超时，请稍后重试",
                    actionLabel = "重试"
                )
            }
            error.exception is IOException -> {
                ErrorInfo(
                    type = ErrorType.NETWORK,
                    message = "网络连接失败: ${error.message}",
                    actionLabel = "重试"
                )
            }
            // HTTP错误码
            error.code == 400 -> {
                ErrorInfo(
                    type = ErrorType.VALIDATION,
                    message = "请求参数错误: ${error.message}"
                )
            }
            error.code == 401 -> {
                ErrorInfo(
                    type = ErrorType.SERVER,
                    message = "未授权，请重新登录"
                )
            }
            error.code == 403 -> {
                ErrorInfo(
                    type = ErrorType.PERMISSION,
                    message = "没有权限执行此操作"
                )
            }
            error.code == 404 -> {
                ErrorInfo(
                    type = ErrorType.SERVER,
                    message = "请求的资源不存在"
                )
            }
            error.code == 500 -> {
                ErrorInfo(
                    type = ErrorType.SERVER,
                    message = "服务器内部错误，请稍后重试",
                    actionLabel = "重试"
                )
            }
            error.code == 503 -> {
                ErrorInfo(
                    type = ErrorType.SERVER,
                    message = "服务暂时不可用，请稍后重试",
                    actionLabel = "重试"
                )
            }
            // 其他错误
            else -> {
                ErrorInfo(
                    type = ErrorType.UNKNOWN,
                    message = error.message ?: "未知错误",
                    actionLabel = if (error.code == -1) "重试" else null
                )
            }
        }
    }
    
    /**
     * 显示错误Snackbar
     * 
     * @param errorInfo 错误信息
     * @param snackbarHostState Snackbar状态
     * @param scope 协程作用域
     * @param context 上下文
     * @param onRetry 重试回调
     */
    fun showErrorSnackbar(
        errorInfo: ErrorInfo,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope,
        context: Context? = null,
        onRetry: (() -> Unit)? = null
    ) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = errorInfo.message,
                actionLabel = errorInfo.actionLabel ?: errorInfo.action?.let { "操作" },
                duration = if (errorInfo.actionLabel != null) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                }
            )
            
            // 处理用户操作
            if (result == SnackbarResult.ActionPerformed) {
                when (errorInfo.type) {
                    ErrorType.NETWORK -> {
                        onRetry?.invoke()
                    }
                    ErrorType.PERMISSION -> {
                        context?.let { openAppSettings(it) }
                    }
                    else -> {
                        errorInfo.action?.invoke() ?: onRetry?.invoke()
                    }
                }
            }
        }
    }
    
    /**
     * 处理权限错误
     * 
     * @param permissionType 权限类型
     * @param snackbarHostState Snackbar状态
     * @param scope 协程作用域
     * @param context 上下文
     */
    fun handlePermissionError(
        permissionType: String,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope,
        context: Context
    ) {
        val message = when (permissionType) {
            "CAMERA" -> "需要相机权限才能录制视频"
            "MICROPHONE" -> "需要麦克风权限才能录制音频"
            "STORAGE" -> "需要存储权限才能保存文件"
            else -> "需要相关权限才能继续"
        }
        
        val errorInfo = ErrorInfo(
            type = ErrorType.PERMISSION,
            message = message,
            actionLabel = "去设置"
        )
        
        showErrorSnackbar(
            errorInfo = errorInfo,
            snackbarHostState = snackbarHostState,
            scope = scope,
            context = context
        )
    }
    
    /**
     * 处理文件错误
     * 
     * @param fileError 文件错误类型
     * @param snackbarHostState Snackbar状态
     * @param scope 协程作用域
     */
    fun handleFileError(
        fileError: FileError,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope
    ) {
        val errorInfo = when (fileError) {
            is FileError.FileTooLarge -> {
                ErrorInfo(
                    type = ErrorType.FILE,
                    message = "文件过大，最大支持 ${fileError.maxSizeMB}MB"
                )
            }
            is FileError.UnsupportedFormat -> {
                ErrorInfo(
                    type = ErrorType.FILE,
                    message = "不支持的文件格式: ${fileError.format}\n支持的格式: ${fileError.supportedFormats.joinToString(", ")}"
                )
            }
            is FileError.FileNotFound -> {
                ErrorInfo(
                    type = ErrorType.FILE,
                    message = "文件不存在或已被删除"
                )
            }
            is FileError.ReadError -> {
                ErrorInfo(
                    type = ErrorType.FILE,
                    message = "无法读取文件: ${fileError.reason}"
                )
            }
            is FileError.WriteError -> {
                ErrorInfo(
                    type = ErrorType.FILE,
                    message = "无法保存文件: ${fileError.reason}"
                )
            }
        }
        
        showErrorSnackbar(
            errorInfo = errorInfo,
            snackbarHostState = snackbarHostState,
            scope = scope
        )
    }
    
    /**
     * 打开应用设置页面
     * 
     * @param context 上下文
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    /**
     * 显示成功消息
     * 
     * @param message 消息内容
     * @param snackbarHostState Snackbar状态
     * @param scope 协程作用域
     */
    fun showSuccess(
        message: String,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope
    ) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }
}

/**
 * 文件错误类型
 */
sealed class FileError {
    data class FileTooLarge(val maxSizeMB: Int) : FileError()
    data class UnsupportedFormat(val format: String, val supportedFormats: List<String>) : FileError()
    object FileNotFound : FileError()
    data class ReadError(val reason: String) : FileError()
    data class WriteError(val reason: String) : FileError()
}
