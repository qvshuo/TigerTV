package art.anjing.tigertv.ui.home

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
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
import art.anjing.tigertv.ui.components.ScreenTitle
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.UiSpacing
import art.anjing.tigertv.ui.components.SelectionChip
import art.anjing.tigertv.ui.components.SearchButton
import art.anjing.tigertv.ui.components.TvTextInput
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.RecentPlayback
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(viewModel: TigerTVViewModel, onSearch: () -> Unit, onSettings: () -> Unit, onResults: () -> Unit,
    onRecent: (RecentPlayback) -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle == Lifecycle.State.RESUMED && LocalWindowInfo.current.isWindowFocused
    var returningFromSearch by rememberSaveable { mutableStateOf(false) }
    var entryFocusPending by rememberSaveable { mutableStateOf(true) }
    var returnCard by rememberSaveable { mutableStateOf<String?>(null) }
    var returnRecent by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(active) {
        if (active) viewModel.refreshRecentPlayback()
    }
    val recent = viewModel.recentPlayback
    val desiredRecent = if (active) returnRecent else null

    LaunchedEffect(Unit) { viewModel.loadHotSearchCategories() }
    LaunchedEffect(active) {
        if (!active || returnCard != null || returnRecent != null) return@LaunchedEffect
        if (entryFocusPending || returningFromSearch) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            requestFocusSafely(searchFocus)
            entryFocusPending = false
            returningFromSearch = false
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelHotSearchWarmup() }
    }
    LaunchedEffect(viewModel.selectedHotCategory, viewModel.hotSearchRevision) {
        viewModel.loadHotSearches()
    }
    val desiredCard = if (active && desiredRecent == null && viewModel.recentPlaybackLoaded && !viewModel.isLoadingHotSearches) {
        returnCard
    } else null
    val hotItems = viewModel.hotSearches
    LaunchedEffect(desiredCard, hotItems, recent.isNotEmpty()) {
        if (desiredCard == null) return@LaunchedEffect
        val index = hotItems.indexOfFirst { it.id == desiredCard }
        if (index >= 0) {
            val rowIndex = index / 5 + if (recent.isEmpty()) 2 else 3
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) listState.scrollToItem(rowIndex)
            requestFocusSafely(cardFocus)
        } else {
            requestFocusSafely(contentFocus)
        }
        returnCard = null
        entryFocusPending = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in homeNavigationKeys) {
                entryFocusPending = false
                returnCard = null
                returnRecent = null
            }
            false
        },
        state = listState,
        contentPadding = PaddingValues(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(UiMetrics.cardGap),
    ) {
        item("home-header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(stringResource(R.string.app_name), Modifier.weight(1f))
                IconButton(scale = IconButtonDefaults.scale(focusedScale = 1f), onClick = { returningFromSearch = true; onSearch() },
                    modifier = Modifier.size(UiMetrics.controlHeight).focusRequester(searchFocus).testTag("home-search")) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
                IconButton(scale = IconButtonDefaults.scale(focusedScale = 1f), onClick = { entryFocusPending = false; onSettings() },
                    modifier = Modifier.size(UiMetrics.controlHeight).testTag("home-settings")) {
                    Icon(Icons.Rounded.Settings, contentDescription = "设置")
                }
            }
        }
        if (recent.isNotEmpty()) {
            item("home-recent") {
                Column {
                    RecentPlaybackRow(recent, viewModel::workCover, viewModel::rememberRecentCover, desiredRecent, onFocusRestored = {
                        returnRecent = null
                        entryFocusPending = false
                    }) {
                        returnRecent = it.workId
                        onRecent(it)
                    }
                }
            }
        }
        if (viewModel.hotSearchCategories.isNotEmpty()) {
            item("hot-categories") {
                Column(Modifier.fillMaxWidth()) {
                    Text("热门推荐", style = MaterialTheme.typography.titleLarge)
                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpacing.choiceColumn),
                        contentPadding = PaddingValues(horizontal = UiSpacing.sm, vertical = UiSpacing.choiceRow)) {
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
                }
            }
            if (hotItems.isEmpty()) {
                item("hot-status") {
                    if (viewModel.isLoadingHotSearches) {
                        LoadingOverlay("加载推荐…", Modifier.fillMaxWidth().height(72.dp))
                    } else {
                        ErrorMessage("推荐暂不可用", onRetry = viewModel::retryHotSearches, compact = true, focusRetry = false)
                    }
                }
            } else {
                hotSearchRows(hotItems, desiredCard, cardFocus) { item ->
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

private val homeNavigationKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

@Composable
internal fun SearchScreen(viewModel: TigerTVViewModel, onResults: () -> Unit) {
    val focus = remember { FocusRequester() }
    fun search() { if (viewModel.search()) onResults() }
    LaunchedEffect(Unit) { requestFocusSafely(focus) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("搜索")
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
