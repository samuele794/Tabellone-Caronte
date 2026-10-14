package fnm

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rfi.Station

/**
 * Lettura delle risposte di trenord.it. Funzioni pure su stringhe: niente rete, così si provano
 * con le risposte salvate.
 */
object FnmParser {

    internal val json = Json { ignoreUnknownKeys = true }

    /** Elenco di `/mia/v2/stazioni_v2/`; senza le voci "(tutte le stazioni)", che non hanno codice. */
    fun parseStations(body: String): List<Station> =
        json.decodeFromString<List<StationDto>>(body).mapNotNull { s ->
            val id = s.mir.trim().ifEmpty { return@mapNotNull null }
            val name = s.name.trim().ifEmpty { return@mapNotNull null }
            Station(id, name)
        }

    /** `station-details`: JSON con l'HTML delle tabelle `partenza` e `arrivo`. */
    fun parseStationDetails(body: String): FnmStationLists {
        val root = json.parseToJsonElement(body).jsonObject
        fun list(key: String) = root[key]?.jsonPrimitive?.contentOrNull?.let(::parseList).orEmpty()
        return FnmStationLists(departures = list("partenza"), arrivals = list("arrivo"))
    }

    /** Un `div.train.prossimi-treni` per treno; i dati completi sono nel dettaglio. */
    fun parseList(html: String): List<FnmListTrain> =
        Ksoup.parse(html).select("div.train.prossimi-treni").mapNotNull { div ->
            val title = div.selectFirst("h4.text-train-prossimi") ?: return@mapNotNull null
            val number = title.ownText().trim().ifEmpty { return@mapNotNull null }
            val code = div.select("a[onclick]").firstNotNullOfOrNull { a ->
                TRANSPORT_CODE.find(a.attr("onclick"))?.groupValues?.get(1)
            } ?: return@mapNotNull null
            FnmListTrain(
                code = code,
                line = title.selectFirst("b")?.text()?.trim().orEmpty(),
                number = number,
                destination = div.selectFirst(".time p.station")?.text()?.trim().orEmpty(),
                time = div.selectFirst(".time .arrival b")?.text()?.trim()
                    ?.takeIf { TIME.matches(it) }.orEmpty(),
            )
        }

    /** `train-sub-detail`: JSON codice trasporto → HTML del dettaglio. */
    fun parseSubDetails(body: String): Map<String, String> =
        json.parseToJsonElement(body).jsonObject.mapNotNull { (code, html) ->
            html.jsonPrimitive.contentOrNull?.let { code to it }
        }.toMap()

    /**
     * Dettaglio HTML, senza binari. Le fermate già passate (`li.active`) hanno orario programmato
     * ed effettivo; per le successive l'orario mostrato è già quello previsto, ritardo compreso, e
     * finisce anche nelle stime. Il ritardo complessivo c'è solo come testo ("in ritardo di 7 Minuti").
     */
    fun parseSubDetail(html: String): FnmTrainDetail {
        val doc = Ksoup.parse(html)
        val stops = doc.select("ul.children-station > li").map { li ->
            val departure = li.selectFirst(".departure")?.let(::times)
            val arrival = li.selectFirst(".arrivals")?.let(::times)
            val passed = li.hasClass("active")
            FnmStop(
                mir = null,
                name = li.selectFirst("p.p2")?.text()?.trim().orEmpty(),
                arrTime = arrival?.first,
                depTime = departure?.first,
                arrActual = arrival?.second,
                depActual = departure?.second,
                arrEstimated = arrival?.first?.takeIf { !passed },
                depEstimated = departure?.first?.takeIf { !passed },
            )
        }
        val text = doc.text()
        val delay = DELAY.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: EARLY.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { -it }
        return FnmTrainDetail(
            category = doc.selectFirst("h4.text-train-prossimi b")?.text()?.trim()
                ?.ifEmpty { null },
            carrier = null,
            origin = doc.selectFirst(".time .departure p.station")?.text()?.trim()
                ?: stops.firstOrNull()?.name.orEmpty(),
            destination = doc.selectFirst(".time .arrival p.station")?.text()?.trim()
                ?: stops.lastOrNull()?.name.orEmpty(),
            stops = stops,
            delayMinutes = delay,
        )
    }

