package fnm

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** L'implementazione in Kotlin puro (usata nel browser) deve dare gli stessi byte di JCA. */
class PureCryptoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex() =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun jcaEncrypt(plain: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            .doFinal(plain)

    @Test
    fun sha256KnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PureCrypto.sha256(ByteArray(0)).hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PureCrypto.sha256("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun sha256MatchesJca() {
        val random = Random(42)
        // Lunghezze attorno ai confini dei blocchi da 64 byte (55, 56, 64…).
        for (size in (0..130) + listOf(1000, 4096)) {
            val data = random.nextBytes(size)
            assertContentEquals(
                MessageDigest.getInstance("SHA-256").digest(data),
                PureCrypto.sha256(data),
                "size $size"
            )
        }
    }

    @Test
    fun aesFips197Vector() {
        // FIPS-197, appendice C.3 (AES-256), blocco singolo senza padding: si aggiunge un blocco
        // di padding cifrato con JCA e si controlla il primo blocco in chiaro.
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val plain = hex("00112233445566778899aabbccddeeff")
        val cipher = jcaEncrypt(plain, key)
        assertEquals("8ea2b7ca516745bfeafc49904b496089", cipher.copyOf(16).hex())
        assertContentEquals(plain, PureCrypto.aesEcbDecrypt(cipher, key))
    }

    @Test
    fun aesMatchesJca() {
        val random = Random(7)
        for (size in (0..48) + listOf(1000, 5000)) {
            val key = random.nextBytes(32)
            val plain = random.nextBytes(size)
            assertContentEquals(
                plain,
                PureCrypto.aesEcbDecrypt(jcaEncrypt(plain, key), key),
                "size $size"
            )
        }
    }

    @Test
    fun decryptsBffResponse() {
        val expected = BffResponses.train2162()
        val key = PureCrypto.sha256(FnmKeyStore.DEFAULT_PASSPHRASE.encodeToByteArray())
        assertEquals(
            expected,
            PureCrypto.aesEcbDecrypt(BffResponses.encrypt(expected), key).decodeToString()
        )
    }

    @Test
    fun wrongKeyFailsLikeJca() {
        val body = BffResponses.encrypt(BffResponses.train2162())
        val wrong = PureCrypto.sha256("chiave sbagliata".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { PureCrypto.aesEcbDecrypt(body, wrong) }
    }
}
