package com.example.mydiary.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import retrofit2.Response

object SafeApiCall {

    suspend fun <T> execute(
        apiCall: suspend () -> Response<T>,
    ): ApiResult<T> {
        return try {
            val response = apiCall()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    ApiResult.Success(body)
                } else if (response.code() == 204) {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(Unit as T)
                } else {
                    ApiResult.Error(code = response.code(), message = "响应体为空")
                }
            } else {
                ApiResult.Error(
                    code = response.code(),
                    message = parseErrorMessage(response),
                )
            }
        } catch (io: IOException) {
            ApiResult.Error(
                code = -1,
                message = "网络连接失败: ${io.message}",
                exception = io,
            )
        } catch (error: Exception) {
            ApiResult.Error(
                code = -1,
                message = "请求失败: ${error.message}",
                exception = error,
            )
        }
    }

    private fun parseErrorMessage(response: Response<*>): String {
        val errorBody = response.errorBody()?.string()?.trim().orEmpty()
        if (errorBody.isBlank()) {
            return "未知错误"
        }

        return runCatching {
            val root = JsonParser().parse(errorBody)
            extractBestMessage(root)
        }.getOrElse { error ->
            "解析错误响应失败: ${error.message}. 原始响应: ${errorBody.take(240)}"
        }
    }

    private fun extractBestMessage(element: JsonElement?): String {
        if (element == null || element.isJsonNull) {
            return "未知错误"
        }

        if (element.isJsonPrimitive) {
            return element.asString
        }

        if (element.isJsonArray) {
            return extractArrayMessage(element.asJsonArray)
        }

        val obj = element.asJsonObject
        val detail = obj.get("detail")
        val error = obj.get("error")
        val message = obj.get("message")

        return firstNonBlank(
            detail?.let(::extractBestMessage),
            message?.let(::extractBestMessage),
            error?.let(::extractBestMessage),
            element.toString(),
        )
    }

    private fun extractArrayMessage(array: JsonArray): String {
        val messages = array.mapNotNull { child ->
            extractBestMessage(child).takeIf { it.isNotBlank() }
        }
        return if (messages.isEmpty()) "未知错误" else messages.joinToString("; ")
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: "未知错误"
    }
}
