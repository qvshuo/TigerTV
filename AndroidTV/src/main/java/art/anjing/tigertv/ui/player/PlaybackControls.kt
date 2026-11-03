package art.anjing.tigertv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import art.anjing.tigertv.R
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
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.55f),
        0.28f to Color.Transparent,
        0.52f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.8f)
    ))) {
        Column(Modifier.align(Alignment.TopStart).padding(horizontal = 40.dp, vertical = 28.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(resource, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (controls.speedMenuOpen) {
                Column(Modifier.align(Alignment.End)
                    .background(Color(0xFF242428).copy(alpha = 0.72f), RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("播放倍速", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    val focusedSpeed = speeds.minBy { abs(it - snapshot.speed) }
                    Row(Modifier.widthIn(max = 612.dp).horizontalScroll(rememberScrollState()).focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        speeds.forEachIndexed { index, speed ->
                            PlayerButton(onClick = { onSpeed(speed) }, modifier = Modifier.width(92.dp)
                                .then(if (focusedSpeed == speed) Modifier.focusRequester(speedOptionFocus) else Modifier)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = speedFocus
                                    if (index == 0) left = FocusRequester.Cancel
                                    if (index == speeds.lastIndex) right = FocusRequester.Cancel
                                }
                                .onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.Speed) },
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("${speed}×" + if (snapshot.speed == speed) " ✓" else "")
                            }
                        }
                    }
                }
            } else {
                val position = controls.seek?.targetMillis ?: snapshot.positionMillis
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(position), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    controls.seek?.let {
                        val delta = it.targetMillis - it.originMillis
                        Text((if (delta >= 0) "+" else "−") + formatPlaybackTime(abs(delta)),
                            color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(if (snapshot.durationMillis > 0) formatPlaybackTime(snapshot.durationMillis) else "时长未知",
                        color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyLarge)
                }
                PlaybackTimeline(snapshot, position, timelineFocus, playFocus, onFocus, onSeekTo)
                if (!snapshot.seekable) {
                    Text(if (!snapshot.ready && snapshot.durationMillis == 0L) "正在获取时长…" else "当前视频不支持快进快退",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
            val previousFocus = remember { FocusRequester() }
            val nextFocus = remember { FocusRequester() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 560.dp
                Box(Modifier.fillMaxWidth().height(if (stacked) 132.dp else 64.dp).focusGroup()) {
                    val actionModifier = Modifier.focusProperties {
                        up = if (controls.speedMenuOpen) speedOptionFocus else timelineFocus
                        down = FocusRequester.Cancel
                    }.onFocusChanged { if (it.isFocused) onFocus(PlayerFocusArea.Actions) }
                    // The symmetric transport group centers play on the viewport, independently of speed.
                    Row(Modifier.align(if (stacked) Alignment.TopCenter else Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PlayerButton(onClick = onPrevious, enabled = canPrevious,
                            modifier = Modifier.focusRequester(previousFocus).width(56.dp).then(actionModifier).focusProperties {
                                canFocus = canPrevious; left = FocusRequester.Cancel; right = playFocus
                            }, compact = true, contentPadding = PaddingValues(0.dp)) {
                            Icon(painterResource(R.drawable.player_skip_previous), contentDescription = "上一集", modifier = Modifier.size(24.dp))
                        }
                        PlayerButton(onClick = onTogglePlayback,
                            modifier = Modifier.focusRequester(playFocus).width(64.dp).then(actionModifier).focusProperties {
                                left = if (canPrevious) previousFocus else FocusRequester.Cancel
                                right = if (canNext) nextFocus else speedFocus
                            }, height = 64.dp, prominent = true, contentPadding = PaddingValues(0.dp)) {
                            val icon = if (snapshot.ended) R.drawable.player_replay else if (snapshot.playWhenReady) R.drawable.player_pause else R.drawable.player_play_arrow
                            Icon(painterResource(icon), contentDescription = if (snapshot.ended) "重播" else if (snapshot.playWhenReady) "暂停" else "播放",
                                modifier = Modifier.size(if (snapshot.ended) 32.dp else 40.dp))
                        }
                        PlayerButton(onClick = onNext, enabled = canNext,
                            modifier = Modifier.focusRequester(nextFocus).width(56.dp).then(actionModifier).focusProperties {
                                canFocus = canNext; left = playFocus; right = speedFocus
                            }, compact = true, contentPadding = PaddingValues(0.dp)) {
                            Icon(painterResource(R.drawable.player_skip_next), contentDescription = "下一集", modifier = Modifier.size(24.dp))
                        }
                    }
                    PlayerButton(onClick = onSpeedMenu,
                        modifier = Modifier.align(if (stacked) Alignment.BottomEnd else Alignment.CenterEnd).focusRequester(speedFocus).width(144.dp).then(actionModifier).focusProperties {
                            left = if (canNext) nextFocus else playFocus; right = FocusRequester.Cancel
                        }, compact = true) {
                        Icon(painterResource(R.drawable.player_speed), contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("倍速 ${snapshot.speed}×", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackTimeline(
    snapshot: PlayerSnapshot, position: Long, timelineFocus: FocusRequester, playFocus: FocusRequester,
    onFocus: (PlayerFocusArea) -> Unit, onSeekTo: (Long) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focus = animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "timeline focus")
    val progress = if (snapshot.durationMillis > 0) (position.toFloat() / snapshot.durationMillis).coerceIn(0f, 1f) else 0f
    val buffered = if (snapshot.durationMillis > 0) (snapshot.bufferedMillis.toFloat() / snapshot.durationMillis).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().height(56.dp).focusRequester(timelineFocus).focusProperties {
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
        val inset = 16.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(0f)
        val y = size.height / 2
        val stroke = (3 + 2 * focus.value).dp.toPx()
        drawLine(Color.White.copy(alpha = 0.22f), Offset(inset, y), Offset(inset + width, y), stroke, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.42f), Offset(inset, y), Offset(inset + width * buffered, y), stroke, StrokeCap.Round)
        val accent = Color.White
        val thumb = Offset(inset + width * progress, y)
        drawLine(accent, Offset(inset, y), Offset(inset + width * progress, y), stroke, StrokeCap.Round)
        if (focus.value > 0f) drawCircle(Color.White.copy(alpha = focus.value * 0.2f), 12.dp.toPx(), thumb)
        drawCircle(accent, (5 + 3 * focus.value).dp.toPx(), thumb)
    }
}
