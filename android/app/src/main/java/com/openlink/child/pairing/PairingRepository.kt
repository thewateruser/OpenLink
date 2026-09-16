package com.openlink.child.pairing

import android.content.Context
import android.os.Build
import com.openlink.child.network.NetworkModule
import com.openlink.child.network.OpenLinkApi
import com.openlink.child.network.model.PairingClaimRequest
import com.openlink.child.prefs.SecurePrefs

/** Backs the onboarding screen: `POST /pairing/claim`, then persists the result via SecurePrefs. */
class PairingRepository(private val context: Context) {

    private val prefs = SecurePrefs(context)

    suspend fun pair(serverUrl: String, code: String): Result<Unit> = try {
        // The server URL has to be saved before the API call, since NetworkModule/
        // DynamicBaseUrlInterceptor read it from SecurePrefs at request time.
        prefs.setServerUrl(normalizeUrl(serverUrl))

        val api = NetworkModule.buildRetrofit(context).create(OpenLinkApi::class.java)
        val response = api.claimPairing(
            PairingClaimRequest(
                code = code.trim().uppercase(),
                deviceName = Build.MODEL ?: "Android device",
                platform = "android"
            )
        )
        prefs.savePairing(response.deviceToken, response.deviceId, response.familyId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    fun isPaired(): Boolean = prefs.isPaired()
}
