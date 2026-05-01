import SwiftUI
import AVKit

struct PlayerContainer: View {
    let player: AVPlayer

    var body: some View {
        VideoPlayer(player: player)
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
