package fnm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Risposte di trenord.it costruite nei test invece che salvate. Ricalcano la struttura vista il
 * 14/10/2026 (classi, annidamenti, testi) con dati inventati sul modello di Milano Domodossola
 * alle 17:44: elenco stazioni, `station-details`, HTML di `train-sub-detail`, il pezzo del
 * JavaScript con la chiave. Le risposte del BFF sono in [BffResponses].
 */
object FnmFixtures {

    const val DOMODOSSOLA_MIR = "S01067"

    // Elenco stazioni (/mia/v2/stazioni_v2/)

    /** Stazioni vere, in ordine alfabetico come le restituisce Trenord. */
    val stations = listOf(
        "S01416" to "ABBADIA LARIANA",
        "S01728" to "ABBIATE GUAZZONE",
        "S01758" to "LAMBRUGO-LURAGO D'ERBA",
        "S01066" to "MILANO CADORNA",
        DOMODOSSOLA_MIR to "MILANO DOMODOSSOLA",
        "S01765" to "COMO LAGO",
    )

    /** Voci di gruppo, senza codice, che l'app scarta. */
    val stationGroups = listOf("COMO (tutte le stazioni)", "MILANO (tutte le stazioni)")

    val stazioni: String
        get() = JsonArray(
            (stations + stationGroups.map { "" to it }).mapIndexed { i, (mir, name) ->
                buildJsonObject {
                    put("_id", "00000000-0000-0000-0000-${i.toString().padStart(12, '0')}")
                    put("CodiceMIR", mir)
                    put("NomeGeoStazioni", name)
                }
            }
        ).toString()

    // Treni di una stazione (/rest/render/station-details)

    /** Un treno dell'elenco, con l'orario di partenza e quello di arrivo in questa stazione. */
    class Listed(
        val code: String,
        val line: String,
        val number: String,
        val destination: String,
        val departure: String,
        val arrival: String,
    )

    /** Il REG 2162 e altri 19 treni ogni 3 minuti: più dei 15 di cui si chiede il dettaglio. */
    val domodossolaTrains: List<Listed> =
        listOf(Listed("1902162", "REG", "2162", "MILANO CADORNA", "17:44", "17:43")) +
                (1..19).map { i ->
                    val number = (800 + i).toString()
                    val minutes = 17 * 60 + 44 + 3 * i
                    Listed(
                        code = "19" + number.padStart(5, '0'),
                        line = listOf("S3", "RE", "S4", "REG")[i % 4],
                        number = number,
                        destination = listOf(
                            "SARONNO",
                            "CAMNAGO-LENTATE",
                            "MILANO CADORNA",
                            "ASSO"
                        )[i % 4],
                        departure = hhmm(minutes),
                        arrival = hhmm(minutes - 1),
                    )
                }

    val domodossola get() = stationDetails(domodossolaTrains)

    /** JSON con l'HTML delle due tabelle; in arrivo "Diretto a" resta la destinazione. */
    fun stationDetails(trains: List<Listed>): String = buildJsonObject {
        put("partenza", trains.joinToString("\n") { listBlock(it, "Partenza alle", it.departure) })
        put("arrivo", trains.joinToString("\n") { listBlock(it, "Arrivo alle", it.arrival) })
        put("stationMap", "<div class=\"map\"></div>")
    }.toString()

    private fun listBlock(t: Listed, label: String, time: String) = """
        <div data-direzione="da-milano-centrale" class="train prossimi-treni"
             xmlns:f="http://typo3.org/ns/TYPO3/CMS/Fluid/ViewHelpers">
            <div class="hidden-mobile ">
                <div class="content-visible col-sm-12 no-margin  ">
                    <div class="col-sm-4 col-lg-3  no-margin ">
                        <p class="titolo-dettaglio-viaggio" style="">DETTAGLIO VIAGGIO</p>
                        <div class="col-sm-12 no-margin " style="">
                            <img id='' class='icon-train-prossimi' alt='' src='/fileadmin/templates/img/linee/${t.line}.png' loading='lazy' />
                            <h4 class="text-train-prossimi">
                                <b>${t.line}</b>
                                ${t.number}</h4>
                        </div>
                    </div>
                    <div class="col-sm-5 col-lg-6  no-margin ">
                        <div class=" hidden-mobile" style="display:none">
                            <div class="departure">
                                <span class="content"><p>${t.departure}</p><p class="divider">-</p><p class="station"></p></span>
                            </div>
                            <div class="arrival">
                                <span class="content"><p>${t.arrival} </p><p class="divider">-</p><p class="station">${t.destination}</p></span>
                            </div>
                        </div>
                    </div>
                    <div class="col-sm-5 col-lg-4  no-margin ">
                        <div class="time hidden-mobile" style="">
                            <div class="arrival">
                                <p style="">Diretto a</p>
                                <p class="station" style="">${t.destination}</p>
                            </div>
                        </div>
                    </div>
                    <div class="col-sm-5 col-lg-4  no-margin ">
                        <div class="time hidden-mobile" style="">
                            <div class="arrival">
                                <p style="">
                                    $label
                                    <b>$time</b></p>
                            </div>
                        </div>
                    </div>
                    <div class="col-sm-4 col-lg-1  center no-margin star-station ">
                        <span class="star star-empty  ${t.code}" onclick="Trenord.ui.bookmarks.toggle('train','${t.code}','false','infomob', '${t.number}')"></span>
                    </div>
                    <div class="col-lg-12 center no-margin accordion-train dettaglio-prossimi-desktop infomob">
                        <div class="container-link">
                            <a id="train-detail-open-${t.number}" class="accordion-desk infomob "
                               onclick="Trenord.infoMobilita.updateHistory(Trenord.infoMobilita.lastStation,${t.code})">
                                Vedi dettagli
                            </a>
                        </div>
                    </div>
                </div>
                <div class="content-accordion train-sub-details-${t.code}"></div>
            </div>
        </div>
    """.trimIndent()

