package art.anjing.tigertv.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.tv.material3.Icon
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
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
import art.anjing.tigertv.ui.components.GlassButton
import art.anjing.tigertv.ui.components.UiMotion
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.tvGlass
import art.anjing.tigertv.ui.components.SearchButton
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "全网好剧，一搜即达",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

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
                GlassButton(onClick = { viewModel.retryLoadConfig() }) {
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
                    Text("豆瓣热门", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(16.dp))
                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        items(viewModel.hotSearchCategories, key = { it.id }) { category ->
                            val color by animateColorAsState(
                                if (category.id == viewModel.selectedHotCategory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                tween(UiMotion.durationMillis), label = "category selection")
                            GlassButton(onClick = { viewModel.selectHotSearchCategory(category.id) },
                                height = UiMetrics.chipHeight, contentPadding = PaddingValues(horizontal = 16.dp)) {
                                Text(category.name, color = color)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).animateContentSize(tween(UiMotion.durationMillis)), contentAlignment = Alignment.Center) {
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
                                FlowRow(Modifier.fillMaxWidth().onSizeChanged {
                                    if (!viewModel.isLoadingHotSearches && viewModel.hotSearches.isNotEmpty()) {
                                        hotContentHeight = with(density) { it.height.toDp() }.coerceAtLeast(72.dp)
                                    }
                                }.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    viewModel.hotSearches.forEach { title ->
                                        GlassButton(onClick = {
                                            viewModel.onKeywordChange(title)
                                            if (viewModel.search()) onSearch()
                                        }, height = UiMetrics.chipHeight,
                                            contentPadding = PaddingValues(horizontal = 16.dp)) {
                                            Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focus = animateFloatAsState(if (isFocused) 1f else 0f, tween(UiMotion.durationMillis), label = "search input glass focus")

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(UiMetrics.controlHeight)
            .focusRequester(focusRequester),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(Modifier.fillMaxSize().tvGlass(focus, radius = 16.dp)
                .padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Button(onClick = {
                        onValueChange("")
                        scope.launch { requestFocusSafely(focusRequester) }
                    }, modifier = Modifier.size(48.dp),
                        shape = ButtonDefaults.shape(CircleShape), contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.colors(containerColor = Color.Transparent)) {
                        Icon(Icons.Default.Close, contentDescription = "清空搜索内容", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    )
}
