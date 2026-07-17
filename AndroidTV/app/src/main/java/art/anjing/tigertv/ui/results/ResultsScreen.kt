package art.anjing.tigertv.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.ui.components.ErrorMessage
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.ui.components.TvCardShape
import art.anjing.tigertv.ui.components.tvCardFocusedBorder
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: TigerTVViewModel,
    onBack: () -> Unit,
    onSelectResult: (SearchResult) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
                Text(
                    text = "${stringResource(R.string.results_title)}: ${viewModel.submittedKeyword}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            when {
                viewModel.isSearching -> LoadingOverlay()
                viewModel.searchErrorMessage != null -> ErrorMessage(
                    message = viewModel.searchErrorMessage ?: stringResource(R.string.error_generic),
                    onRetry = {
                        viewModel.clearSearchError()
                        viewModel.search()
                    }
                )
                viewModel.results.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_no_results),
                        style = MaterialTheme.typography.displayMedium
                    )
                }
                else -> ResultsGrid(
                    results = viewModel.results,
                    onSelectResult = onSelectResult,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultsGrid(
    results: List<SearchResult>,
    onSelectResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstCardFocusRequester = remember { FocusRequester() }

    // 每当 results 变更（新搜索 / 重入屏导致 List 实例变化）即恢复首项焦点：
    // 去掉一次性 `focused` 标志——其在二次搜索时不会复位，导致焦点丢失。
    LaunchedEffect(results) {
        if (results.isNotEmpty()) {
            requestFocusSafely(firstCardFocusRequester)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(results) { index, result ->
            val focusModifier = if (index == 0) {
                Modifier.focusRequester(firstCardFocusRequester)
            } else {
                Modifier
            }
            Card(
                onClick = { onSelectResult(result) },
                modifier = focusModifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = CardDefaults.shape(shape = TvCardShape),
                border = tvCardFocusedBorder(),
                colors = CardDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = result.vodName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = result.site,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (result.vodRemarks.isNotEmpty()) {
                            Text(
                                text = result.vodRemarks,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
