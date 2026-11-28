package art.anjing.tigertv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.ListItemDefaults
import art.anjing.tigertv.ui.player.PlaybackPreferences
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.ScreenTitle
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.TvTextInput
import art.anjing.tigertv.ui.player.DanmakuPreferences
import art.anjing.tigertv.ui.requestFocusSafely
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun SettingsScreen() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val preferences = remember(context) { DanmakuPreferences(context) }
    val playback = remember { PlaybackPreferences(context) }
    var autoNext by remember { mutableStateOf(playback.autoNext) }
    var rememberSpeed by remember { mutableStateOf(playback.rememberSpeed) }
    var filterAds by remember { mutableStateOf(playback.filterAds) }
    var saved by remember { mutableStateOf(preferences.server) }
    var draft by remember { mutableStateOf(saved) }
    var savedNotice by remember { mutableStateOf(false) }
    LaunchedEffect(savedNotice, saved) {
        if (savedNotice) {
            delay(2500)
            savedNotice = false
        }
    }
    val focus = remember { FocusRequester() }
    val address = draft.trim().trimEnd('/')
    val valid = address.isEmpty() || address.toHttpUrlOrNull()?.let { it.query == null && it.fragment == null } == true
    LaunchedEffect(Unit) { requestFocusSafely(focus) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle("设置")
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("播放", style = MaterialTheme.typography.titleLarge)
                PlaybackSetting("自动连播", autoNext) { autoNext = it; playback.autoNext = it }
                PlaybackSetting("记忆倍速", rememberSpeed) { rememberSpeed = it; playback.rememberSpeed = it }
                PlaybackSetting("去除广告", filterAds) { filterAds = it; playback.filterAds = it }
                Text("弹幕", style = MaterialTheme.typography.titleLarge)
                Text("弹幕服务地址", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvTextInput(draft, { draft = it; savedNotice = false }, {}, focus, Modifier.weight(1f).testTag("danmaku-server"),
                        hint = "https://danmu.example.com", imeAction = ImeAction.Done, focusedScale = 1f)
                    ActionButton(onClick = {
                        keyboard?.hide()
                        preferences.server = address
                        saved = address
                        draft = address
                        savedNotice = true
                    }, enabled = valid && address != saved.trimEnd('/'), modifier = Modifier.testTag("danmaku-save")) { Text("保存") }
                }
                if (address.isNotEmpty() && !valid) Text("请输入有效的弹幕服务地址", color = MaterialTheme.colorScheme.error)
                else if (savedNotice) {
                    Text("已保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PlaybackSetting(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    DenseListItem(
        selected = enabled,
        scale = ListItemDefaults.scale(focusedScale = UiMetrics.quarterFocusScale),
        onClick = { onChange(!enabled) },
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
        trailingContent = { Text(if (enabled) "开启" else "关闭", style = MaterialTheme.typography.labelLarge) },
    )
}
