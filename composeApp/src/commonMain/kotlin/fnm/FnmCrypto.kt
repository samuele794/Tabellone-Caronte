package fnm

/**
 * Cifratura delle risposte del BFF Trenord: AES-256 in modalità ECB con padding PKCS7, chiave
 * = SHA-256 di una frase scritta nel JavaScript del sito (vedi [FnmKeyStore]).
 */
internal object FnmCrypto {

    /** Testo in chiaro, o null se [passphrase] non è la chiave giusta (padding non valido). */
    fun decrypt(body: ByteArray, passphrase: String): String? =
        try {
            aesEcbDecrypt(body, sha256(passphrase.encodeToByteArray())).decodeToString()
        } catch (e: Exception) {
            null
        }
}

/** AES/ECB/PKCS7; lancia un'eccezione se il padding non torna. */
internal expect fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray

internal expect fun sha256(data: ByteArray): ByteArray
