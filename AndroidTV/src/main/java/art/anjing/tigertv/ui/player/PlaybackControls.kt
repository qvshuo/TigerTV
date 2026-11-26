package art.anjing.tigertv.ui.player

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Surface
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.requestFocusSafely
import kotlin.math.abs

enum class PlayerFocusArea { Surface, Timeline, Actions, Speed, SpeedButton, Danmaku }

@Composable
fun PlaybackControls(
    title: String, resource: String, snapshot: PlayerSnapshot, controls: PlaybackControlsState,
    timelineFocus: FocusRequester, playFocus: FocusRequester, speedFocus: FocusRequester,
    speedOptionFocus: FocusRequester, danmakuFocus: FocusRequester, canPrevious: Boolean, canNext: Boolean,
    onFocus: (PlayerFocusArea) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onTogglePlayback: () -> Unit, onSpeedMenu: () -> Unit, onSpeed: (Float) -> Unit,
    onSeekTo: (Long) -> Unit, danmakuOpen: Boolean, danmakuEnabled: Boolean,
    onDanmakuMenu: () -> Unit, onDanmakuAction: (Int) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.72f),
        0.17f to Color.Black.copy(alpha = 0.55f),
        0.32f to Color.Transparent,
        0.52f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.8f)
    ))) {
        val density = LocalDensity.current
        var danmakuWidth by remember { mutableStateOf(0.dp) }
        val danmakuOption = remember { FocusRequester() }
        LaunchedEffect(danmakuOpen) {
            if (danmakuOpen) requestFocusSafely(danmakuOption)
        }
        val stacked = maxWidth < 900.dp
        val actionsHeight = if (stacked) 132.dp else 64.dp
        val speedMenuHeight = (maxHeight - actionsHeight - 112.dp).coerceAtLeast(132.dp)
        Column(Modifier.align(Alignment.TopStart).padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(resource, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (danmakuOpen) {
                Surface(modifier = Modifier.align(Alignment.End).padding(end = 16.dp).width(152.dp).heightIn(max = speedMenuHeight),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ListItem(selected = false, onClick = { onDanmakuAction(0) },
                            scale = ListItemDefaults.scale(focusedScale = 1f),
                            shape = ListItemDefaults.shape(shape = MaterialTheme.shapes.large),
                            headlineContent = { Text(if (danmakuEnabled) "关闭弹幕" else "开启弹幕") },
                            modifier = Modifier.fillMaxWidth().testTag("danmaku-action-0"))
                        ListItem(selected = false, onClick = { onDanmakuAction(4) },
                            scale = ListItemDefaults.scale(focusedScale = 1f),
                            shape = ListItemDefaults.shape(shape = MaterialTheme.shapes.large),
                            headlineContent = { Text("选择弹幕") },
                            modifier = Modifier.fillMaxWidth().testTag("danmaku-action-4").focusRequester(danmakuOption)
                                .focusProperties { down = danmakuFocus })

                    }
                }
            }
            if (controls.speedMenuOpen) {
                Surface(modifier = Modifier.align(Alignment.End).padding(end = 16.dp + danmakuWidth + 12.dp).width(152.dp).heightIn(max = speedMenuHeight),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp) {
                    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    val focusedSpeed = speeds.minBy { abs(it - snapshot.speed) }
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        speeds.forEachIndexed { index, speed ->
                            ListItem(selected = snapshot.speed == speed, onClick = { onSpeed(speed) },
                                scale = ListItemDefaults.scale(focusedScale = 1f),
                                shape = ListItemDefaults.shape(shape = MaterialTheme.shapes.large),
                                headlineContent = { Text("${speed}×") },
                                trailingContent = {
                                    Icon(painterResource(R.drawable.selection_check), contentDescription = null,
                                        modifier = Modifier.size(20.dp).alpha(if (snapshot.speed == speed) 1f else 0f))
                                },
                                modifier = Modifier.fillMaxWidth()
                                .then(if (focusedSpeed == speed) Modifier.focusRequester(speedOptionFocus) else Modifier)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                    if (index == 0) up = FocusRequester.Cancel
                                    if (index == speeds.lastIndex) down = speedFocus
                                }
                                .onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.Speed) })
                        }
                    }
                }
            }
            if (!controls.speedMenuOpen && !danmakuOpen) {
                val position = controls.seek?.targetMillis ?: snapshot.positionMillis
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        val timeStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                        Text(formatPlaybackTime(position), color = Color.White, style = timeStyle)
                        controls.seek?.let {
                            val delta = it.targetMillis - it.originMillis
                            Text((if (delta >= 0) "+" else "−") + formatPlaybackTime(abs(delta)),
                                color = Color.White, style = timeStyle)
                        }
                        Text(if (snapshot.durationMillis > 0) formatPlaybackTime(snapshot.durationMillis) else "时长未知",
                            color = Color.White.copy(alpha = 0.65f), style = timeStyle)
                    }
                    PlaybackTimeline(snapshot, position, timelineFocus, playFocus, onFocus, onSeekTo, Modifier.matchParentSize())
                }
                if (!snapshot.seekable) {
                    Text(if (!snapshot.ready && snapshot.durationMillis == 0L) "正在获取时长…" else "当前视频不支持快进快退",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
            val previousFocus = remember { FocusRequester() }
            val nextFocus = remember { FocusRequester() }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(actionsHeight).focusGroup()) {
                val actionModifier = Modifier.focusProperties {
                    up = when {
                        controls.speedMenuOpen -> speedOptionFocus
                        else -> timelineFocus
                    }
                    down = if (stacked) speedFocus else FocusRequester.Cancel
                }.onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.Actions) }
                Row(Modifier.align(if (stacked) Alignment.TopCenter else Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(scale = IconButtonDefaults.scale(focusedScale = 1f), onClick = onPrevious, enabled = canPrevious,
                        modifier = Modifier.testTag("player-previous").focusRequester(previousFocus).size(56.dp).then(actionModifier).focusProperties {
                            canFocus = canPrevious; left = FocusRequester.Cancel; right = playFocus
                        }) {
                        Icon(painterResource(R.drawable.player_skip_previous), contentDescription = "上一集", modifier = Modifier.size(24.dp))
                    }
                    val playTag = if (snapshot.ended) "player-replay" else if (snapshot.playWhenReady) "player-pause" else "player-play"
                    IconButton(scale = IconButtonDefaults.scale(focusedScale = 1f), onClick = onTogglePlayback,
                        modifier = Modifier.testTag(playTag).focusRequester(playFocus).size(64.dp).then(actionModifier).focusProperties {
                            left = if (canPrevious) previousFocus else FocusRequester.Cancel
                            right = if (canNext) nextFocus else speedFocus
                        }) {
                        val icon = if (snapshot.ended) R.drawable.player_replay else if (snapshot.playWhenReady) R.drawable.player_pause else R.drawable.player_play_arrow
                        Icon(painterResource(icon), contentDescription = if (snapshot.ended) "重播" else if (snapshot.playWhenReady) "暂停" else "播放",
                            modifier = Modifier.size(if (snapshot.ended) 32.dp else 40.dp))
                    }
                    IconButton(scale = IconButtonDefaults.scale(focusedScale = 1f), onClick = onNext, enabled = canNext,
                        modifier = Modifier.testTag("player-next").focusRequester(nextFocus).size(56.dp).then(actionModifier).focusProperties {
                            canFocus = canNext; left = playFocus; right = speedFocus
                        }) {
                        Icon(painterResource(R.drawable.player_skip_next), contentDescription = "下一集", modifier = Modifier.size(24.dp))
                    }
                }
                Row(Modifier.align(if (stacked) Alignment.BottomEnd else Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(onClick = onSpeedMenu,
                    modifier = Modifier.testTag("player-speed").focusRequester(speedFocus).onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.SpeedButton) }.semantics { contentDescription = "倍速 ${snapshot.speed}×" }.focusProperties {
                        left = if (canNext) nextFocus else playFocus; right = danmakuFocus
                        up = when {
                            danmakuOpen -> danmakuOption
                            controls.speedMenuOpen -> speedOptionFocus
                            stacked -> playFocus
                            else -> timelineFocus
                        }
                        down = FocusRequester.Cancel
                    }, contentPadding = PaddingValues(horizontal = 20.dp)) {
                    Icon(painterResource(R.drawable.player_speed), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("倍速", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                ActionButton(onClick = onDanmakuMenu, modifier = Modifier.testTag("player-danmaku").semantics { contentDescription = if (danmakuEnabled) "弹幕已开启" else "弹幕已关闭" }.onSizeChanged { danmakuWidth = with(density) { it.width.toDp() } }.focusRequester(danmakuFocus)
                    .focusProperties { left = speedFocus; right = FocusRequester.Cancel; up = when {
                        danmakuOpen -> danmakuOption
                        controls.speedMenuOpen -> speedOptionFocus
                        stacked -> playFocus
                        else -> timelineFocus
                    }; down = FocusRequester.Cancel }
                    .onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.Danmaku) }, contentPadding = PaddingValues(horizontal = 20.dp)) {
                    Icon(painterResource(if (danmakuEnabled) R.drawable.player_danmaku else R.drawable.player_danmaku_off), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("弹幕", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                }
            }
        }
    }
}

