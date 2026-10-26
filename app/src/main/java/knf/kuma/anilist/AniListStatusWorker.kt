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
import knf.kuma.R
import knf.kuma.commons.toast
import knf.kuma.database.CacheDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Sube a AniList el estado que el usuario elige en la app (Viendo, Considerando → PLANNING, etc.). */
class AniListStatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val aid = inputData.getInt(EXTRA_AID, 0)
        val status = statusFor(inputData.getInt(EXTRA_STATE, 0))
        if (aid == 0 || status == null || !AniListAuth.isLoggedIn) return@withContext Result.success()
        if (runAttemptCount >= MAX_ATTEMPTS) return@withContext Result.failure()
        val directory = CacheDB.INSTANCE.directoryDAO().findByAid(aid)
        val slug = inputData.getString(EXTRA_SLUG) ?: directory?.slug ?: return@withContext Result.failure()
        val name = inputData.getString(EXTRA_NAME) ?: directory?.name ?: return@withContext Result.failure()
        val mediaId = when (val r = AniListMatcher.findAniListId(aid, slug, name)) {
            is AniListMatcher.Lookup.Found -> r.mediaId
            AniListMatcher.Lookup.NotFound -> {
                notify(applicationContext.getString(R.string.anilist_status_failed, name))
                return@withContext Result.failure()
            }
            AniListMatcher.Lookup.NetworkError -> return@withContext Result.retry()
        }
        Log.i("AniList", "Estado: aid=$aid mediaId=$mediaId -> $status")
        val saved = AniListAuth.query(
            "mutation (\$m: Int, \$s: MediaListStatus) { SaveMediaListEntry(mediaId: \$m, status: \$s) { id status } }",
            JSONObject().put("m", mediaId).put("s", status)
        )?.optJSONObject("SaveMediaListEntry") != null
        if (saved) {
            notify(applicationContext.getString(R.string.anilist_status_registered, name, labelFor(status)))
            Result.success()
        } else if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            notify(applicationContext.getString(R.string.anilist_status_failed, name))
            Result.failure()
        } else Result.retry()
    }

    private suspend fun notify(message: String) = withContext(Dispatchers.Main) { message.toast() }

    companion object {
        private const val EXTRA_AID = "anilist_aid"
        private const val EXTRA_SLUG = "anilist_slug"
        private const val EXTRA_NAME = "anilist_name"
        private const val EXTRA_STATE = "anilist_state"
        private const val MAX_ATTEMPTS = 5

        /** Estados de Organizer: 1 Viendo, 2 Considerando, 3 Completado, 4 Dropeado, 5 Pausado. */
        fun statusFor(state: Int): String? = when (state) {
            1 -> "CURRENT"
            2 -> "PLANNING"
            3 -> "COMPLETED"
            4 -> "DROPPED"
            5 -> "PAUSED"
            else -> null
        }

        fun labelFor(status: String): String = when (status) {
            "CURRENT" -> "Viendo"
            "PLANNING" -> "Planeado"
            "COMPLETED" -> "Completado"
            "DROPPED" -> "Abandonado"
            "PAUSED" -> "Pausado"
            else -> status
        }

        fun enqueue(context: Context, aid: Int, slug: String?, name: String?, state: Int) {
            if (!AniListSync.isScrobbleEnabled || !AniListAuth.isLoggedIn || statusFor(state) == null) return
            val request = OneTimeWorkRequestBuilder<AniListStatusWorker>()
                .setInputData(workDataOf(EXTRA_AID to aid, EXTRA_SLUG to slug, EXTRA_NAME to name, EXTRA_STATE to state))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Si cambia de estado varias veces seguidas, solo se manda el último.
            WorkManager.getInstance(context).enqueueUniqueWork("anilist-status-$aid", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
