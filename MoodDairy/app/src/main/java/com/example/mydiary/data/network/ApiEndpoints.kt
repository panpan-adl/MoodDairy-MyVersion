package com.example.mydiary.data.network

import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.mydiary.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ApiEndpoints {
    private const val TAG = "ApiEndpoints"
    private const val EMULATOR_HOST = "10.0.2.2"
    private const val DEVICE_LOOPBACK_HOST = "127.0.0.1"
    private const val FALLBACK_API = "http://10.0.2.2:8000/"

    val apiBaseUrl: String by lazy { resolveApiBaseUrl() }
    val mediaBaseUrl: String by lazy { resolveMediaBaseUrl() }

    /**
     * 修正误将端口写成 `%3A`、双重编码 `%253A`、或从全局 Gradle/IDE 传入的异常编码。
     * 仍非法时 Retrofit 会崩溃，故最后经 OkHttp 校验，失败则回退到模拟器默认地址。
     */
    private fun sanitizeBaseUrl(url: String): String {
        var s = url.trim().removeSurrounding("\"")
        try {
            s = URLDecoder.decode(s, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            // 保持原样继续替换
        }
        return s
            .replace("%253A", ":").replace("%253a", ":")
            .replace("%3A", ":").replace("%3a", ":")
    }

    /** Retrofit 要求 baseUrl 可被 [okhttp3.HttpUrl] 解析且通常以 / 结尾 */
    private fun validateRetrofitBaseUrlOrFallback(resolved: String): String {
        val withSlash = ensureTrailingSlash(resolved)
        return if (withSlash.toHttpUrlOrNull() != null) {
            withSlash
        } else {
            Log.w(
                TAG,
                "API_BASE_URL 仍无法解析，已回退 $FALLBACK_API。请检查 local.properties / 全局 gradle 中 api.base.url 是否为 http://主机:端口/ 形式（勿使用 %3A）。当前原始值=${BuildConfig.API_BASE_URL}",
            )
            FALLBACK_API
        }
    }

    private fun resolveApiBaseUrl(): String {
        val cleaned = sanitizeBaseUrl(BuildConfig.API_BASE_URL)
        val afterRewrite = if (!shouldRewriteToDeviceLoopback()) {
            rewriteLoopbackToEmulatorHost(cleaned)
        } else {
            rewriteEmulatorHost(cleaned)
        }
        val configured = ensureTrailingSlash(afterRewrite)
        return validateRetrofitBaseUrlOrFallback(configured)
    }

    private fun resolveMediaBaseUrl(): String {
        val cleaned = sanitizeBaseUrl(BuildConfig.MEDIA_BASE_URL)
        val configured = if (!shouldRewriteToDeviceLoopback()) rewriteLoopbackToEmulatorHost(cleaned)
        else rewriteEmulatorHost(cleaned)
        val trimmed = trimTrailingSlash(configured)
        return if (trimmed.toHttpUrlOrNull() != null) {
            trimmed
        } else {
            trimTrailingSlash(FALLBACK_API)
        }
    }

    private fun shouldRewriteToDeviceLoopback(): Boolean {
        // 模拟器：保留 10.0.2.2（指向电脑本机）。
        // 真机：默认 BuildConfig 若是 10.0.2.2 则必须改为 127.0.0.1，并配合 adb reverse，否则必现连接超时。
        // 不依赖 DEBUG：否则 Release 包在真机上仍会错误地使用 10.0.2.2。
        return !isProbablyEmulator()
    }

    private fun rewriteEmulatorHost(url: String): String {
        val uri = Uri.parse(url)
        if (uri.host != EMULATOR_HOST) return url

        val authority = if (uri.port == -1) {
            DEVICE_LOOPBACK_HOST
        } else {
            "$DEVICE_LOOPBACK_HOST:${uri.port}"
        }
        return uri.buildUpon().authority(authority).build().toString()
    }

    /** 模拟器侧反向映射：local.properties 固化 127.0.0.1 后，模拟器需换回 10.0.2.2 才能访问宿主机 */
    private fun rewriteLoopbackToEmulatorHost(url: String): String {
        val uri = Uri.parse(url)
        if (uri.host != DEVICE_LOOPBACK_HOST) return url

        val authority = if (uri.port == -1) {
            EMULATOR_HOST
        } else {
            "$EMULATOR_HOST:${uri.port}"
        }
        return uri.buildUpon().authority(authority).build().toString()
    }

    private fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    private fun trimTrailingSlash(url: String): String = url.trimEnd('/')

    @Suppress("DEPRECATION")
    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val brand = Build.BRAND
        val device = Build.DEVICE
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE

        return fingerprint.startsWith("generic")
                || fingerprint.lowercase().contains("emulator")
                || model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || brand.startsWith("generic") && device.startsWith("generic")
                || product.contains("sdk_gphone")
                || product.contains("sdk")
                || hardware.contains("ranchu")
                || hardware.contains("goldfish")
    }
}
