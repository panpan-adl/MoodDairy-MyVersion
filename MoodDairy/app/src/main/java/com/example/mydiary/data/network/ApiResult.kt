package com.example.mydiary.data.network

/**
 * API结果封装类
 * 用于统一处理API调用的成功和失败情况
 */
sealed class ApiResult<out T> {
    /**
     * 成功结果
     */
    data class Success<T>(val data: T) : ApiResult<T>()
    
    /**
     * 错误结果
     */
    data class Error(
        val code: Int,
        val message: String,
        val exception: Exception? = null
    ) : ApiResult<Nothing>()
    
    /**
     * 加载中状态
     */
    object Loading : ApiResult<Nothing>()
}

/**
 * 扩展函数：判断是否成功
 */
fun <T> ApiResult<T>.isSuccess(): Boolean = this is ApiResult.Success

/**
 * 扩展函数：判断是否失败
 */
fun <T> ApiResult<T>.isError(): Boolean = this is ApiResult.Error

/**
 * 扩展函数：获取数据（如果成功）
 */
fun <T> ApiResult<T>.getOrNull(): T? {
    return when (this) {
        is ApiResult.Success -> data
        else -> null
    }
}

/**
 * 扩展函数：获取错误信息（如果失败）
 */
fun <T> ApiResult<T>.getErrorMessage(): String? {
    return when (this) {
        is ApiResult.Error -> message
        else -> null
    }
}
