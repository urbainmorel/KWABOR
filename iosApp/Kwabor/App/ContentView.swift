import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Text(bridge.appName())
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.secondary)

            Text(bridge.homeTitle())
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(.primary)

            Text(bridge.foundationStatus())
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(.secondary)

            Text("Sponsorisé")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .padding(.horizontal, KwaborDesignTokens.Spacing.md)
                .padding(.vertical, KwaborDesignTokens.Spacing.xs)
                .background(KwaborDesignTokens.ColorToken.sponsored)
                .clipShape(Capsule())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.paper50)
    }
}

#Preview {
    ContentView(bridge: KwaborSharedBridge())
}
