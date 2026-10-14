package announce

import rfi.BoardMode
import rfi.RfiBoard
import rfi.RfiTrain

/** Righe del monitor costruite a mano per i test degli annunci. */
fun train(
    number: String = "9915",
    category: String = "ALTA VELOCITA'",
    carrier: String = "TRENITALIA",
    station: String = "BARI C.LE",
    time: String = "07:40",
    delayMinutes: Int? = null,
    platform: String = "11",
    flashing: Boolean = false,
    info: String = "",
    cancelled: Boolean = false,
) = RfiTrain(
    carrier = carrier,
    carrierLogoBase64 = null,
    category = category,
    number = number,
    station = station,
    time = time,
    delayMinutes = delayMinutes,
    platform = platform,
    flashing = flashing,
    info = info,
    cancelled = cancelled,
)

fun board(vararg trains: RfiTrain, mode: BoardMode = BoardMode.Departures) =
    RfiBoard(
        stationName = "MILANO CENTRALE",
        mode = mode,
        trains = trains.toList(),
        notices = emptyList()
    )
