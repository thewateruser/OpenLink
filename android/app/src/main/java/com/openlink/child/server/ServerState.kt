package com.openlink.child.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the listener is currently doing, published for the UI.
 *
 * The pairing and settings screens live in the Activity while the listener lives in the
 * foreground service, so this is the seam between them: the service writes, Compose collects.
 */
object ServerState {

    data class Snapshot(
        val running: Boolean = false,
        val port: Int? = null,
        val endpoints: List<String> = emptyList(),
        val connectedParents: Int = 0,
        /** Set when the listener could not start at all; surfaced verbatim on the settings screen. */
        val lastError: String? = null
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    fun onStarted(port: Int, endpoints: List<String>) {
        _snapshot.update { it.copy(running = true, port = port, endpoints = endpoints, lastError = null) }
    }

    fun onEndpointsChanged(endpoints: List<String>) {
        _snapshot.update { it.copy(endpoints = endpoints) }
    }

    fun onStopped() {
        _snapshot.update { Snapshot() }
    }

    fun onFailed(message: String) {
        _snapshot.update { Snapshot(lastError = message) }
    }

    fun onParentConnected() {
        _snapshot.update { it.copy(connectedParents = it.connectedParents + 1) }
    }

    fun onParentDisconnected() {
        _snapshot.update { it.copy(connectedParents = (it.connectedParents - 1).coerceAtLeast(0)) }
    }
}
