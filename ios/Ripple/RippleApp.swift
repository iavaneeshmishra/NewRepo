import SwiftUI
import SwiftData
import UserNotifications

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
