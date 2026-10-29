package com.takaotech.departureboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.takaotech.departureboard.rfi.LatLng

/** MapLibre Compose non ha un target wasmJs. */
actual val StationMapSupported: Boolean = false

@Composable
actual fun StationMap(
    stations: List<MapStation>,
    selected: LatLng?,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    onStationClick: (MapStation) -> Unit,
    focus: MapFocus?,
    modifier: Modifier,
) = Unit
