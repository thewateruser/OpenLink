package com.openlink.child.server

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.openlink.child.util.isoFromEpochMillis
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * The facts about this device that `GET /device` and the `device:state` event report.
 *
 * [endpoints] is the important one. It is the entire mechanism behind docs/PROTOCOL.md's
 * endpoint learning: a parent that paired over home Wi-Fi keeps calling `GET /device`, and the
 * first time it does so while the overlay network is up, the child's Tailscale/WireGuard address
 * appears in this list and the parent persists it. Nobody types a remote address anywhere.
 *
 * That makes correctness here load-bearing in both directions: miss an address and away-from-home
 * silently never works; emit a junk address and every future connection attempt wastes time
 * racing something undialable.
 */
object DeviceInfo {

    const val PLATFORM = "android"

    /**
     * Every address this device can currently be reached on, as `host:port`, best-first.
     *
     * Ordering is LAN-private IPv4, then other IPv4 (which is where an overlay address such as
     * Tailscale's 100.64.0.0/10 lands), then IPv6 -- so a parent racing the list in order tries
     * the lowest-latency path first, as PROTOCOL.md describes.
     *
     * Excluded, deliberately:
     *  - loopback and wildcard addresses: not reachable from another host;
     *  - link-local (169.254.0.0/16 and fe80::/10): only meaningful with a scope id that is
     *    local to *this* device's interface numbering, so they cannot be dialled by a peer and
     *    would only pollute the parent's persisted endpoint list;
     *  - multicast addresses.
     *
     * DIVERGENCE from PROTOCOL.md, which also asks for "its NSD hostname": Android's NsdManager
     * does not expose the hostname it registers a service under, and it may rename the service on
     * a name collision. A fabricated `<name>.local` would be a guess, and a guess in this list is
     * worse than an omission -- mDNS discovery already covers the case a hostname would serve.
     * See android/README.md.
     */
    fun endpoints(port: Int): List<String> {
        val siteLocalV4 = mutableListOf<String>()
        val otherV4 = mutableListOf<String>()
        val v6 = mutableListOf<String>()

        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        for (nif in interfaces) {
            val usable = try {
                nif.isUp && !nif.isLoopback
            } catch (e: Exception) {
                false
            }
            if (!usable) continue

            for (address in nif.inetAddresses) {
                if (address.isLoopbackAddress ||
                    address.isAnyLocalAddress ||
                    address.isLinkLocalAddress ||
                    address.isMulticastAddress
                ) continue

                val raw = address.hostAddress ?: continue
                when (address) {
                    is Inet4Address -> {
                        val endpoint = "$raw:$port"
                        if (address.isSiteLocalAddress) siteLocalV4 += endpoint else otherV4 += endpoint
                    }
                    is Inet6Address -> {
                        // Strip any %scope suffix and bracket the literal, per RFC 3986.
                        v6 += "[${raw.substringBefore('%')}]:$port"
                    }
                    else -> Unit
                }
            }
        }

        return (siteLocalV4 + otherV4 + v6).distinct()
    }

    /** 0-100, or null if the platform won't say (some emulators, some OEM builds). */
    fun batteryLevel(context: Context): Int? = try {
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    } catch (e: Exception) {
        null
    }

    /** Wall-clock time of the last boot, derived from uptime. */
    fun lastBootAtIso(): String =
        isoFromEpochMillis(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
