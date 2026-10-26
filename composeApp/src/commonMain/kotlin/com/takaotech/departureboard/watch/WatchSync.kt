package com.takaotech.departureboard.watch

import com.takaotech.departureboard.cache.StationSource
import com.takaotech.departureboard.rfi.Station
import kotlinx.coroutines.flow.StateFlow

/**
 * Collegamento con lo smartwatch, dove la piattaforma lo ha (Android): senza, le impostazioni
 * non mostrano la sezione "Smartwatch".
 */
interface WatchSync {
    /** Se c'è almeno uno smartwatch con l'app, raggiungibile adesso. */
    val connected: StateFlow<Boolean>

    /**
     * Ultimo treno scelto per numero e poi trovato in tabellone, con etichetta e orario veri;
     * null finché non ce n'è.
     */
    val completed: StateFlow<WatchSelection?>

    /** Il treno da seguire è cambiato (null = nessuno): va mandato al watch. */
    fun selectionChanged(selection: WatchSelection?)

    /** Treni in partenza da [station], tra cui scegliere quello da seguire. */
    suspend fun candidates(source: StationSource, station: Station): List<WatchedTrain>
}
