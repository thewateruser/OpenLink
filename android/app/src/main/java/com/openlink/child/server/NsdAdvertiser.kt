package com.openlink.child.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises `_openlink._tcp` over mDNS so the parent app's Bonjour browse finds this device on
 * the LAN without anybody typing an address.
 *
 * Only the LAN case needs this; away-from-home reachability comes from the overlay address the
 * parent learned via `GET /device`. mDNS does not cross a subnet, and nothing here pretends
 * otherwise.
 *
 * The TXT record carries the device id and certificate fingerprint. The fingerprint is public by
 * construction -- it is a hash of a certificate the device presents to anyone who connects -- and
 * publishing it lets a parent that already paired confirm it is about to talk to the right device
 * before opening a socket. The single-use pairing `psk` is emphatically *not* advertised; it only
 * ever exists in the QR on screen.
 */
class NsdAdvertiser(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var registeredName: String? = null
        private set

    fun register(port: Int, deviceId: String, deviceName: String, fingerprintBase64Url: String) {
        val manager = nsdManager ?: run {
            Log.w(TAG, "No NSD service available; LAN discovery disabled")
            return
        }
        unregister()

        val serviceInfo = NsdServiceInfo().apply {
            // mDNS instance names are capped at 63 bytes and the system may rename on collision.
            serviceName = "OpenLink ${deviceName.take(40)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            setAttribute("id", deviceId)
            setAttribute("fp", fingerprintBase64Url)
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // The system hands back the name it actually used, which may have a numeric
                // suffix if another OpenLink device is already advertising.
                registeredName = info.serviceName
                Log.i(TAG, "Advertising $SERVICE_TYPE on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed (code $errorCode); LAN discovery unavailable")
                registeredName = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registeredName = null
            }
        }

        listener = registrationListener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            // Registration is best-effort: a device with mDNS blocked still works over a typed
            // or learned address.
            Log.w(TAG, "Could not register NSD service: ${e.javaClass.simpleName}")
            listener = null
        }
    }

    fun unregister() {
        val manager = nsdManager ?: return
        val current = listener ?: return
        listener = null
        try {
            manager.unregisterService(current)
        } catch (e: IllegalArgumentException) {
            // Already unregistered, or never successfully registered.
        }
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
        const val SERVICE_TYPE = "_openlink._tcp."
    }
}
