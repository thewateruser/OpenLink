package com.openlink.child.server

import android.content.Context
import android.util.Log
import com.openlink.child.domain.ChildRepository
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.security.TlsIdentity
import io.ktor.server.cio.CIO
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
 * ENGINE NOTE: this uses Ktor's CIO engine, which only gained server-side TLS in Ktor 3.2 --
 * hence the version floor in app/build.gradle.kts. If a future Ktor makes that untrue, the
 * fallback is one line: swap `ktor-server-cio` for `ktor-server-netty` and `CIO` for `Netty`
 * below. The `sslConnector` configuration is identical across engines. See android/README.md,
 * which is honest about the fact that none of this has been run.
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

        for (candidate in DEFAULT_PORT until DEFAULT_PORT + PORT_ATTEMPTS) {
            if (!isPortAvailable(candidate)) continue

            val deps = ServerDependencies(
                repository = repository,
                registry = registry,
                port = candidate
            )

            val created = try {
                embeddedServer(
                    factory = CIO,
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
                // the port; try the next one.
                Log.w(TAG, "Could not bind port $candidate: ${e.javaClass.simpleName}")
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

        val message = "No free port in $DEFAULT_PORT..${DEFAULT_PORT + PORT_ATTEMPTS - 1}"
        Log.e(TAG, message)
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
