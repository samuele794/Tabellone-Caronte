package com.takaotech.departureboard.wear.ongoing

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.takaotech.departureboard.debuglog.DebugLog
import com.takaotech.departureboard.watch.WatchSnapshot
import com.takaotech.departureboard.wear.R
import com.takaotech.departureboard.wear.data.RefreshPolicy
import com.takaotech.departureboard.wear.data.WatchRefresher
import com.takaotech.departureboard.wear.ui.TrainActivity
import com.takaotech.departureboard.wear.ui.liveTrain
import com.takaotech.departureboard.wear.wearApp
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * Attività in corso di Wear OS: l'icona del treno sul quadrante e tra le app recenti, per tornare
 * all'app con un tocco. C'è solo nella fascia del treno mostrato ([RefreshPolicy.window]): da
 * [RefreshPolicy.WINDOW_BEFORE] prima a [RefreshPolicy.WINDOW_AFTER] dopo la partenza, ritardo
 * compreso, finché il treno non parte. Fuori si toglie.
 *
 * Non chiede dati da sé: si ridisegna quando cambia il dato salvato, e [OngoingWorker] la mette e
 * la toglie all'inizio e alla fine della fascia. Senza il permesso delle notifiche non c'è.
 */
object OngoingTrain {

    private const val CHANNEL = "ongoing"
    private const val NOTIFICATION_ID = 1
    private const val WORK = "ongoing"

    /**
     * Mette, aggiorna o toglie l'attività in corso e programma il prossimo controllo.
     *
     * Da dentro il worker si accoda a quello in corso: sostituirlo lo annullerebbe.
     */
    suspend fun update(context: Context, fromWorker: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        val workManager = WorkManager.getInstance(context)
        if (!allowed(context)) {
            manager.cancel(NOTIFICATION_ID)
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val now = System.currentTimeMillis()
        val saved = context.wearApp.refresher.store.load()
        val window = RefreshPolicy.window(saved, now)
        if (window != null && now in window) {
            show(
                context,
                manager,
                saved.planned(saved.followed, Instant.fromEpochMilliseconds(now))
            )
        } else {
            manager.cancel(NOTIFICATION_ID)
        }
        // Prossimo cambio: la fine della fascia in corso o l'inizio della prossima.
        val at = window?.let { if (now in it) it.end else it.start }
        if (at == null) {
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val delay = (at - now).coerceAtLeast(0)
        DebugLog.d(WatchRefresher.TAG) { "Attività in corso: prossimo controllo tra ${delay / 1000} s" }
        workManager.enqueueUniqueWork(
            WORK,
            if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OngoingWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private fun show(
        context: Context,
        manager: NotificationManagerCompat,
        snapshot: WatchSnapshot
    ) {
        val selection = snapshot.selection ?: return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.ongoing_channel))
                .setDescription(context.getString(R.string.ongoing_channel_description))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, TrainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = ongoingStatusText(snapshot)
        val icon = TrainNose.of(snapshot).icon
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(selection.label)
            .setContentText(status)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            // Ricompare a ogni aggiornamento: non deve suonare né vibrare.
            .setSilent(true)
            .setOnlyAlertOnce(true)
        // Va applicata prima di costruire la notifica: ne scrive gli extra.
        OngoingActivity.Builder(context, NOTIFICATION_ID, notification)
            .setStaticIcon(icon)
            .setTouchIntent(open)
            .setTitle(selection.label)
            .setStatus(Status.Builder().addTemplate(status).build())
            .build()
            .apply(context)
        if (allowed(context)) manager.notify(NOTIFICATION_ID, notification.build())
    }

    /** Dall'API 33 le notifiche, e quindi le attività in corso, chiedono un permesso. */
    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) ==
                PackageManager.PERMISSION_GRANTED
}

/** "19:18 · +5' · BIN 7"; "19:18 · in attesa" se il treno non è (ancora) in tabellone. */
internal fun ongoingStatusText(snapshot: WatchSnapshot): String {
    val live = snapshot.liveTrain()
    val time = live?.time ?: snapshot.train?.time ?: snapshot.selection?.time
    val rest = if (live == null) listOf("in attesa") else listOfNotNull(
        when {
            live.cancelled -> "CANC"
            (live.delayMinutes ?: 0) > 0 -> "+${live.delayMinutes}'"
            else -> null
        },
        live.platform.takeIf { it.isNotBlank() }?.let { "BIN $it" },
    )
    return (listOfNotNull(time) + rest).joinToString(" · ")
}

/** Muso del treno nell'icona dell'attività in corso, secondo la categoria del monitor. */
internal enum class TrainNose(@DrawableRes val icon: Int) {
    /** Frecciarossa e alta velocità. */
    Etr1000(R.drawable.ic_train_etr1000),

    /** Frecciabianca ("ES*" sui monitor RFI) e Frecciargento, della stessa famiglia. */
    Etr460(R.drawable.ic_train_etr460),

    /** Tutti gli altri: regionali, Intercity, suburbani. */
    E464(R.drawable.ic_train_e464);

    companion object {
        /**
         * Dalla categoria del treno o, finché non si è visto in tabellone, dall'etichetta scelta
         * ("R 91831 → VIGNOLA").
         */
        fun of(snapshot: WatchSnapshot): TrainNose =
            of(snapshot.train?.category ?: snapshot.selection?.label?.substringBefore(' '))

        fun of(category: String?): TrainNose =
            when (category?.trim()?.uppercase()?.removeSuffix("*")) {
                "AV", "FR", "ALTA VELOCITA'", "ALTA VELOCITA" -> Etr1000
                "FB", "FA", "ES" -> Etr460
                else -> E464
            }
    }
}

/** Mette o toglie l'attività in corso all'inizio e alla fine della fascia del treno. */
class OngoingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        OngoingTrain.update(applicationContext, fromWorker = true)
        return Result.success()
    }
}
