package rfi

import cache.StationSource
import live.BoardSource

/** Il monitor RFI di iechub.rfi.it come [BoardSource]. */
class RfiBoardSource(private val client: RfiClient = RfiClient()) : BoardSource {
    override val source = StationSource.Rfi
    override val defaultStation = MilanoCentrale

    override suspend fun stations() = client.stations()

    override suspend fun board(station: Station, mode: BoardMode) = client.board(station.id, mode)

    override fun close() = client.close()

    companion object {
        val MilanoCentrale = Station("1728", "MILANO CENTRALE")
    }
}
