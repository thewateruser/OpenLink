package com.openlink.child.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OpenLink is self-hosted, so the server host is only known at runtime (entered by the user
 * during onboarding / editable later in Settings), not at build time. Retrofit still needs a
 * base URL up front, so [NetworkModule] gives it a fixed placeholder
 * ("http://localhost/api/") and this interceptor rewrites just the scheme/host/port of every
 * outgoing request to the user-configured server, keeping the "/api/..." path Retrofit already
 * built from the placeholder base.
 */
class DynamicBaseUrlInterceptor(private val serverUrlProvider: () -> String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val configured = serverUrlProvider().trim().trimEnd('/')
        val configuredHttpUrl = configured.toHttpUrlOrNull()
            // No/invalid server URL configured yet -- let the request go out against the
            // placeholder host, which fails fast with a clear network error instead of hanging.
            ?: return chain.proceed(original)

        val newUrl = original.url.newBuilder()
            .scheme(configuredHttpUrl.scheme)
            .host(configuredHttpUrl.host)
            .port(configuredHttpUrl.port)
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
