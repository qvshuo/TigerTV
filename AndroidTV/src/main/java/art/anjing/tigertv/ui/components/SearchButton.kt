package art.anjing.tigertv.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchButton(isSearching: Boolean, onClick: () -> Unit) {
    ActionButton(
        onClick = onClick,
        enabled = !isSearching,
        colors = primaryActionColors(),
        modifier = Modifier.semantics { stateDescription = if (isSearching) "正在搜索…" else "" },
        contentPadding = PaddingValues(horizontal = UiSpacing.md, vertical = 0.dp)
    ) {
        Text(stringResource(R.string.search_button), style = MaterialTheme.typography.titleMedium)
    }
}
