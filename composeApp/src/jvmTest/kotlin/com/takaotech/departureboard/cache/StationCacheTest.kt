package com.takaotech.departureboard.cache

import com.takaotech.departureboard.rfi.LatLng
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StationCacheTest {

    private val dir = Files.createTempDirectory("station-cache").toFile()
    private val path = File(dir, "nested/stations.json").absolutePath

    private val clock = object : Clock {
        var now = Instant.parse("2026-01-31T10:00:00Z")
        override fun now() = now
        fun advance(by: Duration) {
            now += by
        }
    }

    private val milano = listOf(Station("1728", "MILANO CENTRALE"))
    private val napoli = listOf(Station("1888", "NAPOLI CENTRALE"))

    private var downloads = 0
    private fun fetching(stations: List<Station>): suspend () -> List<Station> = {
        downloads++
        stations
    }

    private fun cache() = StationCache(path, clock, TimeZone.UTC)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `senza cache scarica e salva, poi legge dalla cache`() = runBlocking {
        cache().use { c ->
            assertEquals(milano, c.stations(StationSource.Rfi, fetching(milano)))
            assertEquals(milano, c.stations(StationSource.Rfi, fetching(napoli)))
        }
        assertEquals(1, downloads)
    }

    @Test
    fun `l'elenco salvato si legge senza scaricare, anche scaduto`() = runBlocking {
        cache().use { c ->
            assertEquals(emptyList(), c.cached(StationSource.Rfi))
            c.stations(StationSource.Rfi, fetching(milano))
            clock.advance(60.days)
            assertEquals(milano, c.cached(StationSource.Rfi))
            assertEquals(emptyList(), c.cached(StationSource.Fnm))
        }
        assertEquals(1, downloads)
    }

    @Test
    fun `la cache sopravvive alla riapertura dello stesso file`() = runBlocking {
        cache().use { it.stations(StationSource.Rfi, fetching(milano)) }

        val stations = cache().use { it.stations(StationSource.Rfi, fetching(napoli)) }

        assertEquals(milano, stations)
        assertEquals(1, downloads)
    }

    @Test
    fun `scade dopo un mese di calendario`() = runBlocking {
        cache().use { c ->
            c.stations(StationSource.Rfi, fetching(milano))

            // Dal 31 gennaio un mese porta al 28 febbraio.
            clock.advance(28.days - 1.minutes)
            assertEquals(milano, c.stations(StationSource.Rfi, fetching(napoli)))

            clock.advance(1.minutes)
            assertEquals(napoli, c.stations(StationSource.Rfi, fetching(napoli)))
            assertEquals(napoli, c.stations(StationSource.Rfi, fetching(milano)))
        }
        assertEquals(2, downloads)
    }

    @Test
    fun `se il download fallisce usa l'elenco scaduto`() = runBlocking {
        cache().use { c ->
            c.stations(StationSource.Rfi, fetching(milano))
            clock.advance(60.days)

            assertEquals(milano, c.stations(StationSource.Rfi) { error("rete giù") })
        }
    }

    @Test
    fun `senza cache l'errore del download arriva al chiamante`() {
        assertFailsWith<IllegalStateException> {
            runBlocking { cache().use { it.stations(StationSource.Rfi) { error("rete giù") } } }
        }
    }

    @Test
    fun `un elenco vuoto non viene salvato`() = runBlocking {
        cache().use { c ->
            assertEquals(emptyList(), c.stations(StationSource.Rfi, fetching(emptyList())))
            assertEquals(milano, c.stations(StationSource.Rfi, fetching(milano)))
        }
        assertEquals(2, downloads)
    }

    @Test
    fun `un file illeggibile viene sostituito`() = runBlocking {
        File(path).apply { parentFile.mkdirs() }.writeText("{non è json")

        val stations = cache().use { it.stations(StationSource.Rfi, fetching(milano)) }

        assertEquals(milano, stations)
        assertEquals(milano, cache().use { it.stations(StationSource.Rfi, fetching(napoli)) })
    }

    @Test
    fun `un elenco incompleto si riscarica anche se non è scaduto`() = runBlocking {
        val located = listOf(Station("1728", "MILANO CENTRALE", LatLng(45.486347, 9.204528)))
        val hasPositions = { list: List<Station> -> list.any { it.position != null } }
        cache().use { c ->
            c.stations(StationSource.Rfi, fetching(milano))
            assertEquals(located, c.stations(StationSource.Rfi, hasPositions, fetching(located)))
            assertEquals(located, c.stations(StationSource.Rfi, hasPositions, fetching(milano)))
        }
        assertEquals(2, downloads)
    }

    @Test
    fun `un elenco incompleto resta il ripiego se il download fallisce`() = runBlocking {
        cache().use { c ->
            c.stations(StationSource.Rfi, fetching(milano))
            val stations = c.stations(StationSource.Rfi, { false }) { error("ViaggiaTreno giù") }
            assertEquals(milano, stations)
        }
    }
}
