package com.takaotech.departureboard.fnm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Risposte di `/mia/bff/train/{numero}` costruite nei test invece che salvate: stessi nomi di
 * campo della risposta reale (ottobre 2026), ma solo quelli che servono, con dati inventati
 * sul modello del REG 2162 Como Lago → Milano Cadorna del 14/10/2026 alle 17:44.
 */
object BffResponses {

    class Stop(
        val mir: String,
        val name: String,
        val arr: String? = null,
        val dep: String? = null,
        val platform: String? = null,
        val arrActual: String? = null,
        val depActual: String? = null,
        val arrEstimated: String? = null,
        val depEstimated: String? = null,
        val cancelled: Boolean = false,
    )

    /** Un elemento dell'array: un treno con le sue fermate (prima = origine, ultima = destinazione). */
    fun solution(
        code: String,
        category: String,
        stops: List<Stop>,
        operator: String = "TRENORD",
        delay: Int? = null,
        cancelled: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("dep_station", station(stops.first()))
        put("arr_station", station(stops.last()))
        put("delay", delay)
        put("cancelled", cancelled)
        put("journey_list", buildJsonArray {
            add(buildJsonObject {
                putJsonObject("train") {
                    put("CodiceTrasporto1", code)
                    put("train_category", category)
                    put("train_operator", operator)
                    put("delay", delay)
                    put("suppression_reason", null as String?)
                    put("suppression_notes", null as String?)
                    put("alerts", null as String?)
                }
                put("pass_list", JsonArray(stops.map(::stop)))
            })
        })
    }

    fun response(vararg solutions: JsonObject): String = JsonArray(solutions.toList()).toString()

    private fun station(s: Stop) = buildJsonObject {
        put("station_id", s.mir)
        put("station_ori_name", s.name)
    }

    private fun stop(s: Stop) = buildJsonObject {
        put("station", station(s))
        put("arr_time", s.arr)
        put("dep_time", s.dep)
        put("platform", s.platform)
        put("cancelled", s.cancelled)
        putJsonObject("actual_data") {
            put("arr_actual_time", s.arrActual)
            put("dep_actual_time", s.depActual)
            put("arr_estimated_time", s.arrEstimated)
            put("dep_estimated_time", s.depEstimated)
        }
    }

    const val BOVISA_MIR = "S01642"

    /**
     * I due treni numero 2162: il RE su rete RFI (Bozzolo → Milano Centrale, già arrivato) e il
     * REG FNM in viaggio con 4 minuti di ritardo, tra Bovisa e Milano Domodossola.
     */
    fun train2162(departedFromDomodossola: Boolean = false): String = response(
        solution(
            code = "102162",
            category = "RE",
            delay = 0,
            stops = listOf(
                Stop("S01920", "BOZZOLO", dep = "09:01:00", platform = "1", depActual = "09:01:00"),
                Stop(
                    "S01700",
                    "MILANO CENTRALE",
                    arr = "10:43:00",
                    platform = "21",
                    arrActual = "10:43:00"
                ),
            ),
        ),
        solution(
            code = "1902162",
            category = "REG",
            delay = 4,
            stops = listOf(
                Stop(
                    "S01765",
                    "COMO LAGO",
                    dep = "16:46:00",
                    platform = "2",
                    depActual = "16:46:06"
                ),
                Stop(
                    "S01000", "SARONNO", arr = "17:26:30", dep = "17:27:30", platform = "1",
                    arrActual = "17:28:56", depActual = "17:30:53",
                ),
                Stop(
                    BOVISA_MIR, "MILANO BOVISA POLITECNICO", arr = "17:39:30", dep = "17:40:30",
                    platform = "4", arrEstimated = "17:43:30", depEstimated = "17:44:30",
                ),
                Stop(
                    FnmFixtures.DOMODOSSOLA_MIR,
                    "MILANO DOMODOSSOLA",
                    arr = "17:43:30",
                    dep = "17:44:00",
                    platform = "2",
                    arrEstimated = "17:47:30",
                    depEstimated = "17:48:00",
                    arrActual = "17:47:40".takeIf { departedFromDomodossola },
                    depActual = "17:48:05".takeIf { departedFromDomodossola },
                ),
                Stop(
                    "S01066",
                    "MILANO CADORNA",
                    arr = "17:52:00",
                    platform = "2",
                    arrEstimated = "17:56:00"
                ),
            ),
        ),
    )

    /**
     * Risposta per un treno dell'elenco di Milano Domodossola: in orario, binario 1, da
     * un'origine inventata alla destinazione dell'elenco. In arrivo ferma un minuto prima.
     */
    fun passingThrough(row: FnmListTrain, arrival: String?): String = response(
        solution(
            code = row.code,
            category = row.line,
            stops = listOf(
                Stop(
                    "S09999",
                    "ORIGINE ${row.number}",
                    dep = "17:00:00",
                    platform = "1",
                    depActual = "17:00:00"
                ),
                Stop(
                    FnmFixtures.DOMODOSSOLA_MIR, "MILANO DOMODOSSOLA",
                    arr = "${arrival ?: row.time}:00", dep = "${row.time}:00", platform = "1",
                ),
                Stop("S09998", row.destination, arr = "19:00:00", platform = "1"),
            ),
        ),
    )

    /** Come lo cifra il BFF: AES-256-ECB/PKCS7, chiave SHA-256 di [passphrase]. */
    fun encrypt(text: String, passphrase: String = FnmKeyStore.DEFAULT_PASSPHRASE): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(passphrase.encodeToByteArray())
        return Cipher.getInstance("AES/ECB/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            .doFinal(text.encodeToByteArray())
    }
}