    /** "Partenza 16:18" + "Effettiva 16:20" → ("16:18", "16:20"). */
    private fun times(block: Element): Pair<String?, String?> {
        var scheduled: String? = null
        var actual: String? = null
        for (p in block.select("p")) {
            val words = p.text().trim().split(Regex("\\s+"))
            val time = words.lastOrNull()?.takeIf { TIME.matches(it) } ?: continue
            if (words.first().startsWith("Effettiv", ignoreCase = true)) actual =
                time else scheduled = time
        }
        return scheduled to actual
    }

    /** Decodifica la risposta del BFF già decifrata. */
    internal fun parseBff(body: String): List<BffSolution> = json.decodeFromString(body)

    /** Il treno con codice trasporto [code] tra quelli con lo stesso numero, o null. */
    internal fun bffDetail(solutions: List<BffSolution>, code: String): FnmTrainDetail? {
        val solution = solutions.firstOrNull { s -> s.journeys.any { it.train?.code == code } }
            ?: return null
        val journey = solution.journeys.first { it.train?.code == code }
        val train = journey.train
        val stops = journey.stops.map { s ->
            FnmStop(
                mir = s.station?.id,
                name = s.station?.name.orEmpty().trim(),
                arrTime = s.arrTime,
                depTime = s.depTime,
                arrActual = s.actual?.arrActual,
                depActual = s.actual?.depActual,
                arrEstimated = s.actual?.arrEstimated,
                depEstimated = s.actual?.depEstimated,
                platform = s.platform.orEmpty().trim(),
                cancelled = s.cancelled,
            )
        }
        val notes = listOfNotNull(train?.suppressionReason, train?.suppressionNotes, train?.alerts)
            .flatMap(::texts).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return FnmTrainDetail(
            category = train?.category?.trim()?.ifEmpty { null },
            // Alcuni treni hanno "TRENORD$:$FNM3": conta solo l'impresa.
            carrier = train?.operator?.substringBefore('$')?.trim()?.ifEmpty { null },
            origin = solution.depStation?.name?.trim() ?: stops.firstOrNull()?.name.orEmpty(),
            destination = solution.arrStation?.name?.trim() ?: stops.lastOrNull()?.name.orEmpty(),
            stops = stops,
            delayMinutes = train?.delay,
            cancelled = solution.cancelled,
            notes = notes,
        )
    }

    /** Testi contenuti in un campo di forma ignota (stringa, elenco, oggetto con messaggi). */
    private fun texts(element: JsonElement): List<String> = when (element) {
        JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf { element.isString })
        is JsonArray -> element.flatMap(::texts)
        is JsonObject -> element.values.flatMap(::texts)
    }

    /**
     * Possibili chiavi del BFF nel JavaScript del sito: i letterali assegnati alla variabile passata
     * a `generateKey`. Il nome è minificato e lo stesso nome può comparire in altri moduli, quindi
     * escono più candidati: quello giusto si riconosce provando a decifrare.
     */
    fun keyCandidates(js: String): List<String> {
        val names =
            Regex("""this\.generateKey\(([\w$]+)\)""").findAll(js).map { it.groupValues[1] }.toSet()
        return names.flatMap { name ->
            val n = Regex.escape(name)
            Regex("""(?<![\w$.])$n\s*=\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""").findAll(js)
                .map { m -> unescape(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        }.filter { it.isNotEmpty() }.distinct()
    }

    /** Sequenze di escape dei letterali JavaScript. */
    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.lastIndex) {
                out.append(c); i++; continue
            }
            val next = s[i + 1]
            when (next) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'x' -> s.substring(i + 2, minOf(i + 4, s.length)).toIntOrNull(16)?.let {
                    out.append(it.toChar()); i += 2
                }

                'u' -> s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)?.let {
                    out.append(it.toChar()); i += 4
                }

                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }

    @Serializable
    private data class StationDto(
        @SerialName("CodiceMIR") val mir: String = "",
        @SerialName("NomeGeoStazioni") val name: String = "",
    )

    private val TRANSPORT_CODE = Regex("""lastStation\s*,\s*(\d+)""")
    private val TIME = Regex("""\d{1,2}:\d{2}(:\d{2})?""")
    private val DELAY = Regex("""in ritardo di\s+(\d+)\s+minut""", RegexOption.IGNORE_CASE)
    private val EARLY = Regex("""in anticipo di\s+(\d+)\s+minut""", RegexOption.IGNORE_CASE)
}
