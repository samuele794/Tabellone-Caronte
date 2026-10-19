package com.takaotech.departureboard.live

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.BoardMode
import com.takaotech.departureboard.rfi.RfiBoard
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class RefreshRequestTest {

    private val milano = Station("1728", "MILANO CENTRALE")

    /** Tabellone vuoto; con [gate] il download resta in corso finché non lo si completa. */
    private class FakeSource(val gate: CompletableDeferred<Unit>? = null) : BoardSource {
        override val source = StationSource.Rfi
        override val defaultStation = Station("1728", "MILANO CENTRALE")
        var invalidations = 0
        override suspend fun stations() = listOf(defaultStation)
        override suspend fun board(station: Station, mode: BoardMode): RfiBoard {
            gate?.await()
            return RfiBoard(station.name, mode, emptyList(), emptyList())
        }

        override fun invalidateList() {
            invalidations++
        }

        override fun close() = Unit
    }

    private fun state(source: FakeSource = FakeSource()) =
        LiveBoardState(mapOf(StationSource.Rfi to source), initial = milano)

    @Test
    fun `aggiornare a mano interrompe l'attesa`() = runBlocking {
        val state = state()
        val wait = launch { state.waitForRefresh(10.minutes) }
        assertTrue(state.canRefreshNow)
        state.requestRefresh()
        withTimeout(5_000) { wait.join() }
    }

    @Test
    fun `durante un aggiornamento il bottone è spento e la richiesta non vale`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val state = state(FakeSource(gate))
        val refresh = launch { state.refresh() }
        withTimeout(5_000) { while (!state.loading) yield() }

        assertFalse(state.canRefreshNow)
        state.requestRefresh()
        gate.complete(Unit)
        refresh.join()

        assertTrue(state.canRefreshNow)
        assertNull(withTimeoutOrNull(200.milliseconds) { state.waitForRefresh(10.minutes) })
    }

    @Test
    fun `una richiesta in sospeso la soddisfa l'aggiornamento seguente`() = runBlocking {
        val state = state()
        state.requestRefresh()
        state.refresh()
        // La richiesta è già stata servita: l'attesa dopo l'aggiornamento è piena.
        assertNull(withTimeoutOrNull(200.milliseconds) { state.waitForRefresh(10.minutes) })
    }

    @Test
    fun `solo l'aggiornamento a mano riscarica anche l'elenco`() = runBlocking {
        val source = FakeSource()
        val state = state(source)
        state.refresh()
        assertEquals(0, source.invalidations)

        state.requestRefresh()
        state.refresh()
        assertEquals(1, source.invalidations)

        state.refresh()
        assertEquals(1, source.invalidations)
    }

    @Test
    fun `prima del ripristino delle impostazioni non si aggiorna a mano`() = runBlocking {
        val dir = Files.createTempDirectory("refresh").toFile()
        try {
            SettingsStore(dir.resolve("settings.json").absolutePath).use { store ->
                val state = LiveBoardState(
                    mapOf(StationSource.Rfi to FakeSource()),
                    settingsStore = store,
                )
                assertFalse(state.canRefreshNow)
                state.restoreSettings()
                assertTrue(state.canRefreshNow)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
