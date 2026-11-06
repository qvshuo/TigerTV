package art.anjing.tigertv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Surface
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.UiMetrics
import kotlin.math.abs

enum class PlayerFocusArea { Surface, Timeline, Actions, Speed, SpeedButton }

@Composable
fun PlaybackControls(
    title: String, resource: String, snapshot: PlayerSnapshot, controls: PlaybackControlsState,
    timelineFocus: FocusRequester, playFocus: FocusRequester, speedFocus: FocusRequester,
    speedOptionFocus: FocusRequester, canPrevious: Boolean, canNext: Boolean,
    onFocus: (PlayerFocusArea) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onTogglePlayback: () -> Unit, onSpeedMenu: () -> Unit, onSpeed: (Float) -> Unit,
    onSeekTo: (Long) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.55f),
        0.28f to Color.Transparent,
        0.52f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.8f)
    ))) {
        val stacked = maxWidth < 640.dp
        val actionsHeight = if (stacked) 132.dp else 64.dp
        val speedMenuHeight = (maxHeight - actionsHeight - 112.dp).coerceAtLeast(132.dp)
        Column(Modifier.align(Alignment.TopStart).padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(resource, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (controls.speedMenuOpen) {
                Surface(modifier = Modifier.align(Alignment.End).padding(end = 16.dp).width(152.dp).heightIn(max = speedMenuHeight),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp) {
                    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    val focusedSpeed = speeds.minBy { abs(it - snapshot.speed) }
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        speeds.forEachIndexed { index, speed ->
                            ListItem(selected = snapshot.speed == speed, onClick = { onSpeed(speed) },
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
            } else {
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
                    up = if (controls.speedMenuOpen) speedOptionFocus else timelineFocus
                    down = FocusRequester.Cancel
                }.onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.Actions) }
                // The symmetric transport group centers play on the viewport, independently of speed.
                Row(Modifier.align(if (stacked) Alignment.TopCenter else Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, enabled = canPrevious,
                        modifier = Modifier.testTag("player-previous").focusRequester(previousFocus).size(56.dp).then(actionModifier).focusProperties {
                            canFocus = canPrevious; left = FocusRequester.Cancel; right = playFocus
                        }) {
                        Icon(painterResource(R.drawable.player_skip_previous), contentDescription = "上一集", modifier = Modifier.size(24.dp))
                    }
                    val playTag = if (snapshot.ended) "player-replay" else if (snapshot.playWhenReady) "player-pause" else "player-play"
                    IconButton(onClick = onTogglePlayback,
                        modifier = Modifier.testTag(playTag).focusRequester(playFocus).size(64.dp).then(actionModifier).focusProperties {
                            left = if (canPrevious) previousFocus else FocusRequester.Cancel
                            right = if (canNext) nextFocus else speedFocus
                        }) {
                        val icon = if (snapshot.ended) R.drawable.player_replay else if (snapshot.playWhenReady) R.drawable.player_pause else R.drawable.player_play_arrow
                        Icon(painterResource(icon), contentDescription = if (snapshot.ended) "重播" else if (snapshot.playWhenReady) "暂停" else "播放",
                            modifier = Modifier.size(if (snapshot.ended) 32.dp else 40.dp))
                    }
                    IconButton(onClick = onNext, enabled = canNext,
                        modifier = Modifier.testTag("player-next").focusRequester(nextFocus).size(56.dp).then(actionModifier).focusProperties {
                            canFocus = canNext; left = playFocus; right = speedFocus
                        }) {
                        Icon(painterResource(R.drawable.player_skip_next), contentDescription = "下一集", modifier = Modifier.size(24.dp))
                    }
                }
                ActionButton(onClick = onSpeedMenu,
                    modifier = Modifier.testTag("player-speed").align(if (stacked) Alignment.BottomEnd else Alignment.CenterEnd).focusRequester(speedFocus).then(actionModifier).semantics { contentDescription = "倍速 ${snapshot.speed}×" }.focusProperties {
                        left = if (canNext) nextFocus else playFocus; right = FocusRequester.Cancel
                    }, contentPadding = PaddingValues(horizontal = 20.dp)) {
                    Icon(painterResource(R.drawable.player_speed), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("倍速", style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
    val focus = animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "timeline focus")
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
            val track = Modifier.align(Alignment.Center).fillMaxWidth().height((4 + 2 * focus.value).dp)
            LinearProgressIndicator(progress = { buffered }, modifier = track,
                color = colors.onSurfaceVariant.copy(alpha = 0.5f), trackColor = colors.onSurface.copy(alpha = 0.16f),
                gapSize = 0.dp, drawStopIndicator = {})
            LinearProgressIndicator(progress = { progress }, modifier = track,
                color = colors.primary, trackColor = Color.Transparent,
                gapSize = 0.dp, drawStopIndicator = {})
            val thumbSize = (10 + 6 * focus.value).dp
            Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * progress - thumbSize / 2)
                .size(thumbSize).background(colors.primary, CircleShape))
        }
    }
}
