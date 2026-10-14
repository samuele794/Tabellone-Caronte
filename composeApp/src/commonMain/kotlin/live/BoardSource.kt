package live

import cache.StationSource
import rfi.BoardMode
import rfi.RfiBoard
import rfi.Station
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Un fornitore di dati per il tabellone (RFI, FNM…). Tutti restituiscono le righe nel formato del
 * monitor RFI ([RfiBoard]), così mapper e annunci restano gli stessi.
 */
interface BoardSource : AutoCloseable {
    val source: StationSource

    /** Stazione mostrata quando si passa a questo fornitore senza sceglierne una. */
    val defaultStation: Station

    suspend fun stations(): List<Station>

    suspend fun board(station: Station, mode: BoardMode): RfiBoard

    /**
     * Quando verrà riscaricato l'elenco dei treni della stazione, per i fornitori che lo
     * aggiornano più di rado dei dettagli; null se si scarica tutto a ogni aggiornamento.
     */
    val nextListRefreshAt: TimeSource.Monotonic.ValueTimeMark? get() = null

    /**
     * Ogni quanto riscaricare l'elenco dei treni, per i fornitori che lo fanno più di rado dei
     * dettagli; null se non lo fanno, e allora assegnarlo non fa nulla.
     */
    var listRefresh: Duration?
        get() = null
        set(_) {}
}
