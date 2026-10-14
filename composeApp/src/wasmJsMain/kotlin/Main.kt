import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import announce.Announcer
import cache.StationCache
import fnm.FnmBoardSource
import fnm.FnmClient
import fnm.FnmKeyStore
import kotlinx.browser.document
import kotlinx.browser.window
import live.LiveBoard
import live.LiveBoardState
import live.SettingsStore
import live.rememberNow
import rfi.BoardMode
import rfi.RfiBoardSource
import rfi.RfiClient
import tts.WebSoundPlayer
import tts.WebSpeechEngine
import tts.unlockSpeech

/** Fusi orari per kotlinx-datetime: nel browser vanno caricati a parte. */
@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule : JsAny

private val jsJodaTz = JsJodaTimeZoneModule

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // RFI e Trenord non mandano intestazioni CORS: si passa da un proxy che inoltra /rfi/* e
    // /trenord/* (il dev server in locale, vedi webpack.config.d/proxy.js).
    val proxy = proxyBase()
    val stationCache = StationCache("departure-board/stations.json")
    val settingsStore = SettingsStore("departure-board/settings.json")
    val fnmClient = FnmClient("$proxy/trenord", userAgent = null)
    val sources = listOf(
        RfiBoardSource(RfiClient("$proxy/rfi", userAgent = null)),
        FnmBoardSource(
            fnmClient,
            FnmKeyStore("departure-board/fnm-key.json", fnmClient::webComponentsJs)
        ),
    ).associateBy { it.source }
    val announcer = Announcer(WebSpeechEngine(), WebSoundPlayer())

    ComposeViewport(document.body!!) {
        val state = remember {
            LiveBoardState(
                sources,
                announcer = announcer,
                stationCache = stationCache,
                settingsStore = settingsStore,
            )
        }
        LaunchedEffect(state.mode, state.station) {
            document.title =
                "${if (state.mode == BoardMode.Arrivals) "Arrivi" else "Partenze"} — ${state.station.name}"
        }
        var audioUnlocked by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            onFirstGesture {
                unlockSpeech()
                audioUnlocked = true
            }
        }
        MaterialTheme {
            Box {
                LiveBoard(state, now = rememberNow())
                if (state.announce && !audioUnlocked) {
                    // Senza un gesto dell'utente il browser blocca jingle e voce.
                    Text(
                        "Tocca il tabellone per attivare gli annunci audio",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Color(0xCC000000)).padding(8.dp),
                    )
                }
            }
        }
    }
}

/** URL del proxy da `<meta name="board-proxy">`; vuoto = la stessa origine della pagina. */
private fun proxyBase(): String =
    document.querySelector("meta[name=board-proxy]")?.getAttribute("content")?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?: window.location.origin

/** Esegue [action] al primo click, tocco o tasto premuto nella pagina, una volta sola. */
private fun onFirstGesture(action: () -> Unit) {
    var done = false
    val listener: (org.w3c.dom.events.Event) -> Unit = {
        if (!done) {
            done = true
            action()
        }
    }
    listOf("pointerdown", "keydown").forEach { document.addEventListener(it, listener) }
}
