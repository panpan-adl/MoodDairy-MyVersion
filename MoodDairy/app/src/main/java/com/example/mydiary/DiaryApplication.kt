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
        // 应用初始化逻辑
    }
}
