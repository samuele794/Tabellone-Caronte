package com.takaotech.departureboard.fnm

internal actual fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray =
    PureCrypto.aesEcbDecrypt(data, key)

internal actual fun sha256(data: ByteArray): ByteArray = PureCrypto.sha256(data)
