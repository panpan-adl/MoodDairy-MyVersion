package com.example.mydiary.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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

        // FastAPI/Pydantic 校验错误项（含 msg + loc），转为友好中文提示
        if (obj.has("msg") && obj.has("loc")) {
            return formatValidationError(obj)
        }

        val error = obj.get("error")
        val message = obj.get("message")

        return firstNonBlank(
            detail?.let(::extractBestMessage),
            message?.let(::extractBestMessage),
            error?.let(::extractBestMessage),
            element.toString(),
        )
    }

    /**
     * 将 Pydantic 校验错误转为友好中文，如 "密码至少需要 6 个字符"
     */
    private fun formatValidationError(obj: com.google.gson.JsonObject): String {
        val field = obj.getAsJsonArray("loc")
            ?.lastOrNull()
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            .orEmpty()
        val fieldName = when (field) {
            "username" -> "用户名"
            "password" -> "密码"
            "nickname" -> "昵称"
            "phone" -> "手机号"
            "email" -> "邮箱"
            "birthday" -> "生日"
            else -> field
        }
        val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val ctx = obj.getAsJsonObject("ctx")
        val reason = when (type) {
            "string_too_short" -> {
                val min = ctx?.get("min_length")?.takeIf { it.isJsonPrimitive }?.asInt
                if (min != null) "至少需要 $min 个字符" else "长度不足"
            }
            "string_too_long" -> {
                val max = ctx?.get("max_length")?.takeIf { it.isJsonPrimitive }?.asInt
                if (max != null) "不能超过 $max 个字符" else "长度超限"
            }
            "missing" -> "不能为空"
            "value_error" -> "格式不正确"
            else -> obj.get("msg")?.takeIf { it.isJsonPrimitive }?.asString ?: "输入不合法"
        }
        return if (fieldName.isBlank()) reason else "$fieldName$reason"
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
