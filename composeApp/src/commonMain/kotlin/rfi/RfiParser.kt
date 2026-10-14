package rfi

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * Lettura dell'HTML di iechub.rfi.it. Funzioni pure su stringhe: niente rete, così si
 * provano con le pagine salvate.
 *
 * Gli `id` delle celle (`RTreno`, `RBinario`…) si ripetono in ogni riga e `RTreno` condivide la
 * classe con `RCategoria`, quindi le celle si cercano per attributo dentro la singola `<tr>`.
 */
object RfiParser {

    /** Elenco delle località dal menu a tendina della pagina iniziale. */
    fun parseStations(html: String): List<Station> =
        Ksoup.parse(html)
            .select("select#ElencoLocalita > option")
            .mapNotNull { option ->
                val id = option.attr("value").toIntOrNull() ?: return@mapNotNull null
                val name = option.text().trim()
                if (name.isEmpty()) null else Station(id, name)
            }

    fun parseBoard(html: String, mode: BoardMode): RfiBoard {
        val doc = Ksoup.parse(html)
        val trains = doc.select("tbody#bodyTabId > tr[name=treno]").mapNotNull(::parseRow)
        val notices = doc.select(".marqueeinfosupp > div")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        return RfiBoard(
            stationName = doc.selectFirst("#nomeStazioneId")?.text()?.trim().orEmpty(),
            mode = mode,
            trains = trains,
            notices = notices,
        )
    }

    /** null per le righe di riempimento (senza `id` e senza numero). */
    private fun parseRow(row: Element): RfiTrain? {
        val number = row.cell("RTreno")?.text()?.trim().orEmpty()
        if (row.id().isBlank() || number.isEmpty()) return null

        val logo = row.cell("RVettore")?.selectFirst("img.logoCliente")
        val delay = row.cell("RRitardo")
        val info = row.cell("RDettagli")?.selectFirst(".testoinfoaggiuntive")?.text()
            ?.let(::cleanInfo).orEmpty()
        val category = row.cell("RCategoria")?.selectFirst("img.logoCategoria")
            ?.attr("alt")?.trim()?.removePrefix("Categoria ")?.trim().orEmpty()

        return RfiTrain(
            carrier = logo?.attr("alt")?.trim().orEmpty(),
            carrierLogoBase64 = logo?.attr("src")?.let(::base64Payload),
            category = category,
            number = number,
            station = row.cell("RStazione")?.text()?.trim().orEmpty(),
            time = row.cell("ROrario")?.text()?.trim().orEmpty(),
            delayMinutes = delay?.text()?.trim()?.toIntOrNull(),
            platform = row.cell("RBinario")?.text()?.trim().orEmpty(),
            flashing = row.cell("RExLampeggio")?.selectFirst("img.exlampeggio[alt=Si]") != null,
            info = info,
            cancelled = listOfNotNull(delay?.text(), delay?.attr("aria-label"), info)
                .any { text -> CANCELLED.any { text.contains(it, ignoreCase = true) } },
        )
    }

    /** Parole con cui il monitor segnala un treno che non circola (non presenti nelle pagine salvate). */
    private val CANCELLED = listOf("CANCELLAT", "SOPPRESS")

    private fun Element.cell(id: String): Element? = selectFirst("td[id=$id]")

    /** "data:image/gif;base64,R0lG… " → "R0lG…" */
    private fun base64Payload(src: String): String? =
        src.substringAfter("base64,", missingDelimiterValue = "").trim().ifEmpty { null }

    /** RFI raddoppia gli apostrofi a ogni passaggio ("CASSANO D''''ADDA"). */
    private fun cleanInfo(text: String): String =
        text.trim().replace(Regex("'{2,}"), "'").replace(Regex("\\s+"), " ")
}
