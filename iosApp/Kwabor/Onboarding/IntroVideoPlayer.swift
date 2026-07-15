import AVFoundation
import SwiftUI

struct IntroVideoPlayer: View {
    @StateObject private var model: IntroVideoPlayerModel

    init(url: URL, onCompleted: @escaping () -> Void) {
        _model = StateObject(
            wrappedValue: IntroVideoPlayerModel(url: url, onCompleted: onCompleted)
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
    private var completionObserver: NSObjectProtocol?

    init(url: URL, onCompleted: @escaping () -> Void) {
        let item = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: item)
        player.volume = 0
        player.actionAtItemEnd = .pause
        completionObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { _ in
            onCompleted()
        }
    }

    func play() {
        player.play()
    }

    func pause() {
        player.pause()
    }

    deinit {
        if let completionObserver {
            NotificationCenter.default.removeObserver(completionObserver)
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
