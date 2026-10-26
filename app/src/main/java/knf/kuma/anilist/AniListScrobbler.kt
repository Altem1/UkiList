package knf.kuma.anilist

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
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
import knf.kuma.pojos.av1.ChapterWID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

const val EXTRA_AID = "anilist_aid"
const val EXTRA_SLUG = "anilist_slug"
const val EXTRA_NAME = "anilist_name"
const val EXTRA_NUMBER = "anilist_number"

/** Adjunta al intent del reproductor los datos del episodio necesarios para el scrobble. */
fun Intent.withScrobble(chapter: ChapterWID?): Intent = apply {
    chapter ?: return@apply
    putExtra(EXTRA_AID, chapter.aid)
    putExtra(EXTRA_SLUG, chapter.slug)
    putExtra(EXTRA_NAME, chapter.name)
    putExtra(EXTRA_NUMBER, chapter.number)
}

/**
 * Observa un Player y, al llegar al 85% o terminar el video, encola el scrobble en WorkManager
 * (sobrevive aunque el usuario cierre el reproductor de golpe).
 */
class AniListScrobbleTracker(private val context: Context, intent: Intent) : Player.Listener {

    private val aid = intent.getIntExtra(EXTRA_AID, 0)
    private val slug = intent.getStringExtra(EXTRA_SLUG)
    private val name = intent.getStringExtra(EXTRA_NAME)
    private val number = intent.getDoubleExtra(EXTRA_NUMBER, 0.0)

    private val enabled get() = slug != null && name != null && number > 0 &&
            AniListSync.isScrobbleEnabled && AniListAuth.isLoggedIn

    private var player: Player? = null
    private var sent = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            check()
            if (!sent) handler.postDelayed(this, 10_000)
        }
    }

    fun attach(player: Player) {
        if (!enabled) return
        this.player = player
        player.addListener(this)
        handler.postDelayed(poll, 10_000)
    }

    /** Llamar antes de liberar el player: hace una última revisión del progreso. */
    fun detach() {
        check()
        handler.removeCallbacks(poll)
        player?.removeListener(this)
        player = null
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) send()
    }

    private fun check() {
        val p = player ?: return
        val duration = p.duration
        if (duration > 0 && p.currentPosition >= duration * 0.85) send()
    }

    private fun send() {
        if (sent || !enabled) return
        sent = true
        AniListScrobbleWorker.enqueue(context, aid, slug!!, name!!, floor(number).toInt())
    }
}

class AniListScrobbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val states = CacheDB.INSTANCE.aniListScrobbleDAO()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val slug = inputData.getString(EXTRA_SLUG)
        val name = inputData.getString(EXTRA_NAME)
        val episode = inputData.getInt(EXTRA_NUMBER, 0)
        if (slug == null || name == null || episode <= 0) return@withContext Result.failure()
        val aid = inputData.getInt(EXTRA_AID, 0).takeIf { it != 0 }
            ?: CacheDB.INSTANCE.directoryDAO().findBySlug(slug)?.aid ?: 0
        fun state(status: Int, reason: String? = null) {
            states.set(AniListScrobbleState(slug, episode, aid, name, status, reason))
            if (status == AniListScrobbleState.SYNCED) states.markSyncedUpTo(slug, episode)
        }
        fun failOrRetry(reason: String): Result =
            if (runAttemptCount >= MAX_ATTEMPTS) { state(AniListScrobbleState.FAILED, reason); Result.failure() }
            else { state(AniListScrobbleState.PENDING, reason); Result.retry() }

        // Si lo desmarcó mientras esperaba red, ya no se sube.
        if (aid != 0 && inputData.getInt(EXTRA_MEDIA_ID, 0) == 0 &&
            (CacheDB.INSTANCE.recordAV1DAO().getLastByAid(aid)?.number?.let { floor(it).toInt() } ?: 0) < episode) {
            states.remove(slug, episode)
            return@withContext Result.success()
        }
        if (!AniListAuth.isLoggedIn) return@withContext failOrRetry(applicationContext.getString(R.string.anilist_reason_no_session))
        Log.i("AniList", "Scrobble: aid=$aid slug=$slug name=$name ep=$episode")
        val forced = inputData.getInt(EXTRA_MEDIA_ID, 0)
        val mediaId = if (forced != 0) forced else when (val r = AniListMatcher.findAniListId(aid, slug, name)) {
            is AniListMatcher.Lookup.Found -> r.mediaId
            AniListMatcher.Lookup.NotFound -> {
                state(AniListScrobbleState.FAILED, applicationContext.getString(R.string.anilist_reason_not_found, name))
                return@withContext Result.failure()
            }
            AniListMatcher.Lookup.NetworkError -> return@withContext failOrRetry(applicationContext.getString(R.string.anilist_error_network))
        }
        val entry = AniListApi.listEntry(mediaId)
            ?: return@withContext failOrRetry(applicationContext.getString(R.string.anilist_error_network))
        val current = entry.progress
        val total = entry.total
        Log.i("AniList", "Scrobble: mediaId=$mediaId actual=$current estado=${entry.status} total=$total -> $episode")
        val finished = total != null && episode >= total
        // Volver a ver un anime ya completado cuenta como rewatch (REPEATING), no como si se viera por primera vez.
        val save: Triple<Int, String, Int?>? = when (entry.status) {
            "COMPLETED" -> if (finished) Triple(episode, "COMPLETED", entry.repeat + 1) else Triple(episode, "REPEATING", null)
            "REPEATING" -> when {
                current != null && current >= episode -> null
                finished -> Triple(episode, "COMPLETED", entry.repeat + 1)
                else -> Triple(episode, "REPEATING", null)
            }
            else -> if (current != null && current >= episode) null
                else Triple(episode, if (finished) "COMPLETED" else "CURRENT", null)
        }
        if (save == null) {
            state(AniListScrobbleState.SYNCED)
            return@withContext Result.success()
        }
        if (AniListApi.saveProgress(mediaId, save.first, save.second, save.third)) {
            state(AniListScrobbleState.SYNCED)
            val message = if (save.second == "REPEATING" || save.third != null)
                applicationContext.getString(R.string.anilist_rewatch_registered, name, episode.toString())
            else applicationContext.getString(R.string.anilist_scrobble_registered, name, episode.toString())
            withContext(Dispatchers.Main) { message.toast() }
            Result.success()
        } else failOrRetry(applicationContext.getString(R.string.anilist_reason_rejected))
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "anilist_media_id"
        private const val MAX_ATTEMPTS = 5

        /** mediaId != 0 fuerza el anime de AniList (elección manual en la pantalla de excepciones). */
        fun enqueue(context: Context, aid: Int, slug: String, name: String, episode: Int, mediaId: Int = 0) {
            Thread {
                CacheDB.INSTANCE.aniListScrobbleDAO()
                    .set(AniListScrobbleState(slug, episode, aid, name, AniListScrobbleState.PENDING))
            }.start()
            val request = OneTimeWorkRequestBuilder<AniListScrobbleWorker>()
                .setInputData(workDataOf(EXTRA_AID to aid, EXTRA_SLUG to slug, EXTRA_NAME to name,
                    EXTRA_NUMBER to episode, EXTRA_MEDIA_ID to mediaId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("anilist-scrobble-$slug-$episode", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
