package com.openlink.child.network

import okhttp3.Interceptor
import okhttp3.Response

/** Adds `X-Device-Token: <token>` to every request, per docs/API.md's device-token auth model. */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) {
                addHeader("X-Device-Token", token)
            }
        }.build()
        return chain.proceed(request)
    }
}
