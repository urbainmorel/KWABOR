import Shared

enum RootDestination: String, CaseIterable, Hashable, Identifiable {
    case home
    case social
    case add
    case notifications
    case profile

    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .home: "safari.fill"
        case .social: "play.circle.fill"
        case .add: "plus.circle.fill"
        case .notifications: "bell.fill"
        case .profile: "person.fill"
        }
    }

    func label(using bridge: KwaborSharedBridge) -> String {
        switch self {
        case .home: bridge.homeLabel()
        case .social: bridge.socialLabel()
        case .add: bridge.addLabel()
        case .notifications: bridge.notificationsLabel()
        case .profile: bridge.profileLabel()
        }
    }
}
