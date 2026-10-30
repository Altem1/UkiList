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
import knf.kuma.commons.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deja un anime o un personaje en favoritos de AniList (o lo quita) según el corazón de la app. */
class AniListFavouriteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AniListAuth.isLoggedIn) return@withContext Result.success()
        if (runAttemptCount >= MAX_ATTEMPTS) return@withContext Result.failure()
        val favourite = inputData.getBoolean(EXTRA_FAV, true)
        val characterId = inputData.getInt(EXTRA_CHARACTER, 0)
        val animeId = if (characterId != 0) 0 else inputData.getInt(EXTRA_MEDIA, 0).takeIf { it != 0 } ?: run {
            val aid = inputData.getInt(EXTRA_AID, 0)
            val slug = inputData.getString(EXTRA_SLUG) ?: return@withContext Result.failure()
            val name = inputData.getString(EXTRA_NAME) ?: return@withContext Result.failure()
            when (val r = AniListMatcher.findAniListId(aid, slug, name)) {
                is AniListMatcher.Lookup.Found -> r.mediaId
                AniListMatcher.Lookup.NotFound -> return@withContext Result.failure()
                AniListMatcher.Lookup.NetworkError -> return@withContext Result.retry()
            }
        }
        // ToggleFavourite invierte: solo se llama si el estado actual no es el deseado.
        val current = if (characterId != 0) AniListApi.isCharacterFavourite(characterId) else AniListApi.isFavourite(animeId)
        if (current == null) return@withContext Result.retry()
        if (current == favourite) return@withContext Result.success()
        val ok = if (characterId != 0) AniListApi.toggleFavourite(characterId = characterId) else AniListApi.toggleFavourite(animeId = animeId)
        Log.i("AniList", "Favorito: anime=$animeId personaje=$characterId -> $favourite ($ok)")
        if (ok) Result.success()
        else if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            withContext(Dispatchers.Main) { "No se pudo actualizar favoritos en AniList".toast() }
            Result.failure()
        } else Result.retry()
    }

    companion object {
        private const val EXTRA_AID = "fav_aid"
        private const val EXTRA_SLUG = "fav_slug"
        private const val EXTRA_NAME = "fav_name"
        private const val EXTRA_MEDIA = "fav_media"
        private const val EXTRA_CHARACTER = "fav_character"
        private const val EXTRA_FAV = "fav_state"
        private const val MAX_ATTEMPTS = 5

        fun anime(context: Context, aid: Int, slug: String, name: String, favourite: Boolean) {
            enqueue(context, "anilist-fav-anime-$aid", workDataOf(EXTRA_AID to aid, EXTRA_SLUG to slug, EXTRA_NAME to name, EXTRA_FAV to favourite))
        }

        fun character(context: Context, characterId: Int, favourite: Boolean) {
            enqueue(context, "anilist-fav-char-$characterId", workDataOf(EXTRA_CHARACTER to characterId, EXTRA_FAV to favourite))
        }

        private fun enqueue(context: Context, name: String, data: androidx.work.Data) {
            if (!AniListAuth.isLoggedIn) return
            val request = OneTimeWorkRequestBuilder<AniListFavouriteWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Si el usuario toca varias veces, solo cuenta el último estado.
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
