package com.openlink.child.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

/**
 * Thin wrapper around io.socket:socket.io-client for the device:<deviceId> room events
 * documented in docs/API.md ("Realtime" section): `policy:update`, `lock:update`,
 * `request:decision`. Callbacks hand back the raw JSON text so the caller can decode it with
 * kotlinx.serialization using the same DTOs as REST responses.
 */
class SocketManager {

    private var socket: Socket? = null

    var onPolicyUpdate: ((String) -> Unit)? = null
    var onLockUpdate: ((String) -> Unit)? = null
    var onRequestDecision: ((String) -> Unit)? = null
    var onConnectionChange: ((connected: Boolean) -> Unit)? = null

    fun isConnected(): Boolean = socket?.connected() == true

    /**
     * @param serverUrl scheme+host+port only (e.g. "https://home.example.com"); the socket path
     *   itself ("/socket.io") is set via IO.Options, matching docs/API.md.
     */
    fun connect(serverUrl: String, deviceToken: String) {
        disconnect()
        try {
            val options = IO.Options().apply {
                path = "/socket.io"
                reconnection = true
                reconnectionDelay = 2000
                forceNew = true
                // docs/API.md: "pass the same JWT or device token as a `token` field in the
                // Socket.IO `auth` payload on connect".
                auth = mapOf("token" to deviceToken)
            }

            val socketInstance = IO.socket(URI.create(serverUrl), options)

            socketInstance.on(Socket.EVENT_CONNECT) { onConnectionChange?.invoke(true) }
            socketInstance.on(Socket.EVENT_DISCONNECT) { onConnectionChange?.invoke(false) }
            socketInstance.on(Socket.EVENT_CONNECT_ERROR) { onConnectionChange?.invoke(false) }

            socketInstance.on("policy:update") { args -> emit(args, onPolicyUpdate) }
            socketInstance.on("lock:update") { args -> emit(args, onLockUpdate) }
            socketInstance.on("request:decision") { args -> emit(args, onRequestDecision) }

            socketInstance.connect()
            socket = socketInstance
        } catch (e: Exception) {
            Log.e(TAG, "Socket connect failed", e)
        }
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
    }

    private fun emit(args: Array<Any>, callback: ((String) -> Unit)?) {
        val payload = args.getOrNull(0) as? JSONObject ?: return
        callback?.invoke(payload.toString())
    }

    companion object {
        private const val TAG = "SocketManager"
    }
}
