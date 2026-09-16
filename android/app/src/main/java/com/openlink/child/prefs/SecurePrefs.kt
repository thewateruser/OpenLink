package com.openlink.child.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openlink.child.security.Crypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One paired parent app.
 *
 * [tokenHashBase64Url] is SHA-256 of the bearer token, never the token itself: docs/PROTOCOL.md
 * requires that a dump of this device's storage does not yield a credential that can drive the
 * listener. The token is generated once, handed to the parent in the `POST /pair` response, and
 * then forgotten by this device.
 */
@Serializable
data class PairedParent(
    val parentId: String,
    val parentName: String,
    val tokenHashBase64Url: String,
    val pairedAtEpochMillis: Long
)

/**
 * Keystore-backed storage for everything the device needs to remember across restarts that is
 * not policy data (policy data lives in Room).
 *
 * Backed by AndroidX Security's EncryptedSharedPreferences: an AES key generated and held in the
 * Android Keystore encrypts both the keys and the values of this file on disk.
 */
class SecurePrefs(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ---- device identity ----------------------------------------------------------------------

    /**
     * The `deviceId` from docs/PROTOCOL.md: 32 random bytes, base64url, minted on first access
     * and stable for the life of the install. Synchronized because the pairing screen and the
     * foreground service can both be the first to ask.
     */
    @Synchronized
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = Crypto.base64Url(Crypto.randomBytes(32))
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    /** Defaults to the hardware model; the child/parent can rename it from Settings. */
    fun deviceName(): String =
        prefs.getString(KEY_DEVICE_NAME, null) ?: (Build.MODEL ?: "Android device")

    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name.trim().take(64)).apply()
    }

    // ---- paired parents -------------------------------------------------------------------------

    fun parents(): List<PairedParent> {
        val raw = prefs.getString(KEY_PARENTS, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer, raw)
        } catch (e: Exception) {
            // Corrupt or from an incompatible build: treat as unpaired rather than crashing the
            // listener on every request.
            emptyList()
        }
    }

    @Synchronized
    fun addParent(parent: PairedParent) {
        val updated = parents().filterNot { it.parentId == parent.parentId } + parent
        writeParents(updated)
    }

    /** Returns true if a parent with this id existed and was removed. */
    @Synchronized
    fun removeParent(parentId: String): Boolean {
        val current = parents()
        val updated = current.filterNot { it.parentId == parentId }
        if (updated.size == current.size) return false
        writeParents(updated)
        return true
    }

    @Synchronized
    fun clearParents() {
        writeParents(emptyList())
    }

    fun isPaired(): Boolean = parents().isNotEmpty()

    private fun writeParents(parents: List<PairedParent>) {
        prefs.edit().putString(KEY_PARENTS, json.encodeToString(ListSerializer, parents)).apply()
    }

    // ---- listener / lock state ------------------------------------------------------------------

    /** Last port the embedded server actually bound, so the UI can show it before restart. */
    fun lastBoundPort(): Int? = prefs.getInt(KEY_LAST_PORT, 0).takeIf { it > 0 }

    fun setLastBoundPort(port: Int) {
        prefs.edit().putInt(KEY_LAST_PORT, port).apply()
    }

    /**
     * Remote-lock state has to survive a process restart, otherwise force-stopping the app (or
     * simply running out of memory) silently unlocks a locked device.
     */
    fun isLocked(): Boolean = prefs.getBoolean(KEY_IS_LOCKED, false)

    fun setLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOCKED, locked).apply()
    }

    // ---- device admin ---------------------------------------------------------------------------

    fun setDeviceAdminRevokedAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_ADMIN_REVOKED_AT, timestampMs).apply()
    }

    fun getDeviceAdminRevokedAt(): Long = prefs.getLong(KEY_ADMIN_REVOKED_AT, 0L)

    companion object {
        private const val FILE_NAME = "openlink_secure_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PARENTS = "paired_parents"
        private const val KEY_LAST_PORT = "last_bound_port"
        private const val KEY_IS_LOCKED = "is_locked"
        private const val KEY_ADMIN_REVOKED_AT = "device_admin_revoked_at"

        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(
            PairedParent.serializer()
        )
    }
}
