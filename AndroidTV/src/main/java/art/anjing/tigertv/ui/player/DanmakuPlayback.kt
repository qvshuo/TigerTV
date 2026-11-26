package art.anjing.tigertv.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.UiSpacing
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.TvTextInput
import art.anjing.tigertv.ui.requestFocusSafely

@Composable
internal fun DanmakuPlayback(
    controller: Media3Controller, state: DanmakuState, title: String, menuOpen: Boolean, onClose: () -> Unit, onSettings: () -> Unit,
) {
    AndroidView(factory = { DanmakuView(it) },
        update = { it.bind(controller.player, state.track, state.enabled,
            loading = state.loadingStep.takeIf { controller.hasVideoFrame && !state.hasComments }) { controller.playbackTimeline } },
        onRelease = { it.release() }, modifier = Modifier.fillMaxSize().testTag("danmaku-overlay"))
    if (!menuOpen) return
    val entry = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var keyword by remember(title) { mutableStateOf(title) }
        val episodeRows = remember(state.episodes) { state.episodes.chunked(2) }
        val currentEpisode = state.selectedEpisode
        val busy = state.busy
        val error = state.error
        val titles = state.titles
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
                    .semantics { stateDescription = if (busy) "加载中" else "就绪" },
                    contentPadding = PaddingValues(UiSpacing.lg), verticalArrangement = Arrangement.spacedBy(UiSpacing.choiceRow)) {
                    item { Text("选择弹幕", style = MaterialTheme.typography.headlineSmall) }
                    if (!state.isConfigured) {
                        item {
                            ActionButton(onClick = onSettings, modifier = Modifier.focusRequester(entry).testTag("danmaku-open-settings")) {
                                Text("前往设置")
                            }
                        }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = UiSpacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(UiSpacing.choiceColumn), verticalAlignment = Alignment.CenterVertically) {
                                TvTextInput(keyword, { keyword = it }, ::match, entry, Modifier.weight(1f).testTag("danmaku-query"),
                                    hint = "作品名称")
                                ActionButton(onClick = ::match, enabled = keyword.isNotBlank(),
                                    modifier = Modifier.testTag("danmaku-search")) { Text("搜索") }
                            }
                        }
                        state.loadingStep?.let { step -> item { Text(step.label, style = MaterialTheme.typography.bodyMedium) } }
                        error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                        if (titles.isNotEmpty()) {
                            item { Text("来源", Modifier.padding(top = UiSpacing.sm), style = MaterialTheme.typography.titleMedium) }
                        }
                        items(titles, key = { "title-${it.id}" }) { result ->
                            SelectionChip(selected = state.selectedTitle == result, onClick = { state.select(result) },
                                modifier = Modifier.fillMaxWidth().testTag("danmaku-title-${result.id}")) {
                                Text(listOf(result.title, result.source).filter { it.isNotBlank() }.joinToString(" · "),
                                    modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (episodeRows.isNotEmpty()) item {
                            Text("剧集", Modifier.padding(top = UiSpacing.sm), style = MaterialTheme.typography.titleMedium)
                        }
                        itemsIndexed(episodeRows, key = { index, _ -> "episode-row-$index" }) { _, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpacing.choiceColumn)) {
                                row.forEach { episode ->
                                    SelectionChip(selected = currentEpisode == episode, onClick = { state.select(episode) },
                                        modifier = Modifier.weight(1f)
                                            .heightIn(min = UiMetrics.controlHeight)
                                            .testTag("danmaku-episode-${episode.id}")) {
                                        Text(episode.title.ifBlank {
                                            if (episode.number.isBlank()) "未命名剧集" else "第 ${episode.number} 集"
                                        }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
