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
        container = c
        _mesh = StateObject(wrappedValue: MeshService(container: c))
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
    static var openConversation: ((String) -> Void)?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if let c = response.notification.request.content.userInfo["conversation"] as? String {
            await MainActor.run { Self.openConversation?(c) }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
