package com.example.mydiary.di

import com.example.mydiary.data.network.ApiEndpoints
import com.example.mydiary.data.network.DiaryApiService
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }

    /** 通过 adb reverse (USB 调试) 访问本机时避免连接复用导致的 unexpected end of stream */
    @Provides
    @Singleton
    @Named("connectionClose")
    fun provideConnectionCloseInterceptor(): Interceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader("Connection", "close")
                .build()
        )
    }

    /**
     * 标准 OkHttpClient：30s 超时，适用于大多数普通 API 调用，
     * 保证失败时快速反馈而不阻塞 UI。
     */
    @Provides
    @Singleton
    @Named("shortTimeout")
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        @Named("connectionClose") connectionCloseInterceptor: Interceptor,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(connectionCloseInterceptor)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 长超时 OkHttpClient：150s 超时，专用于耗时长的视频生成请求。
     * 与后端 Ark 客户端 120s 保持匹配，留 30s 余量。
     */
    @Provides
    @Singleton
    @Named("longTimeout")
    fun provideLongTimeoutOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        @Named("connectionClose") connectionCloseInterceptor: Interceptor,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(150, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(connectionCloseInterceptor)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("shortTimeout")
    fun provideRetrofit(
        @Named("shortTimeout") okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ApiEndpoints.apiBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    @Named("longTimeout")
    fun provideLongTimeoutRetrofit(
        @Named("longTimeout") okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ApiEndpoints.apiBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideDiaryApiService(
        @Named("shortTimeout") retrofit: Retrofit
    ): DiaryApiService {
        return retrofit.create(DiaryApiService::class.java)
    }

    /**
     * 使用长超时 Retrofit 的 DiaryApiService 实例，专用于视频生成等耗时请求。
     */
    @Provides
    @Singleton
    @Named("longTimeout")
    fun provideLongTimeoutDiaryApiService(
        @Named("longTimeout") retrofit: Retrofit
    ): DiaryApiService {
        return retrofit.create(DiaryApiService::class.java)
    }
}
