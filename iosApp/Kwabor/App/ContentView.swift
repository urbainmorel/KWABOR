import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(bridge.appName())
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.secondary)

            Text(bridge.homeTitle())
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(.primary)

            Text(bridge.foundationStatus())
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        .padding(24)
        .background(Color(.systemBackground))
    }
}

#Preview {
    ContentView(bridge: KwaborSharedBridge())
}
