package art.anjing.tigertv.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.InputChip
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
            ActionButton(onClick = onClear, height = UiMetrics.chipHeight, colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.clear_history), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(history) { item ->
                InputChip(
                    selected = false,
                    onClick = { onSelect(item) },
                    onLongClick = { onRemove(item) },
                    modifier = Modifier.heightIn(min = UiMetrics.chipHeight),
                ) {
                    Box(Modifier.heightIn(min = UiMetrics.chipHeight - 16.dp), contentAlignment = Alignment.Center) {
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
