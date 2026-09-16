package com.openlink.child.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for the device token / pairing identity and the self-hosted server
 * URL. Backed by AndroidX Security's EncryptedSharedPreferences (an AES key generated and held
 * in the Android Keystore encrypts both the file's keys and values on disk).
 *
 * The server URL isn't secret, but keeping everything in one small encrypted file is simpler
 * than splitting "sensitive" (Keystore) vs. "non-sensitive" (DataStore) storage for an MVP with
 * only a handful of values, and it costs nothing in practice.
 */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)
    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun getFamilyId(): String? = prefs.getString(KEY_FAMILY_ID, null)

    fun savePairing(deviceToken: String, deviceId: String, familyId: String) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_FAMILY_ID, familyId)
            .apply()
    }

    fun isPaired(): Boolean = getDeviceToken() != null

    /** Used by "Unpair this device" in Settings. Does not call any server endpoint (there is no
     *  parent-auth session on this device to authorize a DELETE /devices/:deviceId) -- it just
     *  forgets local credentials so the device stops enforcing / heartbeating. The parent must
     *  separately remove the device from their app to fully unpair it server-side. */
    fun clearPairing() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_FAMILY_ID)
            .apply()
    }

    fun setDeviceAdminRevokedAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_ADMIN_REVOKED_AT, timestampMs).apply()
    }

    fun getDeviceAdminRevokedAt(): Long = prefs.getLong(KEY_ADMIN_REVOKED_AT, 0L)

    companion object {
        private const val FILE_NAME = "openlink_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_ADMIN_REVOKED_AT = "device_admin_revoked_at"
    }
}
