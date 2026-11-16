package art.anjing.tigertv.debug

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import art.anjing.tigertv.ui.player.Media3Controller

/** Manual, debug-only probe. Times must be verified against the current media by the operator. */
class SeekProbeActivity : Activity() {
    internal var controller: Media3Controller? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val url = intent.dataString
        val start = intent.getLongExtra("start_ms", -1)
        val end = intent.getLongExtra("end_ms", -1)
        if (url == null || start < 0 || end <= start) { finish(); return }
        val media = Media3Controller(applicationContext).also { controller = it }
        val player = media.player
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply { text = "请先验证当前媒体内容，再手动测试跳转" }
        val view = PlayerView(this).apply { this.player = player; useController = true }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        val row = LinearLayout(this)
        fun button(label: String, action: () -> Unit) {
            row.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("广告前") { player.seekTo((start - 3000).coerceAtLeast(0)) }
        button("广告内") { player.seekTo(start + (end - start) / 2) }
        button("广告后") { player.seekTo(end) }
        button("暂停／播放") { if (player.isPlaying) player.pause() else player.play() }
        button("倍速") { player.setPlaybackSpeed(if (player.playbackParameters.speed == 1f) 1.5f else 1f) }
        root.addView(row)
        setContentView(root)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                android.util.Log.d("TigerTVProbe", "firstFrameMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                status.text = "状态 $playbackState；位置 ${player.currentPosition}ms；请检查画面和声音"
            }
        })
        media.load(url, intent.getLongExtra("resume_ms", 0), "实验", intent.getStringExtra("site") ?: "🎬暴风资源", {}, {}, { status.text = "播放失败；请检查设备日志" })
    }

    override fun onStop() {
        controller?.player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        controller?.close()
        controller = null
        super.onDestroy()
    }
}
