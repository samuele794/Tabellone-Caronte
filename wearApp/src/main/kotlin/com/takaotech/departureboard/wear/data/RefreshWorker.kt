package com.takaotech.departureboard.wear.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.wear.wearApp
import java.util.concurrent.TimeUnit

/**
 * Aggiornamento in background per tile e complication, nei tempi di [RefreshPolicy]: fuori dalla
 * fascia del treno non gira. Alla fine programma il successivo.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.wearApp.refresher.refresh(RefreshTrigger.Background)
        schedule(applicationContext, fromWorker = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "refresh"

        /**
         * Programma il prossimo aggiornamento, o lo annulla se non serve: nessun treno, oppure
         * nessun tile o complication che lo mostri (l'app aperta si aggiorna da sola).
         *
         * Da dentro il worker si accoda a quello in corso: sostituirlo lo annullerebbe.
         */
        suspend fun schedule(context: Context, fromWorker: Boolean = false) {
            val app = context.wearApp
            val workManager = WorkManager.getInstance(context)
            val now = System.currentTimeMillis()
            val at = if (app.surfaces.any()) {
                RefreshPolicy.nextRefreshAt(app.refresher.store.load(), now, foreground = false)
            } else {
                null
            }
            if (at == null) {
                DebugLog.d(WatchRefresher.TAG) { "Nessun aggiornamento in background" }
                if (!fromWorker) workManager.cancelUniqueWork(NAME)
                return
            }
            val delay = (at - now).coerceAtLeast(0)
            DebugLog.d(WatchRefresher.TAG) { "Prossimo aggiornamento in background tra ${delay / 1000} s" }
            workManager.enqueueUniqueWork(
                NAME,
                if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RefreshWorker>().setInitialDelay(
                    delay,
                    TimeUnit.MILLISECONDS
                ).build(),
            )
        }
    }
}

/**
 * Tile e complication del treno presenti adesso, per sapere se serve aggiornare in background.
 * Ognuno si segna quando viene aggiunto o mostrato e si toglie quando viene rimosso.
 */
class ActiveSurfaces(context: Context) {

    private val prefs = context.getSharedPreferences("surfaces", Context.MODE_PRIVATE)

    fun any(): Boolean = ids().isNotEmpty()

    /** true se [id] non c'era: allora va programmato l'aggiornamento. */
    @Synchronized
    fun add(id: String): Boolean {
        val ids = ids()
        if (id in ids) return false
        prefs.edit().putStringSet(KEY, ids + id).apply()
        return true
    }

    @Synchronized
    fun remove(id: String) {
        prefs.edit().putStringSet(KEY, ids() - id).apply()
    }

    private fun ids(): Set<String> = prefs.getStringSet(KEY, null).orEmpty()

    companion object {
        private const val KEY = "ids"

        fun tile(id: Int) = "tile:$id"
        fun complication(id: Int) = "complication:$id"
    }
}
