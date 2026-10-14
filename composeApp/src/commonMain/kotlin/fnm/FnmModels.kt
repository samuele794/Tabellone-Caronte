package fnm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Una riga dell'elenco treni di `station-details`: solo orario programmato, niente ritardi. */
data class FnmListTrain(
    /** Codice trasporto: "1900858" (rete FNM), "102162" (rete RFI)… */
    val code: String,
    /** Linea o categoria come sull'icona: "S3", "RE", "REG"… */
    val line: String,
    val number: String,
    /** "Diretto a", anche nella tabella degli arrivi. */
    val destination: String,
    /** "HH:MM" in questa stazione; vuoto se manca. */
    val time: String,
)

/** Le due tabelle di `station-details`. */
data class FnmStationLists(val departures: List<FnmListTrain>, val arrivals: List<FnmListTrain>)

/** Dettaglio di un treno, dal BFF o in mancanza dall'HTML di `train-sub-detail`. */
data class FnmTrainDetail(
    val category: String?,
    val carrier: String?,
    val origin: String,
    val destination: String,
    val stops: List<FnmStop>,
    /** Ritardo complessivo dichiarato; quello alla fermata si ricava dagli orari. */
    val delayMinutes: Int?,
    val cancelled: Boolean = false,
    val notes: List<String> = emptyList(),
)

/** Una fermata del treno; orari "HH:MM" o "HH:MM:SS", null se mancano. */
data class FnmStop(
    /** Codice MIR, assente nel dettaglio HTML. */
    val mir: String?,
    val name: String,
    val arrTime: String?,
    val depTime: String?,
    val arrActual: String? = null,
    val depActual: String? = null,
    val arrEstimated: String? = null,
    val depEstimated: String? = null,
    val platform: String = "",
    val cancelled: Boolean = false,
)

// Risposta (decifrata) di /mia/bff/train/{numero}: solo i campi usati.

@Serializable
internal data class BffSolution(
    @SerialName("dep_station") val depStation: BffStation? = null,
    @SerialName("arr_station") val arrStation: BffStation? = null,
    val cancelled: Boolean = false,
    @SerialName("journey_list") val journeys: List<BffJourney> = emptyList(),
)

@Serializable
internal data class BffStation(
    @SerialName("station_id") val id: String? = null,
    @SerialName("station_ori_name") val name: String? = null,
)

@Serializable
internal data class BffJourney(
    val train: BffTrain? = null,
    @SerialName("pass_list") val stops: List<BffStop> = emptyList(),
)

@Serializable
internal data class BffTrain(
    @SerialName("CodiceTrasporto1") val code: String? = null,
    @SerialName("train_category") val category: String? = null,
    @SerialName("train_operator") val operator: String? = null,
    val delay: Int? = null,
    @SerialName("suppression_type") val suppressionType: JsonElement? = null,
    @SerialName("suppression_reason") val suppressionReason: JsonElement? = null,
    @SerialName("suppression_notes") val suppressionNotes: JsonElement? = null,
    val alerts: JsonElement? = null,
)

@Serializable
internal data class BffStop(
    val station: BffStation? = null,
    @SerialName("arr_time") val arrTime: String? = null,
    @SerialName("dep_time") val depTime: String? = null,
    val platform: String? = null,
    val cancelled: Boolean = false,
    @SerialName("actual_data") val actual: BffActual? = null,
)

@Serializable
internal data class BffActual(
    @SerialName("arr_actual_time") val arrActual: String? = null,
    @SerialName("dep_actual_time") val depActual: String? = null,
    @SerialName("arr_estimated_time") val arrEstimated: String? = null,
    @SerialName("dep_estimated_time") val depEstimated: String? = null,
)
