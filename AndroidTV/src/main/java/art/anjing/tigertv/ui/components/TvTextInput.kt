package art.anjing.tigertv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TvTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    onImeAction: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    hint: String,
    imeAction: ImeAction = ImeAction.Search,
    focusedScale: Float = UiMetrics.focusedScale,
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
    var isFocused by remember { mutableStateOf(false) }

    BasicTextField(
        state = state,
        modifier = modifier
            .height(UiMetrics.controlHeight)
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (!isFocused) keyboardRequested = false
            }
            .scale(if (isFocused) focusedScale else 1f)
            .onPreviewKeyEvent { event ->
                if (imeVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            if (keyboardRequested) keyboard?.show()
                            else keyboardRequested = true
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        val direction = when (event.key) {
                            Key.DirectionUp -> FocusDirection.Up
                            Key.DirectionDown -> FocusDirection.Down
                            Key.DirectionLeft -> FocusDirection.Left
                            else -> FocusDirection.Right
                        }
                        if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                        true
                    }
                    else -> false
                }
            },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, showKeyboardOnFocus = keyboardRequested),
        onKeyboardAction = {
            onChange(state.text.toString())
            keyboard?.hide()
            onImeAction()
        },
        decorator = { innerTextField ->
            Surface(
                modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = Border(BorderStroke(if (isFocused) 2.dp else 1.dp,
                    if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.border))
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    innerTextField()
                }
            }
        }
    )
}
