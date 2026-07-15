import AVFoundation
import Foundation
import SwiftUI

struct IntroVideoPlayer: View {
    @StateObject private var model: IntroVideoPlayerModel

    init(
        url: URL,
        onCompleted: @escaping () -> Void,
        onFailed: @escaping () -> Void
    ) {
        _model = StateObject(
            wrappedValue: IntroVideoPlayerModel(
                url: url,
                onCompleted: onCompleted,
                onFailed: onFailed
            )
        )
    }

    var body: some View {
        PlayerLayerView(player: model.player)
            .onAppear { model.play() }
            .onDisappear { model.pause() }
            .ignoresSafeArea()
    }
}

@MainActor
private final class IntroVideoPlayerModel: ObservableObject {
    let player: AVPlayer
    private let onCompleted: () -> Void
    private let onFailed: () -> Void
    private var completionObserver: NSObjectProtocol?
    private var failureObserver: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var terminalEventDelivered = false

    init(
        url: URL,
        onCompleted: @escaping () -> Void,
        onFailed: @escaping () -> Void
    ) {
        self.onCompleted = onCompleted
        self.onFailed = onFailed
        let item = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.volume = 0
        player.actionAtItemEnd = .pause
        completionObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.deliverCompletion()
            }
        }
        failureObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.deliverFailure()
            }
        }
        statusObservation = item.observe(\.status, options: [.initial, .new]) {
            [weak self, weak item] _, _ in
            guard item?.status == .failed else { return }
            Task { @MainActor [weak self] in
                self?.deliverFailure()
            }
        }
    }

    func play() {
        player.play()
    }

    func pause() {
        player.pause()
    }

    private func deliverCompletion() {
        guard !terminalEventDelivered else { return }
        terminalEventDelivered = true
        onCompleted()
    }

    private func deliverFailure() {
        guard !terminalEventDelivered else { return }
        terminalEventDelivered = true
        player.pause()
        onFailed()
    }

    deinit {
        statusObservation?.invalidate()
        if let completionObserver {
            NotificationCenter.default.removeObserver(completionObserver)
        }
        if let failureObserver {
            NotificationCenter.default.removeObserver(failureObserver)
        }
    }
}

private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerContainerView {
        let view = PlayerContainerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PlayerContainerView, context: Context) {
        uiView.playerLayer.player = player
    }
}

private final class PlayerContainerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer {
        guard let playerLayer = layer as? AVPlayerLayer else {
            preconditionFailure("PlayerContainerView requires AVPlayerLayer")
        }
        return playerLayer
    }
}
