package knf.kuma.anilist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import knf.kuma.R
import knf.kuma.database.CacheDB
import knf.kuma.pojos.av1.Organizer
import knf.kuma.retrofit.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** Importa la lista de AniList a la app: estado de seguimiento + episodios vistos. Nunca borra nada local. */
class AniListImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AniListAuth.isLoggedIn || !AniListSync.isLibrarySyncEnabled) return@withContext Result.success()
        if (AniListAuth.userId == -1) AniListAuth.fetchViewer()
        val entries = AniListApi.userList(AniListAuth.userId) ?: return@withContext Result.retry()
        val organizerDAO = CacheDB.INSTANCE.organizerDAO()
        val recordDAO = CacheDB.INSTANCE.recordAV1DAO()
        var imported = 0
        var unmatched = 0
        for ((index, entry) in entries.withIndex()) {
            progress(index + 1, entries.size, entry.media.titles.first())
            suspend fun report(episodes: Int, failed: Boolean) = workProgress(index + 1, entries.size, entry.media, episodes, imported, unmatched, failed)
            report(-1, false)
            try {
                val mapping = AniListMatcher.findAppAnime(entry.media)
                if (mapping == null) {
                    unmatched++
                    report(0, true)
                    continue
                }
                var marked = 0
                val state = stateFor(entry.status)
                val current = organizerDAO.getByAid(mapping.aid)?.organizer
                val episodes = if (entry.status == "COMPLETED") Int.MAX_VALUE else entry.progress
                if (episodes > 0) {
                    val dir = Repository.getDirectory(mapping.slug)
                    if (dir != null) {
                        val seen = dir.chapters.filter { it.number <= episodes }
                        marked = seen.size
                        seen.forEach { recordDAO.addChapterIgnore(it.asRecord(dir)) }
                        CacheDB.INSTANCE.aniListScrobbleDAO().addIgnore(seen.map {
                            AniListScrobbleState(dir.slug, floor(it.number).toInt(), dir.aid, dir.name, AniListScrobbleState.SYNCED)
                        })
                        if (state != null && current?.state != state)
                            organizerDAO.add(dir.asOrganizer(state))
                    }
                } else if (state != null && current?.state != state) {
                    organizerDAO.add(Organizer(mapping.aid, entry.media.titles.first(), mapping.slug, current?.lastWatched ?: -1, state))
                }
                imported++
                report(marked, false)
            } catch (e: Exception) {
                e.printStackTrace()
                unmatched++
                report(0, true)
            }
        }
        Log.i("AniList", "Importados $imported, sin coincidencia $unmatched de ${entries.size}")
        val unmatchedStr = if (unmatched > 0) " " + applicationContext.getString(R.string.anilist_import_unmatched, unmatched) else ""
        AniListSync.lastImport = applicationContext.getString(R.string.anilist_import_summary, imported, entries.size) + unmatchedStr
        notify(builder().setContentTitle(applicationContext.getString(R.string.anilist_import_complete_title))
            .setContentText(AniListSync.lastImport).setOngoing(false).setAutoCancel(true).build())
        Result.success()
    }

    /** Progreso para la ventana de sincronización (AniListSyncDialog). episodes = -1: aún procesando ese anime. */
    private suspend fun workProgress(index: Int, total: Int, media: AniListApi.Media, episodes: Int, ok: Int, fail: Int, failed: Boolean) {
        runCatching {
            setProgress(workDataOf(P_INDEX to index, P_TOTAL to total, P_TITLE to media.titles.first(), P_COVER to media.cover,
                P_EPISODES to episodes, P_OK to ok, P_FAIL to fail, P_FAILED to failed))
        }
        // Una pausa breve para que la ventana alcance a mostrar el resultado (y la animación de rechazo).
        if (episodes >= 0) delay(if (failed) 900 else 150)
    }

    private fun ensureChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.anilist_channel_name), NotificationManager.IMPORTANCE_LOW))
    }

    // El canal debe existir antes de setForeground; si no, Android mata la app ("Bad notification for startForeground").
    private fun builder() = ensureChannel().let { NotificationCompat.Builder(applicationContext, CHANNEL) }
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    /** Muestra "Importando X de Y" y mantiene el trabajo en primer plano para que Android no lo corte. */
    private suspend fun progress(current: Int, total: Int, title: String) {
        val notification = builder()
            .setContentTitle(applicationContext.getString(R.string.anilist_import_progress_title, current, total))
            .setContentText(title)
            .setProgress(total, current, false)
            .setOngoing(true)
            .build()
        try {
            setForeground(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(NOTIFICATION_ID, notification)
            )
        } catch (e: Exception) {
            // En segundo plano Android 12+ puede negar el primer plano: seguimos sin él.
            notify(notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.anilist_channel_name), NotificationManager.IMPORTANCE_LOW))
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    /** Estados de Organizer: 1 viendo, 2 considerando, 3 completado, 4 abandonado, 5 pausado. */
    private fun stateFor(status: String): Int? = when (status) {
        "CURRENT", "REPEATING" -> 1
        "PLANNING" -> 2
        "COMPLETED" -> 3
        "DROPPED" -> 4
        "PAUSED" -> 5
        else -> null
    }

    companion object {
        const val NAME = "anilist-import"
        const val P_INDEX = "index"
        const val P_TOTAL = "total"
        const val P_TITLE = "title"
        const val P_COVER = "cover"
        const val P_EPISODES = "episodes"
        const val P_OK = "ok"
        const val P_FAIL = "fail"
        const val P_FAILED = "failed"
        private const val CHANNEL = "anilist_sync"
        private const val NOTIFICATION_ID = 7006

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AniListImportWorker>().setConstraints(network).build()
            )
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "$NAME-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AniListImportWorker>(12, TimeUnit.HOURS).setConstraints(network).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("$NAME-periodic")
        }

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

/** Preferencias de sincronización (claves de los switches en Ajustes). */
object AniListSync {
    const val KEY_LIBRARY = "anilist_sync_library"
    const val KEY_SCROBBLE = "anilist_auto_scrobble"
    private const val KEY_LAST_IMPORT = "anilist_last_import"

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(knf.kuma.App.context)

    val isLibrarySyncEnabled get() = prefs.getBoolean(KEY_LIBRARY, false)
    val isScrobbleEnabled get() = prefs.getBoolean(KEY_SCROBBLE, false)

    var lastImport: String?
        get() = prefs.getString(KEY_LAST_IMPORT, null)
        set(value) = prefs.edit().putString(KEY_LAST_IMPORT, value).apply()
}
