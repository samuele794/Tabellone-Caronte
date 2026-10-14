package tts

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** MP3 con [MediaPlayer]; i byte passano da un file nella cache dell'app. */
class AndroidSoundPlayer(context: Context) : SoundPlayer {

    private val cacheDir = context.applicationContext.cacheDir

    override suspend fun play(bytes: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(cacheDir, "sound-${bytes.contentHashCode()}.mp3").apply {
                if (length() != bytes.size.toLong()) writeBytes(bytes)
            }
        }
        val player = MediaPlayer()
        try {
            suspendCancellableCoroutine { cont ->
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer: errore $what/$extra"))
                    true
                }
                player.setDataSource(file.path)
                player.prepareAsync()
            }
        } finally {
            player.release()
        }
    }

    override fun close() {}
}
