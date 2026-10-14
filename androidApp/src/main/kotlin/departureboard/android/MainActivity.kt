package departureboard.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import live.LiveBoard
import live.LiveBoardState
import live.rememberNow
import rfi.RfiClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tabellone a tutto schermo: le barre di sistema ricompaiono con uno swipe dal bordo.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val client = remember { RfiClient() }
            DisposableEffect(client) { onDispose { client.close() } }
            val state = remember { LiveBoardState(client) }
            MaterialTheme {
                LiveBoard(state, now = rememberNow())
            }
        }
    }
}
