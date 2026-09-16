package com.openlink.child.network

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.openlink.child.prefs.SecurePrefs
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Builds a fresh Retrofit instance bound to the current SecurePrefs values. Cheap enough to
     * call per use-site (interceptors read the live server URL / token on every request anyway,
     * via the lambdas below) and avoids a stale-singleton problem right after pairing or after
     * the server URL is edited in Settings.
     */
    fun buildRetrofit(context: Context): Retrofit {
        val appContext = context.applicationContext
        val prefs = SecurePrefs(appContext)
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

        val client = OkHttpClient.Builder()
            .addInterceptor(DynamicBaseUrlInterceptor { prefs.getServerUrl() ?: "" })
            .addInterceptor(AuthInterceptor { prefs.getDeviceToken() })
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    // Never actually reached over the network -- DynamicBaseUrlInterceptor swaps the
    // scheme/host/port for the user-configured server before each request leaves the device.
    // Keeping "/api/" here means every @GET/@POST path in OpenLinkApi can stay relative
    // ("device/policies", etc.) exactly as docs/API.md writes them under "Base URL: .../api".
    private const val PLACEHOLDER_BASE_URL = "http://localhost/api/"
}
