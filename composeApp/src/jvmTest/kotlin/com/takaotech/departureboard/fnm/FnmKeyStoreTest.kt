package com.takaotech.departureboard.fnm

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FnmKeyStoreTest {

    private val dir = Files.createTempDirectory("fnm-key").toFile()
    private val path = File(dir, "nested/fnm-key.json").absolutePath

    private val clock = object : Clock {
        var now = Instant.parse("2026-10-14T15:00:00Z")
        override fun now() = now
        fun advance(by: Duration) {
            now += by
        }
    }

    private var downloads = 0
    private var js = FnmFixtures.webComponents

    private fun store(default: String = FnmKeyStore.DEFAULT_PASSPHRASE) =
        FnmKeyStore(path, { downloads++; js }, clock, default)

    private val expected = BffResponses.train2162()
    private val body = BffResponses.encrypt(expected)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `decifra con la chiave di default`() = runBlocking {
        assertEquals(expected, store().use { it.decrypt(body) })
        assertEquals(0, downloads)
    }

    @Test
    fun `chiave cambiata ricavata dal javascript e salvata`() = runBlocking {
        store(default = "chiave vecchia").use { s ->
            assertEquals(expected, s.decrypt(body))
            assertEquals(expected, s.decrypt(body))
        }
        assertEquals(1, downloads)

        // Al riavvio vale la chiave salvata, senza riscaricare.
        assertEquals(expected, store(default = "chiave vecchia").use { it.decrypt(body) })
        assertEquals(1, downloads)
    }

    @Test
    fun `senza chiave valida il javascript si riscarica al piu ogni 10 minuti`() = runBlocking {
        js = """this.generateKey(Q);var Q="nessuna""""
        store(default = "chiave vecchia").use { s ->
            assertFailsWith<IllegalStateException> { s.decrypt(body) }
            assertFailsWith<IllegalStateException> { s.decrypt(body) }
            assertEquals(1, downloads)

            clock.advance(10.minutes)
            js = FnmFixtures.webComponents
            assertEquals(expected, s.decrypt(body))
            assertEquals(2, downloads)
        }
    }

    @Test
    fun `chiave sbagliata non decifra`() {
        assertEquals(null, FnmCrypto.decrypt(body, "chiave vecchia"))
        assertEquals(expected, FnmCrypto.decrypt(body, FnmKeyStore.DEFAULT_PASSPHRASE))
    }
}
