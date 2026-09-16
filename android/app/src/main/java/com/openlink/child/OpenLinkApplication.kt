package com.openlink.child

import android.app.Application

/**
 * No custom initialization is required: Room, the secure preferences, the TLS identity and the
 * embedded server all build themselves lazily from an application Context the first time they are
 * needed, and the foreground service owns the listener's lifecycle.
 *
 * This class exists mainly as a documented extension point (crash reporting, WorkManager
 * configuration, etc. would be wired up here in a production build).
 */
class OpenLinkApplication : Application()
