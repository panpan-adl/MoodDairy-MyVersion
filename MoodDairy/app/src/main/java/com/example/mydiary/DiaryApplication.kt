package com.example.mydiary

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用程序入口
 * 使用Hilt进行依赖注入
 */
@HiltAndroidApp
class DiaryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A device token issued by the retired computer service cannot authenticate
        // against the original account server. Keep diaries/drafts, reset only that session.
        val retiredConnection = com.example.mydiary.data.local.SecurePreferences.create(this, "local_computer_secure")
        if (retiredConnection.contains("url")) {
            com.example.mydiary.data.local.SessionManager(this).clearSession()
            retiredConnection.edit().clear().commit()
        }
    }
}
