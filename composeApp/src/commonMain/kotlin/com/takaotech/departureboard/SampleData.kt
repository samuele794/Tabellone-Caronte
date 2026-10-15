package com.takaotech.departureboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Marchi dimostrativi inventati per la prima colonna. Nel progetto vero sostituiscili con i
 * PNG dei loghi che hai il diritto di usare (es. `imageResource(Res.drawable.logo_x)`).
 */
object SampleLogos {
    /** Doppia freccia inclinata: "alta velocità". */
    val Arrow: ImageBitmap by lazy {
        rasterize(180, 70) {
            fun chevron(x: Float) = Path().apply {
                moveTo(x, 0f); lineTo(x + 30f, 0f); lineTo(x + 62f, 35f)
                lineTo(x + 30f, 70f); lineTo(x, 70f); lineTo(x + 32f, 35f); close()
            }
            drawPath(chevron(40f), Color.White)
            drawPath(chevron(100f), Color.White)
            drawRect(Color.White, Offset(0f, 25f), Size(52f, 20f))
        }
    }

    /** Barre inclinate di lunghezza crescente: "regionale". */
    val Stripes: ImageBitmap by lazy {
        rasterize(180, 70) {
            fun bar(y: Float, len: Float) = Path().apply {
                moveTo(180f - len, y); lineTo(180f, y); lineTo(170f, y + 20f); lineTo(
                170f - len,
                y + 20f
            ); close()
            }
            drawPath(bar(0f, 70f), Color.White)
            drawPath(bar(25f, 120f), Color.White)
            drawPath(bar(50f, 170f), Color.White)
        }
    }

    /** Rombo con binario: "intercity". */
    val Diamond: ImageBitmap by lazy {
        rasterize(180, 70) {
            val rhombus = Path().apply {
                moveTo(35f, 0f); lineTo(70f, 35f); lineTo(35f, 70f); lineTo(0f, 35f); close()
            }
            drawPath(rhombus, Color.White, style = Stroke(width = 18f))
            drawRect(Color.White, Offset(80f, 15f), Size(100f, 14f))
            drawRect(Color.White, Offset(80f, 42f), Size(100f, 14f))
        }
    }

    /** Ovale pieno con fascia: "internazionale". */
    val Oval: ImageBitmap by lazy {
        rasterize(180, 70) {
            drawRoundRect(
                Color.White, Offset(10f, 0f), Size(160f, 70f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(35f),
            )
            drawRect(
                Color.Transparent,
                Offset(10f, 28f),
                Size(160f, 14f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )
        }
    }
}

object SampleDepartures {
    val rows: List<Departure> = listOf(
        Departure(
            "RV",
            "3015",
            "SESTRI LEV.",
            "07:25",
            60,
            "MILANO LAMBRATE (07.31) - PAVIA (07.52) - VOGHERA (08.10) - GENOVA P.P. (09.12)",
            "2",
            SampleLogos.Stripes
        ),
        Departure(
            "AV",
            "9604",
            "NAPOLI C.LE",
            "07:30",
            60,
            "FERMA A: BOLOGNA C.LE - FIRENZE S.M.N. - ROMA TERMINI - EXECUTIVE E BUSINESS IN TESTA",
            "13",
            SampleLogos.Arrow
        ),
        Departure(
            "AV",
            "8973",
            "VENEZIA S.L.",
            "07:35",
            null,
            "FERMA A: BRESCIA - VERONA P.N. - PADOVA - VENEZIA MESTRE",
            "15",
            SampleLogos.Diamond
        ),
        Departure(
            "AV",
            "9915",
            "BARI C.LE",
            "07:40",
            50,
            "FERMA A: BOLOGNA C.LE - ANCONA - PESCARA C.LE - FOGGIA (14.45)",
            "11",
            SampleLogos.Arrow
        ),
        Departure(
            "AV",
            "9707",
            "TRIESTE C.LE",
            "07:45",
            20,
            "FERMA A: BRESCIA - VERONA P.N. - VENEZIA MESTRE - TRIESTE C.LE",
            "9",
            SampleLogos.Arrow
        ),
        Departure(
            "RE",
            "2217",
            "BERGAMO",
            "08:00",
            null,
            "TREVIGLIO (08.29) - VERDELLO (08.38) - BERGAMO (08.48)",
            "21",
            SampleLogos.Stripes,
            lamps = Lamps.Blinking
        ),
        Departure(
            "IC",
            "657",
            "GROSSETO",
            "08:05",
            50,
            "PAVIA - GENOVA P.P. - LA SPEZIA C.LE - PISA C.LE - LIVORNO C.LE",
            "16",
            SampleLogos.Diamond
        ),
        Departure(
            "AV",
            "9519",
            "SALERNO",
            "08:10",
            40,
            "FERMA A: REGGIO EMILIA AV - BOLOGNA C.LE - FIRENZE S.M.N. - ROMA TERMINI - NAPOLI C.LE",
            "12",
            SampleLogos.Arrow
        ),
        Departure(
            "RE",
            "2157",
            "MANTOVA",
            "08:20",
            30,
            "CODOGNO (08.58) - CREMONA (09.20) - MANTOVA (10.05)",
            "4",
            SampleLogos.Stripes
        ),
        Departure(
            "EC",
            "32",
            "GENEVE",
            "08:20",
            20,
            "DOMODOSSOLA (09.58) - BRIG (10.38) - LAUSANNE (12.10) - GENEVE (12.45)",
            "7",
            SampleLogos.Oval
        ),
    )

    val footer = BoardFooter(
        clock = "07:21",
        date = listOf("MAR", "13", "OTT"),
        messages = listOf(
            "PER UN GUASTO AGLI IMPIANTI DI CIRCOLAZIONE NEL NODO DI FIRENZE I TRENI POSSONO SUBIRE RITARDI",
            "E' VIETATO OLTREPASSARE LA LINEA GIALLA IN ATTESA DEI TRENI",
        ),
    )
}
