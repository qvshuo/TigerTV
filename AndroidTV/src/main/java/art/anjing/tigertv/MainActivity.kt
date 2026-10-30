package art.anjing.tigertv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.tv.material3.Surface
import art.anjing.tigertv.ui.components.tvGlassBackdrop
import art.anjing.tigertv.ui.TigerTVApp
import art.anjing.tigertv.ui.theme.TigerTVTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TigerTVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().tvGlassBackdrop()) {
                        TigerTVApp()
                    }
                }
            }
        }
    }
}
