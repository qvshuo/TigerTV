package art.anjing.tigertv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ListItem
import art.anjing.tigertv.ui.player.PlaybackPreferences
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.TvTextInput
import art.anjing.tigertv.ui.player.DEFAULT_DANMAKU_SERVER
import art.anjing.tigertv.ui.requestFocusSafely
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun SettingsScreen() {
    val context = LocalContext.current
    val preferences = remember { context.applicationContext.getSharedPreferences("danmaku", 0) }
    val playback = remember { PlaybackPreferences(context) }
    var autoNext by remember { mutableStateOf(playback.autoNext) }
    var rememberSpeed by remember { mutableStateOf(playback.rememberSpeed) }
    var filterAds by remember { mutableStateOf(playback.filterAds) }
    var saved by remember { mutableStateOf(preferences.getString("server", "").orEmpty()) }
    var draft by remember { mutableStateOf(saved.ifBlank { DEFAULT_DANMAKU_SERVER }) }
    val focus = remember { FocusRequester() }
    val address = draft.trim().trimEnd('/')
    val valid = address.toHttpUrlOrNull()?.let { it.query == null && it.fragment == null } == true
    LaunchedEffect(saved) { requestFocusSafely(focus) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("播放", style = MaterialTheme.typography.titleMedium)
                PlaybackSetting("自动下一集", autoNext) { autoNext = it; playback.autoNext = it }
                PlaybackSetting("记忆播放速度", rememberSpeed) { rememberSpeed = it; playback.rememberSpeed = it }
                PlaybackSetting("去广告", filterAds) { filterAds = it; playback.filterAds = it }
                Text("弹幕 API 地址", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvTextInput(draft, { draft = it }, {}, focus, Modifier.weight(1f).testTag("danmaku-server"),
                        hint = DEFAULT_DANMAKU_SERVER, imeAction = ImeAction.Done)
                    ActionButton(onClick = {
                        preferences.edit().putString("server", address).apply()
                        saved = address
                        draft = address
                    }, enabled = valid && address != saved.trimEnd('/'), modifier = Modifier.testTag("danmaku-save")) { Text("保存") }
                }
                if (address.isNotEmpty() && !valid) Text("请输入有效的 HTTP 或 HTTPS 地址", color = MaterialTheme.colorScheme.error)
                else if (saved.isNotBlank() && address == saved.trimEnd('/')) {
                    Text("已保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PlaybackSetting(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        selected = enabled,
        onClick = { onChange(!enabled) },
        headlineContent = { Text(label) },
        trailingContent = { Text(if (enabled) "开启" else "关闭") },
    )
}