    // Dettaglio HTML di un treno (/rest/render/train-sub-detail)

    /** Una fermata come la mostra il dettaglio HTML; [passed] = classe `active`. */
    class HtmlStop(
        val name: String,
        val arr: String? = null,
        val dep: String? = null,
        val arrActual: String? = null,
        val depActual: String? = null,
        val passed: Boolean = false,
        val here: Boolean = false,
    )

    class HtmlTrain(
        val code: String,
        val category: String,
        val number: String,
        val stops: List<HtmlStop>,
        val delayMinutes: Int? = null,
    )

    /**
     * I due 2162 com'erano alle 17:44. Per le fermate future l'HTML mostra già l'orario previsto
     * (a Domodossola 17:47 invece delle 17:44 programmate), il ritardo complessivo è nel testo.
     */
    val subDetail2162: String
        get() = subDetails(
            HtmlTrain(
                "102162", "RE", "2162",
                listOf(
                    HtmlStop("BOZZOLO", dep = "09:01", passed = true),
                    HtmlStop("CREMONA", arr = "09:28", dep = "09:30", passed = true),
                    HtmlStop("MILANO CENTRALE", arr = "10:43", passed = true),
                ),
            ),
            HtmlTrain(
                "1902162", "REG", "2162",
                listOf(
                    HtmlStop("COMO LAGO", dep = "16:46", depActual = "16:46", passed = true),
                    HtmlStop(
                        "COMO BORGHI", arr = "16:49", dep = "16:51",
                        arrActual = "16:49", depActual = "16:54", passed = true,
                    ),
                    HtmlStop(
                        "SARONNO", arr = "17:26", dep = "17:27",
                        arrActual = "17:28", depActual = "17:30", passed = true,
                    ),
                    HtmlStop(
                        "MILANO BOVISA POLITECNICO", arr = "17:39", dep = "17:40",
                        arrActual = "17:43", passed = true, here = true,
                    ),
                    HtmlStop("MILANO DOMODOSSOLA", arr = "17:46", dep = "17:47"),
                    HtmlStop("MILANO CADORNA", arr = "17:52"),
                ),
                delayMinutes = 3,
            ),
        )

    /** JSON codice trasporto → HTML, come risponde Trenord per un numero di treno. */
    fun subDetails(vararg trains: HtmlTrain): String =
        JsonObject(trains.associate { it.code to JsonPrimitive(subDetailHtml(it)) }).toString()

