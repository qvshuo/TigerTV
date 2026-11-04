package art.anjing.tigertv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.tv.material3.Border
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: TigerTVViewModel,
    onSearch: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    var hotContentHeight by remember { mutableStateOf(72.dp) }

    LaunchedEffect(Unit) {
        requestFocusSafely(focusRequester)
        viewModel.loadHotSearchCategories()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelHotSearchWarmup() }
    }

    LaunchedEffect(viewModel.selectedHotCategory, viewModel.hotSearchRevision) {
        viewModel.loadHotSearches()
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides CardFocusScroll) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = UiMetrics.pageHorizontalPadding, vertical = UiMetrics.pageVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchInput(
                        value = viewModel.keyword,
                        onValueChange = viewModel::onKeywordChange,
                        onSearch = {
                            if (viewModel.search()) onSearch()
                        },
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f)
                    )
                    SearchButton(isSearching = viewModel.isSearching) {
                        if (viewModel.search()) onSearch()
                    }
                }

                viewModel.configErrorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(onClick = { viewModel.retryLoadConfig() }) {
                        Text(stringResource(R.string.retry_load_config))
                    }
                }

                if (viewModel.searchHistory.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    SearchHistoryRow(
                        history = viewModel.searchHistory,
                        onSelect = {
                            viewModel.onKeywordChange(it)
                            if (viewModel.search()) onSearch()
                        },
                        onRemove = viewModel::removeHistory,
                        onClear = viewModel::clearHistory
                    )
                }
                if (viewModel.hotSearchCategories.isNotEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Column(Modifier.fillMaxWidth()) {
                        Text("豆瓣热门", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                            items(viewModel.hotSearchCategories, key = { it.id }) { category ->
                                SelectionChip(selected = category.id == viewModel.selectedHotCategory,
                                    onClick = { viewModel.selectHotSearchCategory(category.id) },
                                    modifier = Modifier.heightIn(min = UiMetrics.chipHeight)) {
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
                                    }) { title ->
                                        viewModel.onKeywordChange(title)
                                        if (viewModel.search()) onSearch()
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val state = rememberTextFieldState(initialText = value)
    val onChange by rememberUpdatedState(onValueChange)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var keyboardRequested by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) keyboardRequested = false
    }
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { onChange(it) }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        state = state,
        modifier = modifier
            .height(UiMetrics.controlHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { if (!it.isFocused) keyboardRequested = false }
            .onPreviewKeyEvent { event ->
                if (imeVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            // Start an input session on confirmation, including the first focus.
                            keyboardRequested = true
                            keyboard?.show()
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) {
                            focusManager.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        }
                        true
                    }
                    else -> false
                }
            },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = keyboardRequested),
        onKeyboardAction = {
            onChange(state.text.toString())
            keyboard?.hide()
            onSearch()
        },
        interactionSource = interactionSource,
        decorator = { innerTextField ->
            Surface(
                modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = Border(BorderStroke(if (isFocused) 2.dp else 1.dp,
                    if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.border))
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    innerTextField()
                }
            }
        }
    )
}
