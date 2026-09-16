//
//  PushNotificationManager.swift
//  OpenLink (parent app)
//
//  Stubs out the APNs registration path: requests notification permission,
//  registers for remote notifications, and captures the resulting device
//  token. It intentionally stops there — see the TODO below.
//

import Foundation
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

@MainActor
final class PushNotificationManager: NSObject {
    static let shared = PushNotificationManager()

    private override init() {
        super.init()
    }

    /// Call once the parent is signed in (e.g. right after `completeAuth`).
    func requestAuthorizationAndRegister() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if let error {
                print("OpenLink: notification authorization error: \(error.localizedDescription)")
                return
            }
            guard granted else { return }
            Task { @MainActor in
                #if canImport(UIKit)
                UIApplication.shared.registerForRemoteNotifications()
                #endif
            }
        }
    }

    /// Called from AppDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:).
    func handleDeviceToken(_ deviceToken: Data) {
        let tokenString = deviceToken.map { String(format: "%02x", $0) }.joined()

        // TODO(push): Wiring real push notifications still needs two things
        // that don't exist yet:
        //   1. An Apple Developer Program account with an APNs key/cert
        //      configured for this app's bundle id (org.openlink.parent) and
        //      push environment (development/production), plus the "Push
        //      Notifications" capability properly provisioned — none of that
        //      is available in this build environment.
        //   2. A server-side endpoint to receive and store this APNs device
        //      token against the signed-in ParentUser (e.g. something like
        //      `POST /devices/push-token { token }`), which is NOT part of
        //      docs/API.md as of this writing, so it is deliberately not
        //      invented/called here.
        // Until both exist, this token is captured but never transmitted.
        // Socket.IO realtime updates (SocketManager) plus the ~30s
        // foreground poll used by the dashboard and requests screens are
        // what keep this MVP's data current in the meantime.
        print("OpenLink: captured APNs device token (not sent to server — see TODO(push)): \(tokenString)")
    }

    /// Called from AppDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:).
    func handleRegistrationFailure(_ error: Error) {
        print("OpenLink: failed to register for remote notifications: \(error.localizedDescription)")
    }
}
