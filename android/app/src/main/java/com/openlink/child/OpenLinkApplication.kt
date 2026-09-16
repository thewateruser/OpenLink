package com.openlink.child

import android.app.Application

/**
 * No custom initialization is required yet -- every subsystem (Room, Retrofit, the socket
 * client) builds itself lazily from an application Context the first time it's needed. This
 * class exists mainly as a documented extension point (crash reporting, WorkManager
 * configuration, etc. would be wired up here in a production build).
 */
class OpenLinkApplication : Application()
