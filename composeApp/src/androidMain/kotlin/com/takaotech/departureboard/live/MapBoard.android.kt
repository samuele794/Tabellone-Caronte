package com.takaotech.departureboard.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalContext

/** Su mobile le finestre non si ridimensionano: si portano a tutto schermo. */
internal actual val ResizableMapWindows: Boolean = false

internal actual fun WindowEdge.pointerIcon(): PointerIcon = PointerIcon.Default

/**
 * Ruotare ricrea l'Activity: l'orientamento di prima si salva come stato, così quella nuova non
 * prende per suo l'orizzontale già chiesto, e non si ripristina durante la ricreazione.
 */
@Composable
internal actual fun LandscapeLock() {
    val activity = LocalContext.current.findActivity() ?: return
    val previous = rememberSaveable { activity.requestedOrientation }
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (!activity.isChangingConfigurations) activity.requestedOrientation = previous
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
