package com.playdeca.jmedia.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.playdeca.jmedia.data.api.JMusicApiService
import com.playdeca.jmedia.data.api.JMusicWebSocketManager
import com.playdeca.jmedia.data.repository.JMusicRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost:8080")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideJMusicApiService(retrofit: Retrofit): JMusicApiService {
        return retrofit.create(JMusicApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideWebSocketManager(
        okHttpClient: OkHttpClient
    ): JMusicWebSocketManager {
        return JMusicWebSocketManager(
            okHttpClient = okHttpClient,
            baseUrl = "http://localhost:8080"
        )
    }
}

// ExoPlayer will be created directly in AudioPlayerService
// since MediaSessionService doesn't work with Hilt @AndroidEntryPoint

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideJMusicRepository(
        apiService: JMusicApiService
    ): JMusicRepository {
        return JMusicRepository(apiService)
    }
}