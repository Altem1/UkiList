package knf.kuma.anilist

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import knf.kuma.App
import knf.kuma.R
import knf.kuma.commons.toast
import knf.kuma.database.CacheDB
import knf.kuma.pojos.av1.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** Sincroniza con AniList los episodios que el usuario marca o desmarca a mano. */
object AniListSeenHook {

    private val enabled get() = AniListSync.isScrobbleEnabled && AniListAuth.isLoggedIn

    fun onSeen(records: List<Record>) {
        if (!enabled) return
        // Por anime basta con mandar el episodio más alto: AniList guarda progreso, no episodios sueltos.
        records.groupBy { it.aid }.values.forEach { list ->
            val last = list.maxBy { it.number }
            AniListScrobbleWorker.enqueue(App.context, last.aid, last.slug, last.name, floor(last.number).toInt())
        }
    }

    fun onUnseen(aid: Int) {
        if (!enabled) return
        // La nube se quita al momento, aunque no haya red; AniList se actualiza después.
        val localMax = runBlocking { CacheDB.INSTANCE.recordAV1DAO().getLastByAid(aid)?.number?.let { floor(it).toInt() } } ?: 0
        CacheDB.INSTANCE.aniListScrobbleDAO().removeAbove(aid, localMax)
        AniListUnscrobbleWorker.enqueue(App.context, aid)
    }
}

/** Baja el progreso en AniList al mayor episodio que siga marcado como visto en la app. */
class AniListUnscrobbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val aid = inputData.getInt(EXTRA_AID, 0)
        if (aid == 0 || !AniListAuth.isLoggedIn) return@withContext Result.success()
        val localMax = CacheDB.INSTANCE.recordAV1DAO().getLastByAid(aid)?.number?.let { floor(it).toInt() } ?: 0
        CacheDB.INSTANCE.aniListScrobbleDAO().removeAbove(aid, localMax)
        if (runAttemptCount >= MAX_ATTEMPTS) return@withContext Result.failure()
        val mapping = CacheDB.INSTANCE.aniListMappingDAO().byAid(aid)
        val directory = CacheDB.INSTANCE.directoryDAO().findByAid(aid)
        val slug = mapping?.slug ?: directory?.slug ?: return@withContext Result.failure()
        val mediaId = mapping?.anilistId ?: when (val r = AniListMatcher.findAniListId(aid, slug, directory?.name ?: return@withContext Result.failure())) {
            is AniListMatcher.Lookup.Found -> r.mediaId
            AniListMatcher.Lookup.NotFound -> return@withContext Result.failure()
            AniListMatcher.Lookup.NetworkError -> return@withContext Result.retry()
        }
        val (current, _) = AniListApi.currentProgress(mediaId) ?: return@withContext Result.retry()
        Log.i("AniList", "Unscrobble: aid=$aid mediaId=$mediaId actual=$current -> $localMax")
        if (current == null || current <= localMax) return@withContext Result.success()
        // Sin episodios vistos vuelve a "Planeado" en vez de quedar en "Viendo" con 0.
        val status = if (localMax == 0) "PLANNING" else "CURRENT"
        if (!AniListApi.saveProgress(mediaId, localMax, status)) return@withContext Result.retry()
        val name = directory?.name ?: "anime"
        val msg = if (localMax == 0) applicationContext.getString(R.string.anilist_unscrobble_planning, name)
            else applicationContext.getString(R.string.anilist_unscrobble_progress, name, localMax)
        withContext(Dispatchers.Main) { msg.toast() }
        Result.success()
    }

    companion object {
        private const val EXTRA_AID = "anilist_aid"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, aid: Int) {
            // Espera unos segundos y reemplaza: si el usuario desmarca varios seguidos, solo se manda una vez.
            val request = OneTimeWorkRequestBuilder<AniListUnscrobbleWorker>()
                .setInputData(workDataOf(EXTRA_AID to aid))
                .setInitialDelay(2, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("anilist-unscrobble-$aid", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
