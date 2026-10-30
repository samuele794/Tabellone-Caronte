package com.takaotech.departureboard.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon

/** Nel browser la mappa non c'è ([com.takaotech.departureboard.StationMapSupported]). */
internal actual val ResizableMapWindows: Boolean = false

internal actual fun WindowEdge.pointerIcon(): PointerIcon = PointerIcon.Default

@Composable
internal actual fun LandscapeLock() = Unit

@Composable
internal actual fun SystemBarsShown() = Unit
