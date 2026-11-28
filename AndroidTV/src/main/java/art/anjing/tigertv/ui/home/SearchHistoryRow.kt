package art.anjing.tigertv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.anjing.tigertv.ui.components.ActionButton
import art.anjing.tigertv.ui.components.UiMetrics
import art.anjing.tigertv.ui.components.UiSpacing
import androidx.tv.material3.MaterialTheme
import art.anjing.tigertv.ui.components.SelectionChip
import androidx.tv.material3.Text
import art.anjing.tigertv.R

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun SearchHistoryRow(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge
            )
            ActionButton(onClick = onClear, height = UiMetrics.compactControlHeight, colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.clear_history))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(history) { item ->
                SelectionChip(
                    selected = false,
                    verticalPadding = UiSpacing.xs,
                    onClick = { onSelect(item) },
                    onLongClick = { onRemove(item) },
                ) {
                    Text(item, maxLines = 1)
                }
            }
        }
    }
}
