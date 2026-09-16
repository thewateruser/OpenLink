//
//  LocalNotificationManager.swift
//  OpenLink (parent app)
//
//  LOCAL notifications only. There is deliberately no APNs path here.
//
//  docs/PROTOCOL.md, "Deliberate limitations": waking a closed iOS app needs
//  APNs, which needs a provider server holding Apple-issued credentials.
//  OpenLink has no server, and shipping those credentials inside the child
//  app would be a serious vulnerability. So:
//
//    - App closed  -> no notification is possible. The parent sees pending
//                     requests the next time they open the app.
//    - Backgrounded with the WebSocket still alive -> a `request:new` event
//                     raises a local notification, which is what this class
//                     does.
//    - Foreground  -> the UI updates itself; no notification.
//

import Foundation
import UserNotifications
import UIKit

@MainActor
final class LocalNotificationManager: ObservableObject {
    static let shared = LocalNotificationManager()

    @Published private(set) var isAuthorized = false

    private var hasAsked = false

    private init() {}

    /// Ask once, after the first device is paired — asking on first launch,
    /// before the app has done anything, is the classic way to get denied.
    func requestAuthorizationIfNeeded() async {
        guard !hasAsked else { return }
        hasAsked = true
        do {
            let granted = try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
            isAuthorized = granted
        } catch {
            isAuthorized = false
        }
    }

    func refreshAuthorizationStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        isAuthorized = settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
    }

    /// Raise a notification for a new time request, but only when the app
    /// isn't in front of the user — otherwise the list updates in place and a
    /// banner would just be noise.
    func notifyNewRequest(_ request: TimeRequest, deviceName: String) {
        guard UIApplication.shared.applicationState != .active else { return }
        guard isAuthorized else { return }

        let content = UNMutableNotificationContent()
        content.title = "\(deviceName) asked for more time"
        content.body = "\(request.displayName): \(request.minutesRequested) more minutes"
            + (request.message.map { " — “\($0)”" } ?? "")
        content.sound = .default
        content.userInfo = ["requestId": request.id]

        // `trigger: nil` delivers immediately.
        let notification = UNNotificationRequest(
            identifier: "openlink.request.\(request.id)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(notification, withCompletionHandler: nil)
    }
}
