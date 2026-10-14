package fnm

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal actual fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray =
    Cipher.getInstance("AES/ECB/PKCS5Padding")
        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
        .doFinal(data)

internal actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
