import Shared
import SwiftUI

struct VisibleCatalogDetailMedia: Identifiable {
    let sourceIndex: Int
    let media: CatalogDetailMediaUiModel

    var id: Int { sourceIndex }
}

func visibleCatalogDetailMedia(
    _ media: [CatalogDetailMediaUiModel]
) -> [VisibleCatalogDetailMedia] {
    media.enumerated().compactMap { index, item in
        guard ExploreRemoteImageURLPolicy.acceptedURL(item.url) != nil else { return nil }
        return VisibleCatalogDetailMedia(sourceIndex: index, media: item)
    }
}

struct CatalogDetailHero: View {
    let model: CatalogDetailUiModel
    let selectedMedia: CatalogDetailMediaUiModel?
    let strings: CatalogDetailStrings
    let height: CGFloat
    let onDismiss: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var accessibleHeight: CGFloat {
        guard dynamicTypeSize.isAccessibilitySize else { return height }
        return max(height, KwaborDesignTokens.Sizing.detailAccessibilityHeroMinimumHeight)
    }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            KwaborDesignTokens.ColorToken.ink700
            if let selectedMedia {
                ExploreRemoteImage(
                    rawURL: selectedMedia.url,
                    accessibilityLabel: selectedMedia.alt
                )
                .accessibilitySortPriority(3)
            }
            LinearGradient(
                colors: [
                    .clear,
                    KwaborDesignTokens.ColorToken.ink950.opacity(KwaborDesignTokens.Alpha.scrimHigh),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            CatalogDetailHeroTitle(model: model, verifiedLabel: strings.verified)
                .padding(KwaborDesignTokens.Spacing.xxl)
                .accessibilitySortPriority(4)
            VStack {
                HStack {
                    CatalogDetailCloseButton(label: strings.close, action: onDismiss)
                    Spacer()
                }
                Spacer()
            }
            .padding(KwaborDesignTokens.Spacing.lg)
            .accessibilitySortPriority(5)
        }
        .frame(height: accessibleHeight)
        .clipped()
        .accessibilityElement(children: .contain)
    }
}

private struct CatalogDetailHeroTitle: View {
    let model: CatalogDetailUiModel
    let verifiedLabel: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
            Text(model.contextLabel)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink100)
                .lineLimit(2)
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: KwaborDesignTokens.Spacing.sm) {
                    title
                    if model.verified {
                        CatalogDetailVerifiedBadge(label: verifiedLabel)
                    }
                }
                VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
                    title
                    if model.verified {
                        CatalogDetailVerifiedBadge(label: verifiedLabel)
                    }
                }
            }
        }
    }

    private var title: some View {
        Text(model.title)
            .font(.title2.bold())
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct CatalogDetailVerifiedBadge: View {
    let label: String

    var body: some View {
        Label(label, systemImage: "checkmark.circle.fill")
            .font(.caption.weight(.semibold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            .padding(.horizontal, KwaborDesignTokens.Spacing.md)
            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .clipShape(Capsule())
    }
}

struct CatalogDetailGallery: View {
    let media: [VisibleCatalogDetailMedia]
    let selectedSourceIndex: Int
    let selectImageLabel: String
    let onSelect: (Int) -> Void

    var body: some View {
        if media.count > 1 {
            ScrollView(.horizontal) {
                LazyHStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    ForEach(media) { item in
                        let selected = item.sourceIndex == selectedSourceIndex
                        Button {
                            onSelect(item.sourceIndex)
                        } label: {
                            ExploreRemoteImage(rawURL: item.media.url)
                                .frame(
                                    width: KwaborDesignTokens.Sizing.detailGalleryThumbnail,
                                    height: KwaborDesignTokens.Sizing.detailGalleryThumbnail
                                )
                                .clipShape(
                                    RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                                )
                                .overlay {
                                    RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                                        .stroke(
                                            selected
                                                ? KwaborDesignTokens.ColorToken.ink950
                                                : Color.clear,
                                            lineWidth: KwaborDesignTokens.Sizing.hairline
                                        )
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(selectImageLabel) : \(item.media.alt)")
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
                .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
            }
            .scrollIndicators(.hidden)
        }
    }
}

struct CatalogDetailCloseButton: View {
    let label: String
    let action: () -> Void

    @AccessibilityFocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            Image(systemName: "chevron.backward")
                .font(.headline.weight(.bold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                .frame(
                    width: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
                    height: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget
                )
                .background(
                    KwaborDesignTokens.ColorToken.ink950.opacity(
                        KwaborDesignTokens.Alpha.scrimHigh
                    )
                )
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityFocused($isFocused)
        .task {
            await Task.yield()
            isFocused = true
        }
    }
}
