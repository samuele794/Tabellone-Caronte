package com.takaotech.departureboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.takaotech.departureboard.live.Close
import com.takaotech.departureboard.live.label
import com.takaotech.departureboard.resources.Res
import com.takaotech.departureboard.resources.map_tooltip_close
import com.takaotech.departureboard.rfi.LatLng
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.neq
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stile OpenFreeMap Positron: tile vettoriali OpenStreetMap senza chiave, con colori tenui sotto
 * cui risaltano confini regionali e segnaposto.
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/** Sorgente vettoriale dello stile e suo layer dei confini amministrativi (schema OpenMapTiles). */
private const val TILES_SOURCE = "openmaptiles"
private const val BOUNDARY_LAYER = "boundary"

/** Livello OSM delle regioni italiane (le province sono il 6). */
private const val REGION_ADMIN_LEVEL = 4f

/** Ultimo layer dei confini dello stile: le regioni si disegnano sopra, ma sotto le etichette. */
private const val STYLE_BOUNDARIES_TOP = "boundary_disputed"

private val REGION_COLOR = Color(0xFF37474F)
private val REGION_CASING = Color.White

/**
 * La mappa resta sull'Italia: il centro non esce dal rettangolo che la contiene, isole comprese,
 * e non si allontana oltre la penisola intera.
 */
private val ITALY_CONSTRAINTS = CameraConstraints {
    boundingBox = BoundingBox(west = 6.6, south = 35.3, east = 18.6, north = 47.1)
    minZoom = 4.5
}

/** Zoom su una stazione: si vedono i binari e le vie attorno. */
private const val STATION_ZOOM = 15.0

/**
 * Volo sulla stazione scelta dalla ricerca: sempre la stessa durata, breve, invece di quella di
 * MapLibre che cresce con la distanza.
 */
private val FLY_TO_STATION = CameraAnimation.Fly { duration = 800.milliseconds }

/** Senza la stazione del tabellone si guarda l'Italia intera. */
private val ITALY = CameraPosition(center = Position(longitude = 12.5, latitude = 42.0), zoom = 5.0)

/** Segnaposto delle stazioni, tutti uguali: la stazione del tabellone non si distingue. */
private val STATION_COLOR = Color(0xFF1565C0)
private val ICON_SIZE = DpSize(24.dp, 24.dp)

/** Proprietà GeoJSON con l'indice della stazione in `stations`: le coordinate tornano arrotondate. */
private const val INDEX = "i"

/**
 * Mappa di [StationMap], uguale su Android e desktop; il desktop la avvolge nel suo host GPU e le
 * passa un [runtime] suo, null per quello predefinito.
 */
