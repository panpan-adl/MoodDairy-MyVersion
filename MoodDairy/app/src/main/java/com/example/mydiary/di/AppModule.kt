package com.example.mydiary.di

import android.content.Context
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.repository.DiaryRepository
import com.example.mydiary.data.repository.EmotionSignalRepository
import com.example.mydiary.data.repository.MediaRepository
import com.example.mydiary.data.repository.VoiceRepository
import com.example.mydiary.util.CacheManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt依赖注入模块
 * 提供应用级别的单例依赖
 * 
 * 注意：网络相关依赖（OkHttp, Retrofit, ApiService）由 NetworkModule 提供
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    /**
     * 提供CacheManager
     * 
     * 性能优化: 任务31 - 文件缓存
     */
    @Provides
    @Singleton
    fun provideCacheManager(
        @ApplicationContext context: Context
    ): CacheManager {
        return CacheManager(context)
    }
    
    /**
     * 提供DiaryRepository
     * 
     * 验证需求: 2.1, 2.2, 2.4, 1.3
     */
    @Provides
    @Singleton
    fun provideDiaryRepository(
        apiService: DiaryApiService
    ): DiaryRepository {
        return DiaryRepository(apiService)
    }
    
    /**
     * 提供MediaRepository
     * 
     * 验证需求: 4.3, 5.5, 6.3
     * 性能优化: 任务31 - 集成文件缓存
     */
    @Provides
    @Singleton
    fun provideMediaRepository(
        apiService: DiaryApiService,
        cacheManager: CacheManager
    ): MediaRepository {
        return MediaRepository(apiService, cacheManager)
    }
    
    /**
     * 提供VoiceRepository
     * 
     * 验证需求: 8.1, 9.1, 10.1, 11.1
     */
    @Provides
    @Singleton
    fun provideVoiceRepository(
        apiService: DiaryApiService
    ): VoiceRepository {
        return VoiceRepository(apiService)
    }

    /**
     * 提供EmotionSignalRepository（摄像头表情信号上报）
     */
    @Provides
    @Singleton
    fun provideEmotionSignalRepository(
        apiService: DiaryApiService
    ): EmotionSignalRepository {
        return EmotionSignalRepository(apiService)
    }
}
