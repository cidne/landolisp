package com.landolisp.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.landolisp.BuildConfig
import com.landolisp.data.model.LandolispJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Singleton holder for the [SandboxApi] Retrofit binding.
 *
 * Eval timeouts on the server are hard-capped at 30 s, so the OkHttp read timeout matches.
 */
object SandboxClient {

    private const val MEDIA_TYPE_JSON = "application/json"

    val api: SandboxApi by lazy { build(BuildConfig.SANDBOX_BASE_URL) }

    fun build(baseUrl: String): SandboxApi {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val okHttp = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val converter = LandolispJson.asConverterFactory(MEDIA_TYPE_JSON.toMediaType())

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
            .client(okHttp)
            .addConverterFactory(converter)
            .build()
            .create(SandboxApi::class.java)
    }
}