@Composable
private fun PlaybackTimeline(
    snapshot: PlayerSnapshot, position: Long, timelineFocus: FocusRequester, playFocus: FocusRequester,
    onFocus: (PlayerFocusArea) -> Unit, onSeekTo: (Long) -> Unit, modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val progress = if (snapshot.durationMillis > 0) (position.toFloat() / snapshot.durationMillis).coerceIn(0f, 1f) else 0f
    val buffered = if (snapshot.durationMillis > 0) (snapshot.bufferedMillis.toFloat() / snapshot.durationMillis).coerceIn(0f, 1f) else 0f
    Box(modifier.testTag("player-timeline").fillMaxWidth().heightIn(min = 56.dp).focusRequester(timelineFocus).focusProperties {
        up = FocusRequester.Cancel
        down = playFocus
        left = FocusRequester.Cancel
        right = FocusRequester.Cancel
    }.onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) onFocus(PlayerFocusArea.Timeline)
    }.semantics {
        contentDescription = "播放进度"
        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        if (snapshot.seekable) setProgress { fraction ->
            onSeekTo((fraction.coerceIn(0f, 1f) * snapshot.durationMillis).toLong())
            true
        }
    }.focusable()) {
        val colors = MaterialTheme.colorScheme
        BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp).height(24.dp).clearAndSetSemantics {}) {
            val track = Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp)
            LinearProgressIndicator(progress = { buffered }, modifier = track,
                color = colors.onSurfaceVariant.copy(alpha = 0.5f), trackColor = colors.onSurface.copy(alpha = 0.16f),
                gapSize = 0.dp, drawStopIndicator = {})
            LinearProgressIndicator(progress = { progress }, modifier = track,
                color = colors.primary.copy(alpha = if (focused) 1f else 0.72f), trackColor = Color.Transparent,
                gapSize = 0.dp, drawStopIndicator = {})
            val thumbSize = 14.dp
            Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * progress - thumbSize / 2)
                .size(thumbSize).background(colors.primary.copy(alpha = if (focused) 1f else 0.5f), CircleShape))
        }
    }
}
