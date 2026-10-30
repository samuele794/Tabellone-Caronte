package com.takaotech.departureboard.intro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.live.BoardSource
import com.takaotech.departureboard.live.LiveBoardState
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class IntroHostTest {

    private object FakeSource : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1728", "MILANO CENTRALE")
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode) =
            RfiBoard(station.name, mode, emptyList(), emptyList())

        override fun close() = Unit
    }

    private class Owner : LifecycleOwner {
        val registry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val state = LiveBoardState(mapOf(StationSource.Rfi to FakeSource))
    private val owner = Owner()
    private var finished = 0
    private var nanos = 0L

    /** Vive più della composizione, come quella nel ViewModel su Android. */
    private val sessionScope = CoroutineScope(Dispatchers.Unconfined)
    private val session = IntroSession(sessionScope)

    /** false = l'intro esce dalla composizione, come quando una rotazione ricrea l'Activity. */
    private var shown by mutableStateOf(true)

    /** Composta al primo frame, così ogni test può prima cambiare le impostazioni. */
    private val sceneDelegate = lazy {
        ImageComposeScene(width = 800, height = 450, density = Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) {
                    IntroHost(
                        state,
                        player = null,
                        onFinished = { finished++ },
                        session = session
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    private val scene by sceneDelegate

    @AfterTest
    fun close() {
        if (sceneDelegate.isInitialized()) scene.close()
        sessionScope.cancel()
    }

    /** Avanza la scena di [millis] a frame di [step] ms. */
    private fun advance(millis: Long, step: Long = 16) {
        val end = nanos + millis * 1_000_000
        while (nanos < end) {
            nanos += step * 1_000_000
            scene.render(nanos).close()
        }
    }

    @Test
    fun `senza audio parte subito e finisce dopo la durata della musica`() {
        advance(100)
        assertTrue(state.introPlaying)
        advance(IntroTimeline.TOTAL - 1_000, step = 50)
        assertEquals(0, finished)
        advance(2_000, step = 50)
        assertEquals(1, finished)
        assertFalse(state.introPlaying)
    }

    @Test
    fun `si salta solo con SKIP, che compare dopo un attimo`() {
        advance(100)
        tap(skipButton)
        advance(800)
        assertEquals(0, finished)
        tap(Offset(400f, 225f))
        advance(IntroTimeline.SKIP_FADE + 100)
        assertEquals(0, finished)
        tap(skipButton)
        advance(IntroTimeline.SKIP_FADE + 100)
        assertEquals(1, finished)
        assertFalse(state.introPlaying)
    }

    @Test
    fun `spenta nelle impostazioni non parte`() {
        state.intro = false
        advance(100)
        assertEquals(1, finished)
        assertFalse(state.introPlaying)
    }

    @Test
    fun `con l'app in background finisce subito`() {
        advance(1_000)
        owner.registry.currentState = Lifecycle.State.CREATED
        advance(50)
        assertEquals(1, finished)
        assertFalse(state.introPlaying)
    }

    @Test
    fun `se esce solo dalla composizione riprende da dove era`() {
        advance(3_000)
        val reached = session.clock.longValue
        shown = false
        advance(500)
        assertEquals(0, finished)
        assertTrue(state.introPlaying)
        shown = true
        advance(100)
        // Né da capo né saltata: dal punto di prima (più il poco tempo vero passato fuori).
        assertTrue(session.clock.longValue in reached..reached + 1_000)
        assertEquals(0, finished)
        advance(IntroTimeline.TOTAL - reached + 500, step = 50)
        assertEquals(1, finished)
        assertFalse(state.introPlaying)
    }

    @Test
    fun `con il cruscotto il tabellone ci resta dentro anche senza intro`() {
        state.intro = false
        state.dashboard = true
        advance(100)
        assertEquals(1, finished)
        // Il tabellone del test è trasparente: dove c'è il cruscotto, l'angolo è pieno.
        assertTrue(cornerAlpha() > 0f)
        state.dashboard = false
        advance(50)
        assertEquals(0f, cornerAlpha())
    }

    /** Opacità del pixel in alto a sinistra al prossimo frame. */
    private fun cornerAlpha(): Float {
        nanos += 16_000_000
        return scene.render(nanos).use { it.toComposeImageBitmap().toPixelMap()[1, 1].alpha }
    }

    /** SKIP, in fondo a destra nella barra in alto (alta almeno 36 dp). */
    private val skipButton = Offset(800f - 4f, 18f)

    private fun tap(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
    }
}
