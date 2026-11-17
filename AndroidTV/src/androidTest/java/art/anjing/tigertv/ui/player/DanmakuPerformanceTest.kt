package art.anjing.tigertv.ui.player

import android.app.Activity
import android.os.*
import android.view.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import art.anjing.tigertv.core.*
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.ui.theme.TigerTVTheme
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import java.util.concurrent.ConcurrentHashMap
import org.junit.*
import org.junit.Assert.*

/** Opt-in real media/service comparison; catalog metadata only is supplied by the test. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DanmakuPerformanceTest {
    @get:Rule val compose = createComposeRule()
    private fun <T: View> find(v: View, type: Class<T>): T? {
        if(type.isInstance(v)) return type.cast(v)
        if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),type)?.let{return it}
        return null
    }
    @Test fun realAutomaticMatchingAndOnOffComparison() {
        val args=InstrumentationRegistry.getArguments()
        val url=args.getString("media_url"); val server=args.getString("danmu_url")
        Assume.assumeNotNull(url,server)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("danmaku",0).edit().clear().putString("server",server).apply()
        val work=args.getString("work_title") ?: "逐玉"
        val site=args.getString("media_site") ?: "🎬暴风资源"
        val http=platformHttpClient(); val network=Network(http)
        val files=ConcurrentHashMap<String,String>().apply{put("source","""{"api_site":{"test":{"name":"$site","api":"https://fixture.test/api"}}}""")}
        val storage=object:Storage{override fun read(path:String)=files[path];override fun writeAtomic(path:String,text:String){files[path]=text}}
        val catalog="""{"code":1,"list":[{"vod_id":1,"vod_name":"$work","vod_play_from":"HLS","vod_play_url":"第1集${'$'}$url"}]}"""
        val core=CoreService("perf","source",storage,configureClient(HttpClient(MockEngine{r->
            when(r.url.host){"fixture.test"->respond(catalog);"m.douban.com"->respond("""{"subject_collection_items":[]}""");else->respond(network.get(r.url.toString()))}
        })))
        val store=ViewModelStore();lateinit var model:TigerTVViewModel;lateinit var root:View
        var window: Window? = null
        val frames=java.util.Collections.synchronizedList(mutableListOf<Double>())
        val windowListener=Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val ns=metrics.getMetric(FrameMetrics.TOTAL_DURATION);if(ns>0) frames.add(ns/1_000_000.0)
        }
        fun <T> main(block:()->T):T{var x:T?=null;InstrumentationRegistry.getInstrumentation().runOnMainSync{x=block()};@Suppress("UNCHECKED_CAST") return x as T}
        fun player()=find(root,PlayerView::class.java)!!.player!! as ExoPlayer
        fun overlay()=find(root,DanmakuView::class.java)!!
        try {
            main {model=TigerTVViewModel(TigerTVRepository(core),context);store.put("model",model);model.onKeywordChange(work);model.search()}
            compose.waitUntil(15_000){model.works.isNotEmpty()&&!model.isSearching}
            main{model.selectResult(model.works.single().sources.single())}
            compose.waitUntil(15_000){model.fetchResponse!=null&&!model.isFetching}
            main{model.selectEpisode(0)}
            compose.setContent{TigerTVTheme{root=LocalView.current;PlayerScreen(model){}}}
            compose.waitUntil(60_000){main{player().isPlaying&&overlay().commentCount>1_000}}
            assertTrue(main{overlay().commentCount}>1_000)
            args.getString("manual_title_id")?.toLong()?.let { id ->
                main { player().pause() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithTag("danmaku-action-4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithTag("danmaku-options").performScrollToNode(hasTestTag("danmaku-title-$id"))
                compose.onNodeWithTag("danmaku-title-$id").performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitUntil(45_000) { main { overlay().commentCount >= 20_000 } }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            main{window=(root.context as Activity).window;window!!.addOnFrameMetricsAvailableListener(windowListener,Handler(Looper.getMainLooper()))}
            main { player().seekTo(120_000); player().play() }
            compose.waitUntil(25_000) { main { player().isPlaying && overlay().drawnCount > 0 } }
            main { player().pause() }
            Thread.sleep(300)
            val paused = main { overlay().lastClockMs }
            Thread.sleep(500)
            assertEquals(paused, main { overlay().lastClockMs })
            for (speed in listOf(1.5f, 2f)) {
                main { player().seekTo(180_000); player().setPlaybackSpeed(speed); player().play() }
                compose.waitUntil(25_000) { main { player().isPlaying && player().currentPosition > 180_500 } }
                main { assertTrue(kotlin.math.abs(overlay().lastClockMs - player().currentPosition) < 200) }
            }
            val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/tigertv-auto-danmaku.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(capture).use { it.readBytes() }
            android.util.Log.i("TigerTVDanmakuPerf", "automatic=true pause=true seek=true speed1_5=true speed2=true")
            // All blocks use the same already loaded data and the same scene. No comment reload.
            for(enabled in listOf(false,true,false)) {
                main{player().pause()}
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                compose.waitUntil(5_000){compose.onAllNodesWithTag("player-danmaku").fetchSemanticsNodes().isNotEmpty()}
                val current=main{overlay().visibility==View.VISIBLE}
                if(current!=enabled){
                    compose.onNodeWithTag("player-danmaku").performSemanticsAction(SemanticsActions.RequestFocus){it()}
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    compose.waitForIdle()
                    compose.onNodeWithTag("danmaku-action-0").assertIsFocused()
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                }
                compose.waitUntil(5_000) { main { (overlay().visibility == View.VISIBLE) == enabled } }
                main{player().seekTo(120_000);player().setPlaybackSpeed(1f);player().play()}
                compose.waitUntil(25_000){main{player().isPlaying&&player().currentPosition>120_500}}
                frames.clear()
                val startCpu=Process.getElapsedCpuTime();val start=SystemClock.elapsedRealtime()
                val startPss=Debug.getPss();val dropped=main{player().videoDecoderCounters!!.droppedBufferCount}
                val responses=mutableListOf<Long>()
                repeat(4){
                    Thread.sleep(5_000)
                    val before=SystemClock.elapsedRealtime()
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    main { };responses+=SystemClock.elapsedRealtime()-before
                    main{assertNull(player().playerError);assertTrue(overlay().drawnCount<=12)}
                }
                val cpu=100.0*(Process.getElapsedCpuTime()-startCpu)/(SystemClock.elapsedRealtime()-start)
                val sample=synchronized(frames){frames.sorted()}
                val p95=if(sample.isEmpty())0.0 else sample[((sample.size-1)*0.95).toInt()]
                android.util.Log.i("TigerTVDanmakuPerf","enabled=$enabled comments=${main{overlay().commentCount}} cpuPercent=$cpu pssStartKiB=$startPss pssEndKiB=${Debug.getPss()} uiFrames=${sample.size} uiP95Ms=$p95 dropped=${main{player().videoDecoderCounters!!.droppedBufferCount}-dropped} remoteMaxMs=${responses.max()}")
            }
        } finally {
            main{window?.removeOnFrameMetricsAvailableListener(windowListener);store.clear()}
            http.close()
        }
    }
}
