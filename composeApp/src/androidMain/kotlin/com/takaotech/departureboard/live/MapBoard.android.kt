package com.takaotech.departureboard.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Su mobile le finestre non si ridimensionano: si portano a tutto schermo. */
internal actual val ResizableMapWindows: Boolean = false

internal actual fun WindowEdge.pointerIcon(): PointerIcon = PointerIcon.Default

/**
 * Blocchi attivi (intro, tabellone, finestra a tutto schermo): si sovrappongono durante le
 * transizioni, e la rotazione torna libera solo quando cade l'ultimo.
 */
private var landscapeLocks = 0

/**
 * Ruotare ricrea l'Activity: quella vecchia rilascia i suoi blocchi senza liberare la rotazione,
 * e quella nuova li riprende.
 */
@Composable
internal actual fun LandscapeLock() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        landscapeLocks++
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            landscapeLocks--
            if (landscapeLocks == 0 && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

@Composable
internal actual fun SystemBarsShown() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.hide(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
