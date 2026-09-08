import SwiftUI
import SwiftData
import UserNotifications

/// Minimal Objective-C exception handoff for the next Diagnostics export.
/// It never reads messages or identity material; values are bounded and long
/// hex/base64-like tokens are redacted before touching UserDefaults.
enum CrashLog {
    private static let pendingKey = "ripple.pendingException"

    static func install() {
        NSSetUncaughtExceptionHandler(rippleUncaughtException)
    }

    static func takePending() -> String? {
        let defaults = UserDefaults.standard
        let value = defaults.string(forKey: pendingKey)
        defaults.removeObject(forKey: pendingKey)
        return value
    }

    fileprivate static func record(_ exception: NSException) {
        let name = String(exception.name.rawValue.prefix(128))
        let rawReason = exception.reason ?? "reason unavailable"
        let oneLine = rawReason.replacingOccurrences(of: "[\\r\\n\\t]+", with: " ", options: .regularExpression)
        let withoutHex = oneLine.replacingOccurrences(of: "(?i)\\b[0-9a-f]{16,}\\b", with: "<redacted>", options: .regularExpression)
        let sanitized = withoutHex.replacingOccurrences(of: "\\b[A-Za-z0-9+/_-]{32,}={0,2}\\b", with: "<redacted>", options: .regularExpression)
        UserDefaults.standard.set("name=\(name); reason=\(sanitized.prefix(512))", forKey: pendingKey)
        UserDefaults.standard.synchronize()
    }
}

private func rippleUncaughtException(_ exception: NSException) {
    CrashLog.record(exception)
}

@main
struct RippleApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    private let container: ModelContainer
    @StateObject private var mesh: MeshService

    init() {
        let c = Persistence.container()
        self.container = c
        self._mesh = StateObject(wrappedValue: MeshService(container: c))
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(mesh)
        }
        .modelContainer(container)
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Notification taps can arrive before HomeView installs its navigation handler.
    static var pendingConversation: String?
    static var openConversation: ((String) -> Void)? {
        didSet {
            guard let handler = openConversation, let pending = pendingConversation else { return }
            pendingConversation = nil
            handler(pending)
        }
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        CrashLog.install()
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if let conversation = response.notification.request.content.userInfo["conversation"] as? String {
            await MainActor.run {
                if let handler = Self.openConversation {
                    handler(conversation)
                } else {
                    Self.pendingConversation = conversation
                }
            }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
