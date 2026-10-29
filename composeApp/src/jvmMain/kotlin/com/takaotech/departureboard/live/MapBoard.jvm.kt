package com.takaotech.departureboard.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/** Sul desktop si usa il puntatore: le finestre si allargano dai bordi. */
internal actual val ResizableMapWindows: Boolean = true

internal actual fun WindowEdge.pointerIcon(): PointerIcon = PointerIcon(
    Cursor(
        when (this) {
            WindowEdge.Left -> Cursor.W_RESIZE_CURSOR
            WindowEdge.Right -> Cursor.E_RESIZE_CURSOR
            WindowEdge.Bottom -> Cursor.S_RESIZE_CURSOR
            WindowEdge.BottomLeft -> Cursor.SW_RESIZE_CURSOR
            WindowEdge.BottomRight -> Cursor.SE_RESIZE_CURSOR
        }
    )
)

@Composable
internal actual fun LandscapeLock() = Unit
