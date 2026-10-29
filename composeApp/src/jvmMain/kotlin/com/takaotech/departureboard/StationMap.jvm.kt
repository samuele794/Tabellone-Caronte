@file:OptIn(ExperimentalMaplibreComposeApi::class)

package com.takaotech.departureboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.takaotech.departureboard.rfi.LatLng
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.ProvideMapPresentationHost
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

private val osName = System.getProperty("os.name").orEmpty().lowercase()
private val isMac = "mac" in osName
private val isWindows = "windows" in osName
private val isLinux = "linux" in osName

/**
 * MapLibre Compose chiede Java 25 e ha il motore nativo per macOS su Apple Silicon, Windows e
 * Linux (vedi i runtimeOnly in build.gradle.kts).
 */
actual val StationMapSupported: Boolean =
    Runtime.version().feature() >= 25 &&
            ((isMac && System.getProperty("os.arch") == "aarch64") || isWindows || isLinux)

/**
 * Con Nucleus la composizione della finestra non gira sul thread di `Dispatchers.Main` (quello
 * dell'event loop di Tao), che MapLibre prenderebbe come thread principale: runtime della mappa e
 * accesso alla GPU passano dal dispatcher della composizione.
 */
@Composable
actual fun StationMap(
    stations: List<MapStation>,
    selected: LatLng?,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    onStationClick: (MapStation) -> Unit,
    focus: MapFocus?,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val main = remember(scope) {
        CompositionThreadDispatcher(scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    }
    val runtime = remember(main) { createMapRuntime { mainDispatcher = main } }
    DisposableEffect(runtime) { onDispose { runtime.close() } }
    // Il contesto GPU della finestra Tao manca finché la finestra non ha il primo frame.
    val gpu = rememberTaoGpuRenderContext()
    val host = remember(gpu, main) { gpu?.let { nucleusMapHost(it, main) } }
    if (host == null) {
        Box(modifier.background(Color(0xFF1C1E24)))
        return
    }
    ProvideMapPresentationHost(host) {
        StationMapContent(
            stations,
            selected,
            camera,
            onCameraChange,
            onStationClick,
            focus,
            modifier,
            runtime
        )
    }
}

/**
 * Il dispatcher della composizione, che sa di essere già sul suo thread: quello di Compose
 * (`FlushCoroutineDispatcher`) chiede sempre di accodare, e MapLibre lo scambierebbe per un thread
 * principale non ancora partito. Va creato durante la composizione, sul suo thread.
 */
private class CompositionThreadDispatcher(private val delegate: CoroutineDispatcher) :
    CoroutineDispatcher() {
    val thread: Thread = Thread.currentThread()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        Thread.currentThread() !== thread

    override fun dispatch(context: CoroutineContext, block: Runnable) =
        delegate.dispatch(context, block)
}

/**
 * Host di MapLibre sul contesto GPU con cui Nucleus disegna la finestra: la mappa si disegna su una
 * texture dello stesso device, che Compose campiona senza copie. MapLibre Compose conosce solo le
 * finestre AWT; con Tao l'host si costruisce dalle fabbriche per sistema operativo. null con un
 * backend che MapLibre non sa usare.
 */
private fun nucleusMapHost(
    context: TaoGpuRenderContext,
    composition: CompositionThreadDispatcher,
): ComposeMapPresentationHost? {
    val access = GpuAccess(context, composition)
    return when (context) {
        is TaoMetalRenderContext -> {
            val gpu =
                MetalComposeGpuContext(context.skiaContext, NativeHandle(context.metalDevicePtr))
            ComposeMapPresentationHost.macosMetal("finestra Nucleus (Metal)", { gpu }, access::run)
        }

        is TaoOpenGlRenderContext -> {
            val gpu = OpenGlComposeGpuContext(context.skiaContext) { action ->
                checkNotNull(context.withContextCurrent { action.run() }) {
                    "Contesto OpenGL della finestra non disponibile"
                }
            }
            // Su Windows Nucleus usa OpenGL ES di ANGLE sopra Direct3D 11, su Linux EGL.
            if (isWindows) {
                ComposeMapPresentationHost.windowsAngle(
                    "finestra Nucleus (ANGLE)",
                    { gpu },
                    access::run
                )
            } else {
                ComposeMapPresentationHost.linuxOpenGl(
                    "finestra Nucleus (OpenGL)",
                    { gpu },
                    access::run
                )
            }
        }
        // Gerarchia sealed di Nucleus: un backend nuovo resta senza mappa finché non lo si collega.
        else -> null
    }
}

/**
 * Accesso esclusivo al contesto Skia della finestra, come lo vuole MapLibre: da qualunque thread,
 * sincrono, e diretto se si è già dentro un'azione. Nucleus invece accetta chiamate solo dal thread
 * della composizione: dagli altri ci si passa e si aspetta, come fa MapLibre con l'event thread di
 * AWT.
 */
private class GpuAccess(
    private val context: TaoGpuRenderContext,
    private val composition: CompositionThreadDispatcher,
) {
    private val inside = ThreadLocal.withInitial { false }

    fun run(action: Runnable) {
        if (inside.get()) return action.run()
        val guarded = {
            inside.set(true)
            try {
                action.run()
            } finally {
                inside.set(false)
            }
        }
        if (Thread.currentThread() === composition.thread) {
            context.runOnGpuThread(guarded)
        } else {
            runBlocking(composition) { context.runOnGpuThread(guarded) }
        }
    }
}
