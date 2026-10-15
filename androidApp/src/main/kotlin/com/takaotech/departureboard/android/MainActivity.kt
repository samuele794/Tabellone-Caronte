package com.takaotech.departureboard.android

import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.takaotech.departureboard.android.MainActivity.Companion.ECO_BRIGHTNESS
import com.takaotech.departureboard.live.LiveBoard
import com.takaotech.departureboard.live.rememberNow

class MainActivity : ComponentActivity() {
    private val board: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tabellone a tutto schermo: le barre di sistema ricompaiono con uno swipe dal bordo.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val eco = board.state.ecoMode
            LaunchedEffect(eco) { applyEcoMode(eco) }
            MaterialTheme {
                LiveBoard(board.state, now = rememberNow(), charging = rememberIsCharging())
            }
        }
    }

    /**
     * Modalità risparmio della finestra: luminosità al più [ECO_BRIGHTNESS] (mai più alta di
     * quella scelta dall'utente) e il refresh più basso del display, che per 5 scatti al secondo
     * basta e avanza. Spenta, decide di nuovo il sistema.
     */
    private fun applyEcoMode(eco: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (eco) {
                minOf(ECO_BRIGHTNESS, systemBrightness())
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            preferredDisplayModeId = if (eco) lowestRefreshMode() else 0
        }
    }

    /** Luminosità impostata dall'utente, 0..1 (con quella automatica, il valore del cursore). */
    private fun systemBrightness(): Float =
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            .coerceIn(1, 255) / 255f

    /** Modalità del display con il refresh più basso alla risoluzione attuale; 0 = nessuna preferenza. */
    private fun lowestRefreshMode(): Int {
        val display = ContextCompat.getDisplayOrDefault(this)
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { it.refreshRate }?.modeId ?: 0
    }

    private companion object {
        const val ECO_BRIGHTNESS = 0.3f
    }
}
