package com.torrentviewer.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torrentviewer.core.Kv
import com.torrentviewer.core.NewEpisodes
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * A notification when a series you follow has a new episode out. Twice a day, on a network, the
 * followed series of each profile are looked up (Cinemeta, off its day-long cache) and any with
 * episodes out since the profile was last told are announced. The check is [NewEpisodes.alerts];
 * this is only when it runs and how it speaks. Turned off in Settings, nothing is scheduled.
 */
object EpisodeAlerts {
    private const val WORK = "episode-alerts"
    private const val CHANNEL = "new-episodes"

    /** On or off to match the setting; safe to call at every launch. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!SettingsStore.value.value.episodeAlerts) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<Check>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Kept: a launch must not push the next check back.
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Android 13+ asks before a notification shows. */
    fun needsPermission(context: Context) =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    class Check(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // The app may not be running: the worker starts what it reads from.
            Kv.init(context)
            SettingsStore.load()
            Profiles.load()
            if (!SettingsStore.value.value.episodeAlerts || needsPermission(context)) return Result.success()
            val alerts = runCatching { NewEpisodes.alerts() }.getOrElse { return Result.retry() }
            if (alerts.isEmpty()) return Result.success()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val named = Profiles.list.value.size > 1
            alerts.forEach { a ->
                val e = a.episode
                val text = (if (a.count > 1) "${a.count} new episodes, the latest is " else "") + "S${e.season}:E${e.episode}" + (e.name.takeIf { it.isNotBlank() && !it.startsWith("Episode") }?.let { " · $it" } ?: "")
                val who = if (named) Profiles.list.value.find { it.id == a.profile }?.name else null
                val note = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(a.title.title)
                    .setContentText(text)
                    .setSubText(who)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .build()
                nm.notify((a.profile + a.title.imdb).hashCode(), note)
            }
            return Result.success()
        }
    }
}
