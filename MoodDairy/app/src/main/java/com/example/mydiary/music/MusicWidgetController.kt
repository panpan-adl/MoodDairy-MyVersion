package com.example.mydiary.music

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 情绪关怀中的音乐入口，优先打开网易云音乐，未安装时使用浏览器。 */
@Singleton
class MusicWidgetController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun openRecommendations(@Suppress("UNUSED_PARAMETER") mood: String) {
        val uri = Uri.parse("https://music.163.com/")
        for (targetPackage in listOf("com.netease.cloudmusic", null)) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(targetPackage)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 网易云未安装时继续尝试浏览器。
            } catch (_: SecurityException) {
                // 应用无法启动时继续尝试浏览器。
            }
        }
        Toast.makeText(context, "请安装网易云音乐或浏览器后重试", Toast.LENGTH_LONG).show()
    }
}