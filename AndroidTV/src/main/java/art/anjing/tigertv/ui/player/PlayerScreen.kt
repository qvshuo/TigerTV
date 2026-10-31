package art.anjing.tigertv.ui.player

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import art.anjing.tigertv.ui.components.GlassButton
import art.anjing.tigertv.ui.components.BackButton
import art.anjing.tigertv.ui.components.LoadingOverlay
import art.anjing.tigertv.R
import art.anjing.tigertv.ui.requestFocusSafely
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel

@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: TigerTVViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val url = viewModel.resolvedPlaybackUrl
    val activity = context as? Activity
    // Overlays own D-pad focus; hide native transport controls underneath.
    val overlayVisible = viewModel.isResolvingPlayback || viewModel.playbackErrorMessage != null
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(viewModel.playbackErrorMessage) {
        if (viewModel.playbackErrorMessage != null) requestFocusSafely(retryFocus)
    }

    BackHandler {
        viewModel.clearPlayback()
        onBack()
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val controller = remember { Media3Controller(context) }
    val exoPlayer = controller.player

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()?.let(viewModel::playbackDidStart)
            }
            override fun onPlayerError(error: PlaybackException) {
                viewModel.setPlaybackError(error.message ?: "播放失败")
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            controller.close()
        }
    }

    DisposableEffect(url, viewModel.playbackRevision) {
        url?.let {
            controller.load(it)
            controller.play()
        }

        onDispose {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                // Resume only after a media item has been prepared.
                Lifecycle.Event.ON_RESUME -> {
                    if (exoPlayer.mediaItemCount > 0) controller.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = !overlayVisible
                    controllerAutoShow = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.useController = !overlayVisible
            },
            modifier = Modifier.fillMaxSize()
        )

        if (viewModel.isResolvingPlayback) {
            LoadingOverlay("正在准备播放…", Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)))
        }

        viewModel.playbackErrorMessage?.let { error ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassButton(
                            onClick = { viewModel.retryPlayback() },
                            modifier = Modifier.focusRequester(retryFocus),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                        BackButton(onClick = {
                            viewModel.clearPlayback()
                            onBack()
                        })
                    }
                }
            }
        }
    }
}