@Composable
internal fun StationMapContent(
    stations: List<MapStation>,
    selected: LatLng?,
    tooltip: MapStation?,
    onTooltipChange: (MapStation?) -> Unit,
    focus: MapFocus?,
    modifier: Modifier,
    runtime: MapRuntime? = null,
) {
    val camera =
        selected?.let { CameraPosition(center = it.toPosition(), zoom = STATION_ZOOM) } ?: ITALY
    val stationIcon = rememberVectorPainter(stationIcon(STATION_COLOR))
    val features = remember(stations) { stations.toFeatures() }
    val onClick: FeaturesClickHandler = { features ->
        val index = features.firstOrNull()?.properties?.get(INDEX)?.jsonPrimitive?.intOrNull
        stations.getOrNull(index ?: -1)?.let(onTooltipChange)
        ClickResult.Consume
    }
    val content: @Composable @MaplibreComposable () -> Unit = {
        RegionBoundaries()
        // La sorgente tiene i dati con cui nasce: quando arrivano altri elenchi se ne fa una nuova.
        key(features) {
            SymbolLayer(
                id = "stazioni",
                source = rememberGeoJsonSource(GeoJsonData.Features(features)),
                iconImage = image(stationIcon, ICON_SIZE),
                onClick = onClick,
                hitPadding = 4.dp,
            )
        }
    }
    val baseStyle = BaseStyle.Uri(STYLE_URL)
    val state = if (runtime != null) {
        rememberMapState(runtime, baseStyle, camera, content)
    } else {
        rememberMapState(baseStyle = baseStyle, initialCameraPosition = camera, content = content)
    }
    // Cambiando stazione con la mappa aperta, la camera la segue.
    LaunchedEffect(state, camera) { state.setCameraPosition(camera) }
    // Stazione scelta dalla ricerca: la mappa ci vola sopra.
    LaunchedEffect(state, focus) {
        val station = focus?.station ?: return@LaunchedEffect
        state.animateCamera(
            CameraUpdate(center = station.position.toPosition(), zoom = STATION_ZOOM),
            FLY_TO_STATION,
        )
    }
    MaplibreMap(
        modifier,
        state = state,
        cameraConstraints = ITALY_CONSTRAINTS,
        overlay = {
            // Senza scala e bussola in alto: lì c'è il bottone per tornare al tabellone.
            include(MapOverlay.AttributionOnly)
            tooltip?.let { station ->
                StationTooltip(
                    station,
                    onClose = { onTooltipChange(null) },
                    modifier = Modifier
                        .placedAt(station.position.toPosition(), Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                )
            }
        },
    )
}

/**
 * Confini delle regioni, più marcati di quelli dello stile: una linea scura su un alone bianco che
 * si ingrossa avvicinandosi. Prende i confini dalle tile dello stile, senza scaricare altro; prima
 * che lo stile sia caricato non disegna nulla.
 */
@Composable
@MaplibreComposable
private fun RegionBoundaries() {
    val tiles = getBaseSource<VectorSource>(TILES_SOURCE) ?: return
    // Solo i confini di terra: quelli in mare seguono le acque territoriali.
    val regions = all(
        feature["admin_level"].asNumber() eq const(REGION_ADMIN_LEVEL),
        feature["maritime"].asNumber(const(0f)) neq const(1f),
    )
    Anchor.Above(STYLE_BOUNDARIES_TOP) {
        LineLayer(
            id = "regioni-alone",
            source = tiles,
            sourceLayer = BOUNDARY_LAYER,
            filter = regions,
            color = const(REGION_CASING),
            opacity = const(0.8f),
            width = interpolate(linear(), zoom(), 5 to const(3.5.dp), 10 to const(6.dp)),
        )
        LineLayer(
            id = "regioni",
            source = tiles,
            sourceLayer = BOUNDARY_LAYER,
            filter = regions,
            color = const(REGION_COLOR),
            width = interpolate(linear(), zoom(), 5 to const(1.2.dp), 10 to const(2.5.dp)),
        )
    }
}

/**
 * Scheda della stazione toccata, sopra il suo segnaposto; la X la chiude.
 *
 * TODO: grafica definitiva della scheda stazione. Per ora è un tooltip provvisorio con nome e
 *  fornitori.
 */
@Composable
private fun StationTooltip(
    station: MapStation,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        Box {
            Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 40.dp, bottom = 10.dp)) {
                Text(station.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    station.sources.joinToString(" · ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(Close, stringResource(Res.string.map_tooltip_close), Modifier.size(18.dp))
            }
        }
    }
}

private fun LatLng.toPosition() = Position(longitude = lon, latitude = lat)

/** Le stazioni come punti GeoJSON, ognuna con il suo indice in questa lista. */
private fun List<MapStation>.toFeatures(): FeatureCollection<Point, JsonObject> =
    FeatureCollection(
        mapIndexed { i, station ->
            Feature(Point(station.position.toPosition()), buildJsonObject { put(INDEX, i) })
        },
    )

/**
 * Segnaposto a forma di stazione: un tondo di [color] bordato di bianco con il fabbricato
 * viaggiatori, tetto a capanna con l'orologio e la porta ad arco.
 */
private fun stationIcon(color: Color): ImageVector =
    ImageVector.Builder("Stazione", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(color), stroke = SolidColor(Color.White), strokeLineWidth = 1.5f) {
            moveTo(12f, 1f)
            arcTo(11f, 11f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 23f)
            arcTo(11f, 11f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 1f)
            close()
        }
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Tetto e facciata.
            moveTo(4.5f, 10.5f)
            lineTo(12f, 5f)
            lineTo(19.5f, 10.5f)
            lineTo(17.5f, 10.5f)
            lineTo(17.5f, 18.5f)
            lineTo(6.5f, 18.5f)
            lineTo(6.5f, 10.5f)
            close()
            // Orologio nel timpano.
            moveTo(12f, 7.6f)
            arcTo(1.3f, 1.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 10.2f)
            arcTo(1.3f, 1.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 7.6f)
            close()
            // Porta ad arco.
            moveTo(10.3f, 18.5f)
            lineTo(10.3f, 14.6f)
            arcTo(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13.7f, 14.6f)
            lineTo(13.7f, 18.5f)
            close()
        }
    }.build()
