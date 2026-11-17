package art.anjing.tigertv.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
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

/** Nonblocking overlay and manual selection. Recreate with the playback-session key. */
@Composable
internal fun DanmakuPlayback(
    controller: Media3Controller, state: DanmakuState, title: String, menuOpen: Boolean, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentEpisodeFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val preferences = remember { context.applicationContext.getSharedPreferences("danmaku", 0) }
    var server by remember { mutableStateOf(preferences.getString("server", DEFAULT_DANMAKU_SERVER).orEmpty().ifBlank { DEFAULT_DANMAKU_SERVER }) }
    var keyword by remember { mutableStateOf(title) }
    AndroidView(factory = { DanmakuView(it) },
        update = { it.bind(controller.player, state.track, state.enabled, state.offsetMs) { controller.playbackTimeline } },
        onRelease = { it.release() }, modifier = Modifier.fillMaxSize().testTag("danmaku-overlay"))
    if (!menuOpen) return
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        cursorColor = MaterialTheme.colorScheme.primary, focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.border)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        var editing by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(editing) { if (editing != null) { withFrameNanos { }; keyboard?.show() } }
        fun editOnConfirm(name: String): Modifier = Modifier.onPreviewKeyEvent {
            if (it.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_UP) editing = name
                true
            } else false
        }
        val keyboardActions = KeyboardActions(onDone = { editing = null; keyboard?.hide(); focus.clearFocus() })
        val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)

        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        val dialogHeight = maxHeight
        Surface(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = dialogHeight).testTag("danmaku-options")
                .semantics { stateDescription = if (state.busy) "加载中" else "就绪" }, contentPadding = PaddingValues(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { Text("选择弹幕", style = MaterialTheme.typography.headlineSmall) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(readOnly = editing != "query", value = keyword, onValueChange = { keyword = it },
                            singleLine = true, colors = fieldColors, keyboardOptions = keyboardOptions.copy(showKeyboardOnFocus = false), keyboardActions = keyboardActions,
                            label = { Text("作品名称") }, modifier = Modifier.weight(1f).then(editOnConfirm("query")).testTag("danmaku-query"))
                        ActionButton(onClick = {
                            editing = null; keyboard?.hide()
                            state.search(keyword, useSaved = false)
                        }, enabled = state.isConfigured && keyword.isNotBlank(), modifier = Modifier.testTag("danmaku-search")) { Text("匹配") }
                    }
                }
                if (state.busy) item { Text(if (state.track == null) "正在加载弹幕…" else "正在比较弹幕…") }
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("弹幕来源", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.titles.forEach { result ->
                            SelectionChip(selected = state.selectedTitle == result, onClick = { state.select(result) },
                                modifier = Modifier.fillMaxWidth().testTag("danmaku-title-${result.id}")) { Text(listOf(result.title, result.source).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("剧集", style = MaterialTheme.typography.titleMedium)
                        ActionButton(onClick = { scope.launch { art.anjing.tigertv.ui.requestFocusSafely(currentEpisodeFocus) } }, enabled = state.selectedEpisode != null, height = 44.dp) { Text("当前集") }
                    }
                    FlowRow(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.episodes.forEach { episode ->
                            SelectionChip(selected = state.selectedEpisode == episode, onClick = { state.select(episode) },
                                modifier = Modifier.then(if (state.selectedEpisode == episode) Modifier.focusRequester(currentEpisodeFocus) else Modifier).testTag("danmaku-episode-${episode.id}")) { Text("第 ${episode.number} 集") }
                        }
                    }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(readOnly = editing != "server", value = server, onValueChange = { server = it },
                            singleLine = true, colors = fieldColors, keyboardOptions = keyboardOptions.copy(showKeyboardOnFocus = false), keyboardActions = keyboardActions,
                            label = { Text("弹幕 API 地址") }, modifier = Modifier.weight(1f).then(editOnConfirm("server")).testTag("danmaku-server"))
                        ActionButton(onClick = {
                            state.configure(server)
                            preferences.edit().putString("server", server.trim()).apply()
                            state.search(title)
                            editing = null; keyboard?.hide(); focus.clearFocus()
                        }, enabled = server.isNotBlank(), modifier = Modifier.testTag("danmaku-save")) { Text("保存") }
                    }
                }

            }
        }
        }
    }
}

internal const val DEFAULT_DANMAKU_SERVER = "https://danmu-api.253520.xyz"
