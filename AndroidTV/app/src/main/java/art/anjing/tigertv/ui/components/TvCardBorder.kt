package art.anjing.tigertv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme

val TvCardShape = RoundedCornerShape(18.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun tvCardFocusedBorder(shape: RoundedCornerShape = TvCardShape) = CardDefaults.border(
    focusedBorder = Border(
        border = BorderStroke(
            width = 3.dp,
            color = MaterialTheme.colorScheme.primary
        ),
        shape = shape
    )
)