    private fun subDetailHtml(t: HtmlTrain): String {
        val origin = t.stops.first()
        val destination = t.stops.last()
        val delay = t.delayMinutes?.let {
            """
            <div class="hidden">
                <img id='' class='icon-24' alt='' src='/fileadmin/templates/img/prossimi-treni/time-orange.svg' loading='lazy' />
                <p>
                    in ritardo di
                    $it
                    Minuti
                </p>
            </div>
            """
        }.orEmpty()
        return """
        <div class="divider"></div>
        <div class="col-sm-12 col-lg-6  no-margin ">
            <div class="col-sm-4 col-lg-5 no-margin  padd15">
                <img id='' class='icon-train-prossimi' alt='' src='/fileadmin/templates/img/linee/R.png' loading='lazy' />
                <h4 class="text-train-prossimi">
                    <b>${t.category}</b>
                    ${t.number}</h4>
                <p class="specific-detail ">
                    Diretto a
                    ${destination.name}
                </p>
            </div>
            <div class="col-sm-6 col-lg-7 no-margin">
                <div class="time hidden-desktop">
                    <div class="departure">
                        <span class="content"><p>${origin.dep}:00</p><p class="station">${origin.name}</p></span>
                        <hr class="time-bar">
                    </div>
                    <div class="arrival">
                        <span class="content"><p>${destination.arr}:00 </p><p class="station">${destination.name}</p></span>
                    </div>
                </div>
            </div>
            <div class=" col-sm-12 col-lg-12  no-margin">
                <div class="delay-home update">
                    <h4><b>Informazioni sulla circolazione</b></h4>
                    <p class="p3">Ultimo aggiornamento : 14/10/26</p>
                    <div class="carousel-line owl-carousel owl-theme">
                        <div class="item 1 info ">
                            <div class="col-xs-12 status-line no-margin green-line "><p class="p2">Regolare</p></div>
                            <div class="col-xs-12 body-texts no-margin"><p class="p2"><p>Lavori dalle 22:30 alle 05:00.</p></p></div>
                        </div>
                    </div>
                </div>
            </div>
            <div class=" col-sm-12 col-lg-12  no-margin">
                <div class="delay-home">$delay</div>
            </div>
        </div>
        <div class="col-sm-12 col-lg-6 no-margin ">
            <div class="description-train">
                <div class="delay-home update list-stations-infomob">
                    <a class="previous-stations previous-stations-${t.number}">Vedi stazioni precedenti</a>
                    <div class="list-station">
                        <ul class="children-station" data-scroll="0" data-children="${t.stops.size}">
                            <p class="p3 last-update">Ultimo aggiornamento : 14/10/26</p>
                            ${t.stops.joinToString("\n") { htmlStop(it) }}
                        </ul>
                    </div>
                    <a class="next-stations next-stations-${t.number}">Vedi stazioni successive</a>
                </div>
            </div>
        </div>
        """.trimIndent()
    }

    private fun htmlStop(s: HtmlStop): String {
        val classes = listOfNotNull("active".takeIf { s.passed }, "half-arrow".takeIf { s.here })
        fun times(
            block: String,
            label: String,
            actualLabel: String,
            time: String?,
            actual: String?
        ) =
            if (time == null) "" else """
                <div class="$block">
                    <p class="">
                        $label
                        $time
                    </p>
                    ${actual?.let { "<p class=\"orange\">\n$actualLabel\n$it\n</p>" }.orEmpty()}
                </div>
            """
        return """
            <li class=" ${classes.joinToString(" ")} ">
                <p class="p2"><b>${s.name}</b></p>
                <div class="details-station">
                    ${times("departure", "Partenza", "Effettiva", s.dep, s.depActual)}
                    ${times("arrivals", "Arrivo", "Effettivo", s.arr, s.arrActual)}
                </div>
                <div class="triangle-list"></div>
            </li>
        """
    }

    // JavaScript del sito

    /**
     * Il pezzo di `trenord-web-components.js` con la chiave: la frase sta in una costante
     * minificata (`Q`) passata a `generateKey`; un'altra `Q` di un altro modulo fa da esca.
     */
    fun webComponents(passphrase: String = FnmKeyStore.DEFAULT_PASSPHRASE): String = """
        !function(e){var C,M,L,Q="<",A=1e5*e.Math.random()>>0;e.registerElement=function(){return Q}}(window);
        zUnb:function(A,t,n){"use strict";n.r(t);const i=!0,s="/store-api/",C="https://www.trenord.it/mia/",w="https://www.trenord.it/mia/bff/",Q="$passphrase",E="https://www.trenord.it/";
        class St{constructor(){}generateKey(A){return Yt.SHA256(A)}decryptData(A,t){const n=Yt.lib.WordArray.create(new Uint8Array(A));return Yt.AES.decrypt({ciphertext:n},t,{mode:Yt.mode.ECB,padding:Yt.pad.Pkcs7}).toString(Yt.enc.Utf8)}decryptResponse(A){const t=this.generateKey(Q),n=this.decryptData(A,t);return JSON.parse(n)}}
        class Ht{getTrain(A,t){const n=`${'$'}{this.bffUrl}train/${'$'}{A}?date=${'$'}{t}`;return this.http.get(n,{responseType:"arraybuffer"})}}}
    """.trimIndent()

    val webComponents get() = webComponents()

    private fun hhmm(minutes: Int) =
        "${(minutes / 60).toString().padStart(2, '0')}:${
            (minutes % 60).toString().padStart(2, '0')
        }"
}
