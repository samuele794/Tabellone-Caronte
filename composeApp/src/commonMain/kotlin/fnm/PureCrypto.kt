package fnm

/**
 * AES-256 (solo decifratura, ECB con padding PKCS7) e SHA-256 in Kotlin puro, per le piattaforme
 * senza JCA: nel browser WebCrypto non ha la modalità ECB ed è solo asincrono. Su desktop e
 * Android si usa JCA; i test confrontano le due implementazioni.
 */
internal object PureCrypto {

    /** Come `AES/ECB/PKCS5Padding` di JCA; lancia [IllegalArgumentException] se il padding non torna. */
    fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "Chiave AES-256 di ${key.size} byte" }
        require(data.isNotEmpty() && data.size % BLOCK == 0) { "Lunghezza non multipla di $BLOCK: ${data.size}" }
        val roundKeys = expandKey(key)
        val out = ByteArray(data.size)
        val state = IntArray(BLOCK)
        for (offset in data.indices step BLOCK) {
            for (i in 0 until BLOCK) state[i] = data[offset + i].toInt() and 0xFF
            decryptBlock(state, roundKeys)
            for (i in 0 until BLOCK) out[offset + i] = state[i].toByte()
        }
        val pad = out.last().toInt() and 0xFF
        require(pad in 1..BLOCK && (out.size - pad until out.size).all { (out[it].toInt() and 0xFF) == pad }) {
            "Padding PKCS7 non valido"
        }
        return out.copyOf(out.size - pad)
    }

    fun sha256(data: ByteArray): ByteArray {
        val h = H0.copyOf()
        val padded = ByteArray((data.size + 9 + 63) / 64 * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        val bits = data.size.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()

        val w = IntArray(64)
        for (chunk in padded.indices step 64) {
            for (t in 0 until 16) {
                val i = chunk + 4 * t
                w[t] =
                    ((padded[i].toInt() and 0xFF) shl 24) or ((padded[i + 1].toInt() and 0xFF) shl 16) or
                            ((padded[i + 2].toInt() and 0xFF) shl 8) or (padded[i + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 =
                    w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 =
                    w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0];
            var b = h[1];
            var c = h[2];
            var d = h[3]
            var e = h[4];
            var f = h[5];
            var g = h[6];
            var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }

    private const val BLOCK = 16
    private const val ROUNDS = 14

    /** 15 chiavi di round da 16 byte, una dopo l'altra. */
    private fun expandKey(key: ByteArray): IntArray {
        val w = IntArray(BLOCK * (ROUNDS + 1))
        for (i in key.indices) w[i] = key[i].toInt() and 0xFF
        val temp = IntArray(4)
        var rcon = 1
        for (word in 8 until 4 * (ROUNDS + 1)) {
            for (j in 0 until 4) temp[j] = w[(word - 1) * 4 + j]
            if (word % 8 == 0) {
                val first = temp[0]
                temp[0] = SBOX[temp[1]] xor rcon
                temp[1] = SBOX[temp[2]]
                temp[2] = SBOX[temp[3]]
                temp[3] = SBOX[first]
                rcon = xtime(rcon)
            } else if (word % 8 == 4) {
                for (j in 0 until 4) temp[j] = SBOX[temp[j]]
            }
            for (j in 0 until 4) w[word * 4 + j] = w[(word - 8) * 4 + j] xor temp[j]
        }
        return w
    }

    /** Stato per colonne, come i byte del blocco: `state[riga + 4 * colonna]`. */
    private fun decryptBlock(state: IntArray, roundKeys: IntArray) {
        addRoundKey(state, roundKeys, ROUNDS)
        for (round in ROUNDS - 1 downTo 0) {
            invShiftRows(state)
            for (i in state.indices) state[i] = INV_SBOX[state[i]]
            addRoundKey(state, roundKeys, round)
            if (round > 0) invMixColumns(state)
        }
    }

    private fun addRoundKey(state: IntArray, roundKeys: IntArray, round: Int) {
        for (i in state.indices) state[i] = state[i] xor roundKeys[round * BLOCK + i]
    }

    private fun invShiftRows(state: IntArray) {
        val old = state.copyOf()
        for (row in 1 until 4) {
            for (col in 0 until 4) state[row + 4 * col] = old[row + 4 * ((col - row + 4) % 4)]
        }
    }

    private fun invMixColumns(state: IntArray) {
        for (col in 0 until 4) {
            val i = 4 * col
            val a0 = state[i];
            val a1 = state[i + 1];
            val a2 = state[i + 2];
            val a3 = state[i + 3]
            state[i] = mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)
            state[i + 1] = mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)
            state[i + 2] = mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)
            state[i + 3] = mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)
        }
    }

    /** Prodotto in GF(2^8). */
    private fun mul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var result = 0
        while (y != 0) {
            if ((y and 1) != 0) result = result xor x
            x = xtime(x)
            y = y ushr 1
        }
        return result
    }

    private fun xtime(x: Int): Int = ((x shl 1) xor (if ((x and 0x80) != 0) 0x1B else 0)) and 0xFF

    private val SBOX = IntArray(256)
    private val INV_SBOX = IntArray(256)

    init {
        // S-box ricavata dall'inverso moltiplicativo e dalla trasformazione affine, invece di
        // 512 costanti da ricopiare: p scorre tutti i valori non nulli, q è il suo inverso.
        var p = 1
        var q = 1
        do {
            p = (p xor (p shl 1) xor (if ((p and 0x80) != 0) 0x1B else 0)) and 0xFF
            q = q xor (q shl 1)
            q = q xor (q shl 2)
            q = q xor (q shl 4)
            q = q and 0xFF
            if ((q and 0x80) != 0) q = q xor 0x09
            val s = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4) xor 0x63
            SBOX[p] = s
            INV_SBOX[s] = p
        } while (p != 1)
        SBOX[0] = 0x63
        INV_SBOX[0x63] = 0
    }

    private fun rotl8(x: Int, shift: Int): Int = ((x shl shift) or (x ushr (8 - shift))) and 0xFF

    private val H0 = longArrayOf(
        0x6a09e667,
        0xbb67ae85,
        0x3c6ef372,
        0xa54ff53a,
        0x510e527f,
        0x9b05688c,
        0x1f83d9ab,
        0x5be0cd19,
    ).toIntArray()

    private val K = longArrayOf(
        0x428a2f98,
        0x71374491,
        0xb5c0fbcf,
        0xe9b5dba5,
        0x3956c25b,
        0x59f111f1,
        0x923f82a4,
        0xab1c5ed5,
        0xd807aa98,
        0x12835b01,
        0x243185be,
        0x550c7dc3,
        0x72be5d74,
        0x80deb1fe,
        0x9bdc06a7,
        0xc19bf174,
        0xe49b69c1,
        0xefbe4786,
        0x0fc19dc6,
        0x240ca1cc,
        0x2de92c6f,
        0x4a7484aa,
        0x5cb0a9dc,
        0x76f988da,
        0x983e5152,
        0xa831c66d,
        0xb00327c8,
        0xbf597fc7,
        0xc6e00bf3,
        0xd5a79147,
        0x06ca6351,
        0x14292967,
        0x27b70a85,
        0x2e1b2138,
        0x4d2c6dfc,
        0x53380d13,
        0x650a7354,
        0x766a0abb,
        0x81c2c92e,
        0x92722c85,
        0xa2bfe8a1,
        0xa81a664b,
        0xc24b8b70,
        0xc76c51a3,
        0xd192e819,
        0xd6990624,
        0xf40e3585,
        0x106aa070,
        0x19a4c116,
        0x1e376c08,
        0x2748774c,
        0x34b0bcb5,
        0x391c0cb3,
        0x4ed8aa4a,
        0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee,
        0x78a5636f,
        0x84c87814,
        0x8cc70208,
        0x90befffa,
        0xa4506ceb,
        0xbef9a3f7,
        0xc67178f2,
    ).toIntArray()

    private fun LongArray.toIntArray() = IntArray(size) { this[it].toInt() }
}
