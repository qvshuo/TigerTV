import Foundation
import AVFoundation
import CoreMedia
import CoreVideo
import QuartzCore

guard CommandLine.arguments.count == 2 else {
    fatalError("usage: player-probe <private-test-cases.json>")
}
let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let tests = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
for test in tests {
    guard let site = test["name"] as? String, let u = test["url"] as? String,
          let url = URL(string: u), let start = test["start"] as? Double,
          let end = test["end"] as? Double else { continue }
    let asset = AVURLAsset(url:url, options:["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15"]])
    let item = AVPlayerItem(asset: asset)
    let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]); item.add(output)
    let player = AVPlayer(playerItem:item); print("SITE \(site) start \(start) end \(end)"); fflush(stdout)
    func seek(_ t: Double) {
        var done=false; var ok=false
        player.seek(to: CMTime(seconds:t,preferredTimescale:600),toleranceBefore:.zero,toleranceAfter:.zero) { success in ok=success; done=true }
        let deadline=Date().addingTimeInterval(25)
        while !done && Date()<deadline { RunLoop.current.run(until:Date().addingTimeInterval(0.1)) }
        print("seek \(t) callback \(done) success \(ok) actual \(player.currentTime().seconds) item \(item.status.rawValue) error \(item.error.map { "\(($0 as NSError).domain):\(($0 as NSError).code)" } ?? "none")"); fflush(stdout)
    }
    func observe(_ seconds: Double) {
        player.play(); let deadline=Date().addingTimeInterval(seconds); var frames=0; var first=Double.nan; var last=Double.nan
        while Date()<deadline {
            let time=output.itemTime(forHostTime:CACurrentMediaTime())
            if output.hasNewPixelBuffer(forItemTime:time), output.copyPixelBuffer(forItemTime:time,itemTimeForDisplay:nil) != nil { frames += 1; if first.isNaN { first=time.seconds }; last=time.seconds }
            RunLoop.current.run(until:Date().addingTimeInterval(0.08))
        }
        player.pause(); print("observed frames \(frames) pts \(first) to \(last) position \(player.currentTime().seconds) status \(player.timeControlStatus.rawValue) error \(item.error.map { "\(($0 as NSError).domain):\(($0 as NSError).code)" } ?? "none")"); fflush(stdout)
    }
    seek(start-2); observe(5); seek(end); observe(5); seek(start+3); observe(2); seek(end+0.2); observe(4)
    player.replaceCurrentItem(with:nil)
}
