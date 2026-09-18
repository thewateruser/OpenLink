package com.openlink.child

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlink.child.server.ServerState
import com.openlink.child.service.MonitorForegroundService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the app actually starts on a device, which compiling does not.
 *
 * This exists because of a real bug that shipped: `startForeground()` was called with
 * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on every API level from 29 up, but that constant and the
 * manifest's `specialUse` value both arrived in API 34. On Android 10 through 13 the platform
 * rejected the type and threw, killing the service inside `onCreate` the instant the user tapped
 * Continue -- so the app "did nothing" and the pairing QR was unreachable. Every CI check passed,
 * because all of them only ever compiled the code.
 *
 * The emulator matrix in CI therefore includes an API level inside that 29..33 window as well as
 * a modern one. A test that only ran on API 34 would have been just as blind as the compiler.
 */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The regression test proper: bring the foreground service up and require that it survives
     * and binds a listener. On the broken build this failed on API 30 with the service dead.
     */
    @Test
    fun foregroundServiceStartsAndBindsAListener() {
        // Starting a foreground service from the background is restricted on Android 12+, so put
        // the app in the foreground the way a user would before asking for the service.
        launchActivityAndWait()

        MonitorForegroundService.start(context)

        val snapshot = awaitServerOutcome(timeoutMillis = 60_000)

        assertNull(
            "The listener reported an error instead of binding: ${snapshot.lastError}",
            snapshot.lastError
        )
        assertNotNull(
            "The listener never bound a port. If the service died in onCreate this is where it " +
                "shows up -- check logcat for MonitorService.",
            snapshot.port
        )
    }

    /**
     * The listener is useless if nothing can reach it, and an address list that comes back empty
     * on a networked device means endpoint learning (and therefore pairing) has nothing to put in
     * the QR.
     */
    @Test
    fun boundListenerReportsAtLeastOneEndpoint() {
        launchActivityAndWait()
        MonitorForegroundService.start(context)

        val snapshot = awaitServerOutcome(timeoutMillis = 60_000)
        assertNotNull("The listener never bound a port", snapshot.port)

        // An emulator always has at least its NAT address, so an empty list here is a real fault
        // in DeviceInfo.endpoints() rather than an environment quirk.
        assert(snapshot.endpoints.isNotEmpty()) {
            "A bound listener reported no reachable endpoints, so a pairing QR would be unusable"
        }
    }

    private fun launchActivityAndWait() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(instrumentation.targetContext.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.targetContext.startActivity(intent)
        instrumentation.waitForIdleSync()
        Thread.sleep(2_000)
    }

    /** Polls until the listener either binds or reports a failure, rather than sleeping blind. */
    private fun awaitServerOutcome(timeoutMillis: Long): ServerState.Snapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var snapshot = ServerState.snapshot.value
        while (System.currentTimeMillis() < deadline &&
            snapshot.port == null &&
            snapshot.lastError == null
        ) {
            Thread.sleep(250)
            snapshot = ServerState.snapshot.value
        }
        return snapshot
    }
}
