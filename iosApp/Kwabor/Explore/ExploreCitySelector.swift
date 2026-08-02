import Shared
import SwiftUI

struct ExploreCitySelector: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(action: store.requestLocation) {
                        HStack(spacing: KwaborDesignTokens.Spacing.md) {
                            Label(
                                store.strings.exploreUseLocation,
                                systemImage: "location.circle.fill"
                            )
                            Spacer()
                            if store.state.isLocating {
                                ProgressView()
                                    .accessibilityLabel(store.strings.loading)
                            }
                        }
                        .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    }
                    .disabled(store.state.isLocating)

                    if let message = store.state.locationMessage {
                        Label(message, systemImage: "info.circle")
                            .font(.callout)
                            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                            .accessibilityLabel(message)
                    }
                }

                Section {
                    ForEach(store.state.availableCities, id: \.id) { city in
                        Button {
                            store.selectCity(city.id)
                        } label: {
                            HStack(spacing: KwaborDesignTokens.Spacing.md) {
                                Text(city.label)
                                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                                Spacer()
                                if store.state.selectedCityId == city.id {
                                    Image(systemName: "checkmark")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                                }
                            }
                            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(
                            store.state.selectedCityId == city.id ? .isSelected : []
                        )
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(KwaborDesignTokens.ColorToken.paper50)
            .navigationTitle(store.strings.exploreSelectCity)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(store.strings.authCancel, action: store.closeCitySelector)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
