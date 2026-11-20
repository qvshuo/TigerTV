package art.anjing.tigertv.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import art.anjing.tigertv.ui.components.SelectionChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.TvTextInput
import art.anjing.tigertv.ui.requestFocusSafely

/** Nonblocking overlay and manual selection. Recreate with the playback-session key. */
@Composable
internal fun DanmakuPlayback(
    controller: Media3Controller, state: DanmakuState, title: String, menuOpen: Boolean, onClose: () -> Unit, onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentEpisodeFocus = remember { FocusRequester() }
    AndroidView(factory = { DanmakuView(it) },
        update = { it.bind(controller.player, state.track, state.enabled, state.offsetMs,
            loading = controller.hasVideoFrame && state.busy && !state.hasComments) { controller.playbackTimeline } },
        onRelease = { it.release() }, modifier = Modifier.fillMaxSize().testTag("danmaku-overlay"))
    if (!menuOpen) return
    val entry = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var keyword by remember(title) { mutableStateOf(title) }
        val keyboard = LocalSoftwareKeyboardController.current
        fun match() {
            if (keyword.isNotBlank()) {
                keyboard?.hide()
                state.search(keyword, useSaved = false)
            }
        }
        LaunchedEffect(state.isConfigured) { requestFocusSafely(entry) }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
            val dialogHeight = maxHeight
            Surface(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = dialogHeight).testTag("danmaku-options")
                    .semantics { stateDescription = if (state.busy) "加载中" else "就绪" }, contentPadding = PaddingValues(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    item { Text("选择弹幕", style = MaterialTheme.typography.headlineSmall) }
                    if (!state.isConfigured) {
                        item {
                            ActionButton(onClick = onSettings, modifier = Modifier.focusRequester(entry).testTag("danmaku-open-settings")) {
                                Text("前往设置")
                            }
                        }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                TvTextInput(keyword, { keyword = it }, ::match, entry, Modifier.weight(1f).testTag("danmaku-query"),
                                    hint = "作品名称")
                                ActionButton(onClick = ::match, enabled = keyword.isNotBlank(),
                                    modifier = Modifier.testTag("danmaku-search")) { Text("匹配") }
                            }
                        }
                        if (state.busy) item { Text(if (state.track == null) "正在加载弹幕…" else "正在比较弹幕…") }
                        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                        item { Text("弹幕来源", style = MaterialTheme.typography.titleMedium) }
                        items(state.titles, key = { "title-${it.id}" }) { result ->
                            SelectionChip(selected = state.selectedTitle == result, onClick = { state.select(result) },
                                modifier = Modifier.fillMaxWidth().testTag("danmaku-title-${result.id}")) {
                                Text(listOf(result.title, result.source).filter { it.isNotBlank() }.joinToString(" · "),
                                    modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("剧集", style = MaterialTheme.typography.titleMedium)
                                    ActionButton(onClick = { scope.launch { requestFocusSafely(currentEpisodeFocus) } }, enabled = state.selectedEpisode != null, height = 44.dp) { Text("当前集") }
                                }
                                FlowRow(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    state.episodes.forEach { episode ->
                                        SelectionChip(selected = state.selectedEpisode == episode, onClick = { state.select(episode) },
                                            modifier = Modifier.then(if (state.selectedEpisode == episode) Modifier.focusRequester(currentEpisodeFocus) else Modifier).testTag("danmaku-episode-${episode.id}")) {
                                            val label = episode.title.ifBlank {
                                                if (episode.number.isBlank()) "未命名剧集" else "第 ${episode.number} 集"
                                            }
                                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
