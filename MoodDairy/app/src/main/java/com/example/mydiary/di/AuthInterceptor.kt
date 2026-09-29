package com.example.mydiary.di

import com.example.mydiary.data.local.SessionManager
import okhttp3.Interceptor
import javax.inject.Inject

/**
 * Attaches JWT Bearer token to API requests when the user is logged in.
 */
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = sessionManager.getAccessToken()
        val request = if (!token.isNullOrBlank()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
