package com.openlink.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.ui.home.HomeScreen
import com.openlink.child.ui.pairing.PairingScreen
import com.openlink.child.ui.permissions.PermissionsScreen
import com.openlink.child.ui.settings.SettingsScreen
import com.openlink.child.ui.theme.OpenLinkTheme

/**
 * Single-Activity host for the four Compose screens: permissions, pairing, home/status and
 * settings. Also the target the blocking overlay's "Request more time" button launches, carrying
 * [EXTRA_REQUEST_TIME_PACKAGE] so the dialog opens straight to the right app.
 *
 * The order changed with the architecture. It used to be "pair, then grant permissions", because
 * pairing was a call to a server that could happen any time. Now pairing means *showing a QR for
 * a listener that has to already be running*, and that listener lives in the foreground service
 * the permissions screen starts -- so permissions come first.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedPackage = intent?.getStringExtra(EXTRA_REQUEST_TIME_PACKAGE)
        val isPaired = ParentRegistry(applicationContext).isPaired()

        setContent {
            OpenLinkTheme {
                val navController = rememberNavController()
                val startDestination = if (isPaired) "home" else "permissions"

                NavHost(navController = navController, startDestination = startDestination) {
                    composable("permissions") {
                        PermissionsScreen(
                            onAllGrantedContinue = {
                                val next = if (ParentRegistry(applicationContext).isPaired()) {
                                    "home"
                                } else {
                                    "pairing"
                                }
                                navController.navigate(next) {
                                    popUpTo("permissions") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("pairing") {
                        PairingScreen(
                            onPaired = {
                                navController.navigate("home") {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onSkip = {
                                navController.navigate("home") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            initialRequestPackage = requestedPackage,
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onRepair = { navController.navigate("pairing") },
                            onUnpairedAll = {
                                navController.navigate("permissions") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST_TIME_PACKAGE = "extra_request_time_package"
    }
}
