package knf.kuma.anilist

import android.util.Log
import knf.kuma.App
import knf.kuma.database.CacheDB
import knf.kuma.pojos.av1.Chapter
import knf.kuma.pojos.av1.DirectoryAV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.math.floor

/**
 * Da estado de sync (nube) a los episodios vistos que no tienen uno: los marcados antes de conectar AniList
 * o por caminos que no hacen scrobble. Se compara con el progreso real en AniList:
 * los que AniList ya tiene quedan sincronizados; si la app va más adelante, se sube el episodio más alto.
 */
object AniListBackfill {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Una revisión por anime y por sesión de la app, para no consultar AniList cada vez que se abre la ficha.
    private val checked = Collections.synchronizedSet(HashSet<Int>())

    fun check(anime: DirectoryAV1, chapters: List<Chapter>) {
        val aid = anime.aid
        if (!AniListAuth.isLoggedIn || aid == 0 || !checked.add(aid)) return
        scope.launch {
            runCatching { run(anime, chapters) }.onFailure {
                checked.remove(aid)
                Log.w("AniList", "Backfill falló para ${anime.slug}", it)
            }
        }
    }

    private fun run(anime: DirectoryAV1, chapters: List<Chapter>) {
        val aid = anime.aid
        val slug = anime.slug
        val name = anime.name
        val dao = CacheDB.INSTANCE.aniListScrobbleDAO()
        val records = CacheDB.INSTANCE.recordAV1DAO()
        val mediaId = CacheDB.INSTANCE.aniListMappingDAO().byAid(aid)?.anilistId
            ?: when (val r = AniListMatcher.findAniListId(aid, slug, name)) {
                is AniListMatcher.Lookup.Found -> r.mediaId
                AniListMatcher.Lookup.NotFound -> return
                AniListMatcher.Lookup.NetworkError -> error("sin red")
            }
        val entry = AniListApi.listEntry(mediaId) ?: error("sin red")
        // Completado (o en rewatch) cuenta como visto entero; si no, hasta su progreso.
        val watched = entry.status == "COMPLETED" || entry.status == "REPEATING"
        val current = if (watched) entry.total ?: Int.MAX_VALUE else entry.progress ?: 0
        var seen = records.byAid(aid).map { floor(it.number).toInt() }.filter { it > 0 }.toSet()
        // AniList → app: lo que ya se vio en AniList queda visto en la app.
        val fromAniList = chapters.filter { floor(it.number).toInt().let { n -> n in 1..current && n !in seen } }
        if (fromAniList.isNotEmpty()) {
            fromAniList.forEach { records.addChapterIgnore(it.asRecord(anime)) }
            seen = seen + fromAniList.map { floor(it.number).toInt() }
        }
        val missing = seen - dao.episodes(slug).toSet()
        Log.i("AniList", "Backfill: $slug estado=${entry.status} progreso=$current desde AniList=${fromAniList.size} sin estado=${missing.size}")
        if (missing.isEmpty()) return
        dao.addIgnore(missing.map {
            AniListScrobbleState(slug, it, aid, name,
                if (it <= current) AniListScrobbleState.SYNCED else AniListScrobbleState.PENDING)
        })
        // App → AniList: si la app va más adelante se sube, salvo que ya esté completado (eso sería un rewatch falso).
        val localMax = seen.max()
        if (!watched && localMax > current && AniListSync.isScrobbleEnabled)
            AniListScrobbleWorker.enqueue(App.context, aid, slug, name, localMax)
    }
}
