package knf.kuma.player

import android.util.Log
import knf.kuma.anilist.AniListAuth
import knf.kuma.anilist.AniListMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

/**
 * Tiempos de opening/ending de AniSkip (api.aniskip.com, base comunitaria por ID de MyAnimeList).
 * El ID de MAL sale de AniList a partir del mapeo del anime.
 */
object AniSkip {

    enum class Type { OP, MIXED_OP, RECAP, ED, MIXED_ED }

    /** Intervalo en milisegundos. */
    data class Interval(val type: Type, val start: Long, val end: Long) {
        operator fun contains(position: Long) = position in start until end
    }

    /** Tiempos del episodio y la duración con la que se tomaron (para descartarlos si nuestro video es otro corte). */
    data class Times(val intervals: List<Interval>, val episodeLength: Long) {
        fun matches(durationMs: Long) = episodeLength <= 0 || durationMs <= 0 || abs(durationMs - episodeLength) <= TOLERANCE_MS
    }

    private const val TOLERANCE_MS = 10_000L
    private val malIds = mutableMapOf<Int, Int?>()

    suspend fun fetch(aid: Int, slug: String, name: String, episode: Int): Times? = withContext(Dispatchers.IO) {
        runCatching {
            val mediaId = (AniListMatcher.findAniListId(aid, slug, name) as? AniListMatcher.Lookup.Found)?.mediaId ?: return@runCatching null
            val malId = malIds.getOrPut(mediaId) {
                AniListAuth.query("query (\$m: Int) { Media(id: \$m) { idMal } }", JSONObject().put("m", mediaId))
                    ?.optJSONObject("Media")?.optInt("idMal")?.takeIf { it > 0 }
            } ?: return@runCatching null
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode" +
                    "?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=0"
            val json = AniListAuth.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                JSONObject(r.body.string())
            }
            val results = json.optJSONArray("results") ?: return@runCatching null
            var length = 0L
            val intervals = (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val type = when (o.optString("skipType")) {
                    "op" -> Type.OP
                    "mixed-op" -> Type.MIXED_OP
                    "recap" -> Type.RECAP
                    "ed" -> Type.ED
                    "mixed-ed" -> Type.MIXED_ED
                    else -> return@mapNotNull null
                }
                val interval = o.optJSONObject("interval") ?: return@mapNotNull null
                length = maxOf(length, (o.optDouble("episodeLength", 0.0) * 1000).toLong())
                Interval(type, (interval.optDouble("startTime") * 1000).toLong(), (interval.optDouble("endTime") * 1000).toLong())
            }
            Log.i("AniSkip", "mal=$malId ep=$episode -> $intervals")
            Times(intervals, length).takeIf { intervals.isNotEmpty() }
        }.getOrNull()
    }
}
