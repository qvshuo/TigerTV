package art.anjing.tigertv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.UiMotion
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.SelectionChip
import art.anjing.tigertv.ui.components.CardFocusScroll
import art.anjing.tigertv.ui.components.SearchButton
import art.anjing.tigertv.ui.components.TvTextInput
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(viewModel: TigerTVViewModel, onSearch: () -> Unit, onSettings: () -> Unit, onResults: () -> Unit) {
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle == Lifecycle.State.RESUMED && LocalWindowInfo.current.isWindowFocused
    var hotContentHeight by remember { mutableStateOf(72.dp) }
    var returningFromSearch by rememberSaveable { mutableStateOf(false) }
    var initialCardFocus by remember { mutableStateOf(!returningFromSearch) }
    var returnCard by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadHotSearchCategories() }
    LaunchedEffect(active) {
        if (!active || returnCard != null) return@LaunchedEffect
        if (returningFromSearch) {
            requestFocusSafely(searchFocus)
            returningFromSearch = false
        } else if (initialCardFocus && viewModel.hotSearches.isEmpty()) {
            requestFocusSafely(contentFocus)
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelHotSearchWarmup() }
    }
    LaunchedEffect(viewModel.selectedHotCategory, viewModel.hotSearchRevision) {
        viewModel.loadHotSearches()
    }
    val desiredCard = if (active && !viewModel.isLoadingHotSearches) {
        returnCard ?: viewModel.hotSearches.firstOrNull()?.id?.takeIf { initialCardFocus }
    } else null
    LaunchedEffect(desiredCard, viewModel.hotSearches) {
        if (desiredCard != null && viewModel.hotSearches.none { it.id == desiredCard }) {
            requestFocusSafely(contentFocus)
            returnCard = null
            initialCardFocus = false
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides HomeFocusScroll) {
        Column(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in homeNavigationKeys) {
                initialCardFocus = false
                returnCard = null
            }
            false
        }) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("热门推荐", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = { returningFromSearch = true; onSearch() },
                        modifier = Modifier.size(UiMetrics.controlHeight).focusRequester(searchFocus).testTag("home-search")) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { initialCardFocus = false; onSettings() },
                        modifier = Modifier.size(UiMetrics.controlHeight).testTag("home-settings")) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                }
                if (viewModel.hotSearchCategories.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                            items(viewModel.hotSearchCategories, key = { it.id }) { category ->
                                SelectionChip(selected = category.id == viewModel.selectedHotCategory,
                                    onClick = { viewModel.selectHotSearchCategory(category.id) },
                                    modifier = Modifier
                                        .then(if (category.id == viewModel.selectedHotCategory) Modifier.focusRequester(contentFocus) else Modifier)
                                        .heightIn(min = UiMetrics.chipHeight)) {
                                    Box(Modifier.heightIn(min = UiMetrics.chipHeight - 16.dp), contentAlignment = Alignment.Center) {
                                        Text(category.name)
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().heightIn(min = hotContentHeight), contentAlignment = Alignment.Center) {
                            Crossfade(targetState = when {
                                viewModel.isLoadingHotSearches -> 0
                                viewModel.hotSearches.isEmpty() -> 1
                                else -> 2
                            }, animationSpec = tween(UiMotion.durationMillis), label = "hot content") { state ->
                                if (state == 0) {
                                    LoadingOverlay(message = "正在加载热门内容…", modifier = Modifier.fillMaxWidth().height(hotContentHeight))
                                } else if (state == 1) {
                                    ErrorMessage(message = "热门内容暂不可用，请重试", onRetry = viewModel::retryHotSearches, compact = true, focusRetry = false)
                                } else {
                                    HotSearchGrid(viewModel.hotSearches, Modifier.onSizeChanged {
                                        hotContentHeight = with(density) { it.height.toDp() }.coerceAtLeast(72.dp)
                                    }, restoreCardId = desiredCard, cardFocus = cardFocus, onFocusRestored = {
                                        returnCard = null
                                        initialCardFocus = false
                                    }) { item ->
                                        viewModel.onKeywordChange(item.title)
                                        if (viewModel.search()) {
                                            returnCard = item.id
                                            onResults()
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

private val homeNavigationKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

@OptIn(ExperimentalFoundationApi::class)
private object HomeFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val focusMargin = size * 0.05f
        if (offset >= focusMargin && offset + size + focusMargin <= containerSize) return 0f
        return CardFocusScroll.calculateScrollDistance(offset, size, containerSize)
    }
}

@Composable
internal fun SearchScreen(viewModel: TigerTVViewModel, onResults: () -> Unit) {
    val focus = remember { FocusRequester() }
    fun search() { if (viewModel.search()) onResults() }
    LaunchedEffect(Unit) { requestFocusSafely(focus) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("搜索", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvTextInput(viewModel.keyword, viewModel::onKeywordChange, ::search, focus, Modifier.weight(1f),
                hint = stringResource(R.string.search_hint))
            SearchButton(viewModel.isSearching, onClick = ::search)
        }
        viewModel.configErrorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
            ActionButton(onClick = viewModel::retryLoadConfig) { Text(stringResource(R.string.retry_load_config)) }
        }
        if (viewModel.searchHistory.isNotEmpty()) {
            SearchHistoryRow(viewModel.searchHistory,
                onSelect = { viewModel.onKeywordChange(it); search() },
                onRemove = viewModel::removeHistory, onClear = viewModel::clearHistory)
        }
    }
}
