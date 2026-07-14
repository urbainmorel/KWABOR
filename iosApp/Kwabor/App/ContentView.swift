import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge
    @State private var selectedDestination = RootDestination.home

    var body: some View {
        TabView(selection: $selectedDestination) {
            ForEach(RootDestination.allCases) { destination in
                NavigationStack {
                    RootDestinationContent(destination: destination, bridge: bridge)
                }
                .tabItem {
                    Label(destination.label(using: bridge), systemImage: destination.systemImage)
                }
                .tag(destination)
            }
        }
        .onOpenURL { url in
            guard let routeKey = bridge.rootDestinationKeyForDeepLink(rawUrl: url.absoluteString),
                  let destination = RootDestination(rawValue: routeKey) else {
                return
            }
            selectedDestination = destination
        }
    }
}

private struct RootDestinationContent: View {
    let destination: RootDestination
    let bridge: KwaborSharedBridge

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Text(bridge.appName())
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.secondary)

            Text(title)
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(.primary)

            Text(bridge.foundationStatus())
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.paper50)
        .navigationTitle(destination.label(using: bridge))
    }

    private var title: String {
        destination == .home ? bridge.homeTitle() : destination.label(using: bridge)
    }
}

#Preview {
    ContentView(bridge: KwaborSharedBridge())
}
