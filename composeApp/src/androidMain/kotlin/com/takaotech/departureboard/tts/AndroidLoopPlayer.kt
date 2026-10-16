package com.takaotech.departureboard.tts

import android.content.Context
import android.media.MediaPlayer
import com.takaotech.departureboard.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * MP3 in loop con [MediaPlayer]. La traccia si legge direttamente dagli asset dell'APK (gli MP3
 * non vengono compressi); se non si può, passa da una copia nella cache dell'app.
 */
class AndroidLoopPlayer(context: Context) : LoopPlayer {

    private val context = context.applicationContext

    override suspend fun loop(path: String, gain: StateFlow<Float>) {
        val player = MediaPlayer()
        try {
            withContext(Dispatchers.IO) { player.setSource(path) }
            player.isLooping = true
            coroutineScope {
                launch { gain.collect { player.setVolume(it, it) } }
                suspendCancellableCoroutine<Unit> { cont ->
                    player.setOnPreparedListener { it.start() }
                    player.setOnErrorListener { _, what, extra ->
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer: errore $what/$extra"))
                        true
                    }
                    player.prepareAsync()
                }
            }
        } finally {
            player.release()
        }
    }

    private suspend fun MediaPlayer.setSource(path: String) {
        val asset = Res.getUri(path).removePrefix(ASSET_URI_PREFIX)
        try {
            context.assets.openFd(asset)
                .use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        } catch (e: IOException) {
            val file = File(context.cacheDir, "loop-${path.substringAfterLast('/')}")
            val bytes = Res.readBytes(path)
            if (file.length() != bytes.size.toLong()) file.writeBytes(bytes)
            setDataSource(file.path)
        }
    }

    override fun close() {}

    private companion object {
        const val ASSET_URI_PREFIX = "file:///android_asset/"
    }
}
