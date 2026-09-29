package com.example.mydiary.whitenoise

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.PlaybackException
import java.util.concurrent.ConcurrentHashMap

/**
 * 白噪音管理器
 * 负责管理白噪音的播放、暂停、音量控制等
 */
class WhiteNoiseManager private constructor() {
    
    companion object {
        private const val TAG = "WhiteNoiseManager"
        private const val DEFAULT_VOLUME = 0.5f
        
        @Volatile
        private var instance: WhiteNoiseManager? = null
        
        fun getInstance(): WhiteNoiseManager {
            return instance ?: synchronized(this) {
                instance ?: WhiteNoiseManager().also { instance = it }
            }
        }
    }
    
    // 播放器映射
    private val players = ConcurrentHashMap<String, ExoPlayer>()
    
    // 播放状态
    private val playingStates = ConcurrentHashMap<String, Boolean>()
    
    // 音量设置
    private val volumeSettings = ConcurrentHashMap<String, Float>()
    
    /**
     * 播放白噪音
     */
    fun playSound(context: Context, sound: WhiteNoiseSound) {
        try {
            if (isPlaying(sound.id)) {
                Log.d(TAG, "${sound.name} 已在播放中")
                return
            }
            
            // 初始化播放器
            if (players[sound.id] == null) {
                val player = ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    addListener(createPlayerListener(sound.id))
                }
                players[sound.id] = player
            }
            
            val player = players[sound.id] ?: return
            
            // 设置媒体源
            val uri = Uri.parse("android.resource://${context.packageName}/${sound.resourceId}")
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            
            // 设置音量和播放
            player.volume = volumeSettings[sound.id] ?: DEFAULT_VOLUME
            player.prepare()
            player.playWhenReady = true
            playingStates[sound.id] = true
            
            Log.d(TAG, "${sound.name} 开始播放")
        } catch (e: Exception) {
            Log.e(TAG, "播放 ${sound.name} 失败: ${e.message}")
            playingStates[sound.id] = false
        }
    }
    
    /**
     * 暂停白噪音
     */
    fun pauseSound(soundId: String) {
        try {
            players[soundId]?.pause()
            playingStates[soundId] = false
            Log.d(TAG, "暂停声音: $soundId")
        } catch (e: Exception) {
            Log.e(TAG, "暂停声音失败: ${e.message}")
        }
    }
    
    /**
     * 停止所有白噪音
     */
    fun stopAllSounds() {
        try {
            players.forEach { (soundId, player) ->
                player.stop()
                playingStates[soundId] = false
            }
            Log.d(TAG, "已停止所有声音")
        } catch (e: Exception) {
            Log.e(TAG, "停止所有声音失败: ${e.message}")
        }
    }
    
    /**
     * 检查是否正在播放
     */
    fun isPlaying(soundId: String): Boolean {
        return playingStates[soundId] == true
    }
    
    /**
     * 设置音量
     */
    fun setVolume(soundId: String, volume: Float) {
        val coercedVolume = volume.coerceIn(0f, 1f)
        volumeSettings[soundId] = coercedVolume
        players[soundId]?.volume = coercedVolume
    }
    
    /**
     * 获取音量
     */
    fun getVolume(soundId: String): Float {
        return volumeSettings[soundId] ?: DEFAULT_VOLUME
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            players.forEach { (_, player) ->
                player.stop()
                player.release()
            }
            players.clear()
            playingStates.clear()
            Log.d(TAG, "已释放所有资源")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败: ${e.message}")
        }
    }
    
    /**
     * 创建播放器监听器
     */
    private fun createPlayerListener(soundId: String): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        val player = players[soundId]
                        if (player?.playWhenReady == true) {
                            playingStates[soundId] = true
                        }
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        playingStates[soundId] = false
                    }
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingStates[soundId] = isPlaying
            }
            
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "$soundId 播放错误: ${error.message}")
                playingStates[soundId] = false
            }
        }
    }
}

