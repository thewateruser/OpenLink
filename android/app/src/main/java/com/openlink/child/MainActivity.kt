package com.openlink.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.ui.home.HomeScreen
import com.openlink.child.ui.onboarding.OnboardingScreen
import com.openlink.child.ui.permissions.PermissionsScreen
import com.openlink.child.ui.settings.SettingsScreen
import com.openlink.child.ui.theme.OpenLinkTheme

/**
 * Single-Activity host for the four Compose screens (item 8 of the spec): onboarding/pairing,
 * permission-grant flow, home/status, and settings. Also the target the blocking overlay's
 * "Request more time" button launches, carrying [EXTRA_REQUEST_TIME_PACKAGE] so the dialog opens
 * straight to the right app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedPackage = intent?.getStringExtra(EXTRA_REQUEST_TIME_PACKAGE)
        val prefs = SecurePrefs(applicationContext)

        setContent {
            OpenLinkTheme {
                val navController = rememberNavController()
                val startDestination = if (prefs.isPaired()) "permissions" else "onboarding"

                NavHost(navController = navController, startDestination = startDestination) {
                    composable("onboarding") {
                        OnboardingScreen(
                            onPaired = {
                                navController.navigate("permissions") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("permissions") {
                        PermissionsScreen(
                            onAllGrantedContinue = {
                                navController.navigate("home") {
                                    popUpTo("permissions") { inclusive = true }
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
                            onUnpaired = {
                                navController.navigate("onboarding") {
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
