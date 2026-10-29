package com.takaotech.departureboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.takaotech.departureboard.rfi.LatLng

actual val StationMapSupported: Boolean = true

@Composable
actual fun StationMap(
    stations: List<MapStation>,
    selected: LatLng?,
    tooltip: MapStation?,
    onTooltipChange: (MapStation?) -> Unit,
    focus: MapFocus?,
    modifier: Modifier,
) = StationMapContent(stations, selected, tooltip, onTooltipChange, focus, modifier)
