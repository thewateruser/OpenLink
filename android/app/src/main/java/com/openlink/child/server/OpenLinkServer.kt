package com.openlink.child.server

import android.content.Context
import android.util.Log
import com.openlink.child.domain.ChildRepository
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.security.TlsIdentity
import io.ktor.server.netty.Netty
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The embedded TLS listener: the thing that makes this device "the server".
 *
 * Owned by MonitorForegroundService so its lifetime is exactly the lifetime of enforcement --
 * there is no state in which the device is enforcing rules but unreachable by the parent, or
 * reachable but not enforcing.
 *
 * Binds `0.0.0.0` so one socket serves the LAN interface and any overlay-network interface
 * (Tailscale/WireGuard) at once, which is what lets docs/PROTOCOL.md have a single code path for
 * "at home" and "away".
 *
 * ENGINE NOTE: Netty, and **not** CIO. This is not a preference -- CIO cannot terminate TLS at
 * all. It throws `UnsupportedOperationException: CIO Engine does not currently support HTTPS`
 * the moment an `sslConnector` tries to start, and since every route here is HTTPS-only that
 * leaves the app with no listener whatsoever.
 *
 * This was originally written against CIO on the stated belief that Ktor 3.2 had added
 * server-side TLS to it. That belief was wrong, and nothing caught it until an emulator actually
 * ran the app: it compiled perfectly and failed at runtime, every time. If you are tempted to
 * move back to CIO because it is lighter, run the instrumented tests first.
 */
class OpenLinkServer(context: Context) {

    private val appContext = context.applicationContext
    private val repository = ChildRepository.getInstance(appContext)
    private val registry = ParentRegistry(appContext)
    private val prefs = SecurePrefs(appContext)
    private val nsd = NsdAdvertiser(appContext)

    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var boundPort: Int? = null
        private set

    /**
     * Starts the listener on the first free port in [DEFAULT_PORT]..[DEFAULT_PORT]+[PORT_ATTEMPTS],
     * advertises it over mDNS, and returns the port actually bound (or null if it could not
     * start at all).
     */
    @Synchronized
    fun start(): Int? {
        if (server != null) return boundPort

        val identity = try {
            TlsIdentity.loadOrCreate()
        } catch (e: Exception) {
            val message = "Could not load the device TLS identity (${e.javaClass.simpleName})"
            Log.e(TAG, message)
            ServerState.onFailed(message)
            return null
        }

        // Why the loop records this: a start() failure that is *not* a port conflict (a TLS
        // misconfiguration, an engine that cannot run here) makes every candidate fail, and the
        // loop then falls through to "no free port" -- a diagnosis that is both wrong and
        // untraceable. Keeping the last real exception means the failure reported to the pairing
        // screen names what actually broke.
        var lastStartFailure: Throwable? = null

        for (candidate in DEFAULT_PORT until DEFAULT_PORT + PORT_ATTEMPTS) {
            if (!isPortAvailable(candidate)) continue

            val deps = ServerDependencies(
                repository = repository,
                registry = registry,
                port = candidate
            )

            val created = try {
                embeddedServer(
                    factory = Netty,
                    configure = {
                        sslConnector(
                            keyStore = identity.keyStore,
                            keyAlias = TlsIdentity.KEY_ALIAS,
                            // An AndroidKeyStore entry has no passphrase: access is mediated by
                            // the keystore itself, and the private key never leaves it.
                            keyStorePassword = { TlsIdentity.emptyPassword() },
                            privateKeyPassword = { TlsIdentity.emptyPassword() }
                        ) {
                            host = LISTEN_HOST
                            port = candidate
                        }
                    },
                    module = { openLinkModule(deps) }
                ).also { it.start(wait = false) }
            } catch (e: Throwable) {
                // Between the availability probe and the bind, something else may have taken
                // the port; try the next one. Anything else is a real fault, so keep it.
                lastStartFailure = e
                Log.w(TAG, "Could not start listener on port $candidate", e)
                null
            }

            if (created != null) {
                server = created
                boundPort = candidate
                prefs.setLastBoundPort(candidate)

                val endpoints = DeviceInfo.endpoints(candidate)
                ServerState.onStarted(candidate, endpoints)
                nsd.register(
                    port = candidate,
                    deviceId = repository.deviceId(),
                    deviceName = repository.deviceName(),
                    fingerprintBase64Url = identity.fingerprintBase64Url
                )
                Log.i(TAG, "Listening on $LISTEN_HOST:$candidate")
                return candidate
            }
        }

        val range = "$DEFAULT_PORT..${DEFAULT_PORT + PORT_ATTEMPTS - 1}"
        val message = lastStartFailure
            ?.let { "The listener could not start: ${it.javaClass.name}: ${it.message}" }
            ?: "No free port in $range"
        Log.e(TAG, message, lastStartFailure)
        ServerState.onFailed(message)
        return null
    }

    @Synchronized
    fun stop() {
        nsd.unregister()
        val current = server ?: run {
            ServerState.onStopped()
            return
        }
        server = null
        boundPort = null
        try {
            current.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping listener: ${e.javaClass.simpleName}")
        }
        ServerState.onStopped()
    }

    /**
     * Probe-then-bind is racy in principle (something could grab the port in between), which is
     * why the caller also treats a bind failure as "try the next port" rather than trusting this.
     */
    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(LISTEN_HOST, port))
            true
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    companion object {
        private const val TAG = "OpenLinkServer"

        const val DEFAULT_PORT = 8765
        private const val PORT_ATTEMPTS = 10
        private const val LISTEN_HOST = "0.0.0.0"
        private const val GRACE_MILLIS = 500L
        private const val TIMEOUT_MILLIS = 2_000L
    }
}
