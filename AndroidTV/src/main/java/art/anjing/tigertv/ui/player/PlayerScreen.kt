package art.anjing.tigertv.ui.player

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.compose.ui.res.painterResource
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: TigerTVViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val activity = context as? Activity
    val controller = remember { Media3Controller(context) }
    val snapshot = controller.snapshot
    val url = viewModel.playbackUrl
    val playbackError = viewModel.playbackErrorMessage
    val blocked = playbackError != null || url == null
    var controls by remember { mutableStateOf(PlaybackControlsState()) }
    var focusArea by remember { mutableStateOf(PlayerFocusArea.Surface) }
    var focusRequest by remember { mutableStateOf(PlayerFocusArea.Surface to 0) }
    var consumedCenter by remember { mutableStateOf(false) }
    var showBuffering by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var danmakuMenu by remember { mutableStateOf(false) }
    var danmakuQuick by remember { mutableStateOf(false) }
    val danmakuScope = rememberCoroutineScope()
    val preferences = remember { context.applicationContext.getSharedPreferences("danmaku", 0) }
    var configuredServer by remember { mutableStateOf(preferences.getString("server", "").orEmpty()) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "server") configuredServer = preferences.getString("server", "").orEmpty()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val session = viewModel.playbackSession
    val identity = listOf(session?.workId.orEmpty(), session?.site.orEmpty(), session?.line.orEmpty(), session?.episode.orEmpty())
        .joinToString("") { "${it.length}:$it" }
    val danmakuState = remember(identity, viewModel.playbackRevision) {
        DanmakuState(danmakuScope, viewModel::danmakuClient, preferences.getBoolean("enabled:$identity", true),
            art.anjing.tigertv.core.regularDanmakuEpisode(session?.episode.orEmpty()),
            { server, id -> preferences.edit().putLong(danmakuPreferenceKey(server, session?.workId.orEmpty(), session?.site.orEmpty()), id).apply() },
            { server -> preferences.getLong(danmakuPreferenceKey(server, session?.workId.orEmpty(), session?.site.orEmpty()), -1).takeIf { it > 0 } },
            workYear = viewModel.fetchResponse?.vodYear.orEmpty())
    }
    DisposableEffect(danmakuState) { onDispose { danmakuState.close() } }
    LaunchedEffect(danmakuState, danmakuState.enabled, configuredServer) {
        danmakuState.configure(configuredServer)
        if (danmakuState.enabled && danmakuState.track == null && !danmakuState.busy && configuredServer.isNotBlank())
            danmakuState.search(viewModel.fetchResponse?.vodName.orEmpty())
    }
    val surfaceFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val danmakuFocus = remember { FocusRequester() }
    val speedOptionFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    fun requestFocus(area: PlayerFocusArea) { focusRequest = area to (focusRequest.second + 1) }
    fun interact() { controls = controls.show(SystemClock.elapsedRealtime()) }
    fun showControls(area: PlayerFocusArea) {
        controller.refresh()
        interact()
        requestFocus(area)
    }
    fun hideControls() { controls = controls.hide(); requestFocus(PlayerFocusArea.Surface) }
    fun finishSeek(commit: Boolean) {
        val seek = controls.seek ?: return
        controls = controls.copy(seek = null).show(SystemClock.elapsedRealtime())
        if (commit) controller.seekTo(seek.targetMillis)
        if (seek.wasPlaying) controller.play() else controller.pause()
    }
    fun exitPlayer() {
        if (exiting) return
        exiting = true
        finishSeek(false)
        controller.pause()
        onBack()
    }
    fun switchEpisode(offset: Int) {
        val index = viewModel.selectedEpisodeIndex + offset
        if (index !in viewModel.episodes.indices) return
        finishSeek(false)
        controller.unload()
        controls = PlaybackControlsState()
        viewModel.selectEpisode(index)
    }
    fun togglePlayback() {
        if (controls.seek != null) finishSeek(true) else controller.togglePlayback()
        showControls(PlayerFocusArea.Actions)
    }
    fun handleBack() {
        when {
            danmakuQuick -> { danmakuQuick = false; showControls(PlayerFocusArea.Danmaku) }
            danmakuMenu -> { danmakuMenu = false; showControls(PlayerFocusArea.Danmaku) }
            blocked -> exitPlayer()
            controls.seek != null -> finishSeek(false)
            controls.speedMenuOpen -> {
                controls = controls.copy(speedMenuOpen = false).show(SystemClock.elapsedRealtime())
                requestFocus(PlayerFocusArea.SpeedButton)
            }
            controls.visible -> hideControls()
            else -> exitPlayer()
        }
    }

    BackHandler { handleBack() }
    LaunchedEffect(windowFocused) {
        if (!windowFocused) finishSeek(false)
    }
    // An error can arrive while loading is already blocked, without changing `blocked`.
    LaunchedEffect(focusRequest, blocked, playbackError, controls.visible, controls.speedMenuOpen, windowFocused, lifecycleState) {
        if (!windowFocused || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        when {
            playbackError != null -> requestFocusSafely(retryFocus)
            blocked || !controls.visible -> requestFocusSafely(surfaceFocus)
            controls.speedMenuOpen -> requestFocusSafely(speedOptionFocus)
            focusRequest.first == PlayerFocusArea.Timeline -> requestFocusSafely(timelineFocus)
            focusRequest.first == PlayerFocusArea.SpeedButton -> requestFocusSafely(speedFocus)
            focusRequest.first == PlayerFocusArea.Danmaku -> requestFocusSafely(danmakuFocus)
            focusRequest.first == PlayerFocusArea.Actions -> requestFocusSafely(playFocus)
            else -> requestFocusSafely(surfaceFocus)
        }
    }
    LaunchedEffect(snapshot.buffering, blocked) {
        showBuffering = false
        if (snapshot.buffering && !blocked) { delay(500); showBuffering = true }
    }
    LaunchedEffect(url, viewModel.playbackRevision) {
        danmakuMenu = false
        danmakuQuick = false
        controls = PlaybackControlsState()
        consumedCenter = false
        requestFocus(PlayerFocusArea.Surface)
    }
    // A seek can change readiness while paused; it must not restore focus over navigation.
    LaunchedEffect(snapshot.playWhenReady, blocked) {
        if (!snapshot.playWhenReady && snapshot.ready && !blocked && controls.seek == null) showControls(PlayerFocusArea.Actions)
    }
    LaunchedEffect(snapshot.ended) {
        if (snapshot.ended && !blocked) showControls(PlayerFocusArea.Actions)
    }
    LaunchedEffect(controls.lastInteractionMillis, controls.visible, controls.speedMenuOpen, controls.seek, snapshot.playWhenReady, blocked, danmakuMenu, danmakuQuick) {
        if (danmakuMenu || danmakuQuick) return@LaunchedEffect
        if (!blocked && controls.visible && snapshot.playWhenReady && controls.seek == null && !controls.speedMenuOpen) {
            val remaining = (5_000 - (SystemClock.elapsedRealtime() - controls.lastInteractionMillis)).coerceAtLeast(0)
            delay(remaining)
            if (controls.shouldAutoHide(SystemClock.elapsedRealtime(), controller.snapshot.playWhenReady)) hideControls()
        }
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.close()
            if (exiting) viewModel.clearPlayback()
        }
    }
    DisposableEffect(url, viewModel.playbackRevision) {
        val session = viewModel.playbackSession
        if (url != null && session != null) {
            val title = listOfNotNull(viewModel.fetchResponse?.vodName, session.episode).joinToString(" · ")
            controller.load(url, session.positionMillis, title, session.site,
                onStarted = { viewModel.playbackDidStart(session) },
                onPosition = { viewModel.savePlaybackPosition(session, it) },
                onError = viewModel::setPlaybackError)
            controller.play()
        }
        onDispose { controller.unload() }
    }
    LaunchedEffect(controller, controls.visible, blocked, lifecycleState) {
        if (!controls.visible || blocked || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        while (true) {
            controller.refresh()
            delay(250)
        }
    }
    LaunchedEffect(controller, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        while (true) {
            delay(5_000)
            controller.savePosition()
        }
    }
    // Keep lifecycle callbacks current without repeatedly registering an observer.
    val cancelSeekOnBackground by rememberUpdatedState({ finishSeek(false) })
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { cancelSeekOnBackground(); controller.onBackground() }
                Lifecycle.Event.ON_RESUME -> controller.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val code = key.keyCode
        val down = key.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !key.isCanceled) handleBack()
            return@onPreviewKeyEvent true
        }
        val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (center && !down && consumedCenter) {
            consumedCenter = false
            return@onPreviewKeyEvent true
        }
        if (blocked) return@onPreviewKeyEvent false
        if (down && controls.visible) controls = controls.show(SystemClock.elapsedRealtime())
        val seekDirection = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
            else -> 0
        }
        val dedicatedSeek = code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        if (seekDirection != 0 && (dedicatedSeek || !controls.visible || (!controls.speedMenuOpen && focusArea == PlayerFocusArea.Timeline) || controls.seek != null)) {
            if (!down) {
                if (controls.seek?.keyCode == code) finishSeek(!key.isCanceled)
            } else {
                if (controls.speedMenuOpen) controls = controls.copy(speedMenuOpen = false)
                if (!controls.visible || focusArea != PlayerFocusArea.Timeline) showControls(PlayerFocusArea.Timeline)
                controller.refresh()
                val current = controller.snapshot
                if (current.seekable) {
                    controls = controls.adjustSeek(seekDirection, current.positionMillis, current.durationMillis,
                        current.playWhenReady, code, SystemClock.elapsedRealtime(), key.repeatCount > 0)
                    if (key.repeatCount > 0 && controller.player.playWhenReady) controller.pause()
                }
            }
            return@onPreviewKeyEvent true
        }
        if (center && (!controls.visible || focusArea == PlayerFocusArea.Surface || focusArea == PlayerFocusArea.Timeline)) {
            if (down && key.repeatCount == 0) { consumedCenter = true; togglePlayback() }
            return@onPreviewKeyEvent true
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && key.repeatCount == 0) {
                    finishSeek(false)
                    when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> { if (snapshot.ended) controller.togglePlayback() else controller.play() }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> controller.pause()
                        else -> controller.togglePlayback()
                    }
                    showControls(PlayerFocusArea.Actions)
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down && key.repeatCount == 0) switchEpisode(if (code == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down && (!controls.visible || focusArea == PlayerFocusArea.Surface)) {
                    showControls(PlayerFocusArea.Timeline)
                    true
                } else false
            }
            else -> false
        }
    }.focusRequester(surfaceFocus).onFocusChanged {
        if (it.isFocused) focusArea = PlayerFocusArea.Surface
    }.focusable()) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                player = controller.player
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())

        key(url, viewModel.playbackRevision) {
            if (!blocked) DanmakuPlayback(controller, danmakuState, viewModel.fetchResponse?.vodName.orEmpty(), danmakuMenu,
                onClose = { danmakuMenu = false; showControls(PlayerFocusArea.Danmaku) }, )
        }
        if (!blocked && controls.visible) {
            val line = viewModel.fetchResponse?.playSources?.getOrNull(viewModel.selectedPlaybackSourceIndex)
            val episode = viewModel.episodes.getOrNull(viewModel.selectedEpisodeIndex)
            PlaybackControls(
                title = listOfNotNull(viewModel.fetchResponse?.vodName, episode?.name).joinToString(" · "),
                resource = listOfNotNull(viewModel.selectedResult?.site, line?.name).joinToString(" · "),
                snapshot = snapshot, controls = controls, timelineFocus = timelineFocus, playFocus = playFocus,
                speedFocus = speedFocus, speedOptionFocus = speedOptionFocus, danmakuFocus = danmakuFocus,
                danmakuOpen = danmakuQuick, danmakuEnabled = danmakuState.enabled, danmakuLoaded = danmakuState.track?.comments?.any { it.mode == 1 } == true, danmakuOffsetMs = danmakuState.offsetMs,
                onDanmakuMenu = { controls = controls.copy(speedMenuOpen = false); danmakuQuick = !danmakuQuick; interact() },
                onDanmakuAction = { action ->
                    when (action) {
                        4 -> danmakuMenu = true
                        1 -> danmakuState.offsetMs += 1_000
                        2 -> danmakuState.offsetMs -= 1_000
                        0 -> { danmakuState.enabled = !danmakuState.enabled; preferences.edit().putBoolean("enabled:$identity", danmakuState.enabled).apply() }
                    }
                    danmakuQuick = false; interact(); requestFocus(PlayerFocusArea.Danmaku)
                },
                canPrevious = viewModel.selectedEpisodeIndex > 0,
                canNext = viewModel.selectedEpisodeIndex in 0 until viewModel.episodes.lastIndex,
                onFocus = { focusArea = it }, onPrevious = { switchEpisode(-1) }, onNext = { switchEpisode(1) },
                onTogglePlayback = ::togglePlayback,
                onSpeedMenu = {
                    finishSeek(false)
                    danmakuQuick = false
                    controls = controls.copy(speedMenuOpen = !controls.speedMenuOpen).show(SystemClock.elapsedRealtime())
                    requestFocus(if (controls.speedMenuOpen) PlayerFocusArea.Speed else PlayerFocusArea.SpeedButton)
                },
                onSpeed = {
                    controller.setSpeed(it)
                    controls = controls.copy(speedMenuOpen = false).show(SystemClock.elapsedRealtime())
                    requestFocus(PlayerFocusArea.SpeedButton)
                }, onSeekTo = { controller.seekTo(it); interact() }
            )
        }
        if (showBuffering) {
            LoadingOverlay("正在准备播放…")
        }
        playbackError?.let { error ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
                .padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(error, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    ActionButton(onClick = { controller.unload(); viewModel.retryPlayback() },
                        modifier = Modifier.focusRequester(retryFocus).widthIn(min = 144.dp).focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }) {
                        Icon(painterResource(R.drawable.player_refresh), contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}
