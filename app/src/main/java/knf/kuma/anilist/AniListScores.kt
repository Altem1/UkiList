package knf.kuma.anilist

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import knf.kuma.App
import knf.kuma.R
import knf.kuma.database.CacheDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Puntuación de AniList (%) junto al nombre de cada portada. Se resuelve el id de AniList (mapeo guardado o
 * búsqueda) y se piden las puntuaciones en lote; todo queda en caché 3 días para no repetir consultas.
 */
object AniListScores {

    private const val TTL = 3L * 24 * 60 * 60 * 1000
    private val prefs get() = App.context.getSharedPreferences("anilist_scores", Context.MODE_PRIVATE)
    private val queue = LinkedHashMap<Int, Pair<String, String>>() // aid -> (slug, nombre)
    private val views = mutableMapOf<Int, MutableList<WeakReference<TextView>>>()
    private var running = false
    private val wideViews = mutableMapOf<Int, MutableList<WeakReference<android.view.View>>>()

    /**
     * Tarjeta ancha de "Últimos actualizados (viendo)": nombre, próxima emisión y estrella con el %.
     * [root] debe tener los ids title, next_airing y score.
     */
    fun bindWide(root: android.view.View, aid: Int, slug: String, name: String) {
        root.setTag(R.id.anilist_score_tag, aid)
        root.findViewById<TextView>(R.id.title)?.text = name
        val scoreView = root.findViewById<TextView>(R.id.score)
        val nextView = root.findViewById<TextView>(R.id.next_airing)
        val score = cached(aid)
        scoreView?.text = when {
            score == null -> "★ …"
            score > 0 -> "★ $score%"
            else -> "★ Sin votos"
        }
        val airingAt = prefs.getLong("n_$aid", 0)
        val episode = prefs.getInt("e_$aid", 0)
        nextView?.text = when {
            score == null -> "Próximo episodio: …"
            airingAt > 0 && airingAt * 1000 > System.currentTimeMillis() -> "Próximo: Ep. $episode · " + nextText(airingAt * 1000)
            else -> "Sin próxima emisión anunciada"
        }
        if (score == null && aid > 0) {
            synchronized(this) {
                wideViews.getOrPut(aid) { mutableListOf() }.add(WeakReference(root))
                if (!queue.containsKey(aid)) queue[aid] = slug to name
            }
            root.setTag(R.id.anilist_score_name, slug to name)
            start()
        }
    }

    private fun nextText(millis: Long): String {
        val days = ((millis - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es")).format(java.util.Date(millis))
        return when (days) {
            0 -> "hoy $time"
            1 -> "mañana $time"
            else -> java.text.SimpleDateFormat("EEE d", java.util.Locale("es")).format(java.util.Date(millis)) + " $time"
        }
    }

    /** Pone [name] (+ [suffix]) en [tv] y, cuando se conozca, la puntuación al lado. */
    fun bind(tv: TextView, aid: Int, slug: String, name: String, suffix: String = "") {
        tv.setTag(R.id.anilist_score_tag, aid)
        val score = cached(aid)
        tv.text = format(tv, name, suffix, score)
        if (score == null && aid > 0) {
            synchronized(this) {
                views.getOrPut(aid) { mutableListOf() }.add(WeakReference(tv))
                if (!queue.containsKey(aid)) queue[aid] = slug to name
            }
            tv.setTag(R.id.anilist_score_name, name to suffix)
            start()
        }
    }

    /** null = aún no se sabe; -1 = AniList no tiene puntuación o no se encontró. */
    private fun cached(aid: Int): Int? {
        val time = prefs.getLong("t_$aid", 0)
        if (System.currentTimeMillis() - time > TTL) return null
        return prefs.getInt("s_$aid", -1)
    }

    private fun format(tv: TextView, name: String, suffix: String, score: Int?): CharSequence {
        // Formato: "85%  Nombre del anime"
        val text = SpannableStringBuilder()
        if (score != null && score > 0) {
            val start = 0
            text.append("$score%")
            val color = ContextCompat.getColor(tv.context, if (score >= 75) R.color.anilist_score_good else if (score >= 60) R.color.anilist_score_mid else R.color.anilist_score_low)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.9f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("  ")
        }
        text.append(name)
        text.append(suffix)
        return text
    }

    private fun start() {
        synchronized(this) {
            if (running) return
            running = true
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val batch = synchronized(this@AniListScores) {
                        queue.entries.take(25).map { it.key to it.value }.also { list -> list.forEach { queue.remove(it.first) } }
                    }
                    if (batch.isEmpty()) break
                    process(batch)
                }
            } finally {
                synchronized(this@AniListScores) { running = false }
            }
        }
    }

    private suspend fun process(batch: List<Pair<Int, Pair<String, String>>>) {
        val mapping = CacheDB.INSTANCE.aniListMappingDAO()
        val ids = mutableMapOf<Int, Int>() // aid -> mediaId
        for ((aid, info) in batch) {
            val known = mapping.byAid(aid)?.anilistId
            if (known != null) {
                ids[aid] = known
                continue
            }
            // Sin mapeo: se busca por título (respetando el límite de AniList).
            when (val lookup = runCatching { AniListMatcher.findAniListId(aid, info.first, info.second) }.getOrNull()) {
                is AniListMatcher.Lookup.Found -> ids[aid] = lookup.mediaId
                is AniListMatcher.Lookup.NotFound -> save(aid, -1)
                else -> {}
            }
            delay(700)
        }
        if (ids.isEmpty()) return
        val data = AniListAuth.query(
            "query (\$ids: [Int]) { Page(perPage: 50) { media(id_in: \$ids) { id averageScore nextAiringEpisode { airingAt episode } } } }",
            JSONObject().put("ids", JSONArray(ids.values.distinct()))
        ) ?: return
        val arr = data.optJSONObject("Page")?.optJSONArray("media") ?: return
        val media = (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.getInt("id") to it } }
        ids.forEach { (aid, mediaId) ->
            val m = media[mediaId]
            val next = m?.optJSONObject("nextAiringEpisode")
            save(aid, m?.let { if (it.isNull("averageScore")) -1 else it.getInt("averageScore") } ?: -1,
                next?.optLong("airingAt") ?: 0, next?.optInt("episode") ?: 0)
        }
    }

    private suspend fun save(aid: Int, score: Int, airingAt: Long = 0, episode: Int = 0) {
        prefs.edit { putInt("s_$aid", score).putLong("t_$aid", System.currentTimeMillis()).putLong("n_$aid", airingAt).putInt("e_$aid", episode) }
        val wide = synchronized(this) { wideViews.remove(aid) }
        if (wide != null) withContext(Dispatchers.Main) {
            wide.mapNotNull { it.get() }.filter { it.getTag(R.id.anilist_score_tag) == aid }.forEach { root ->
                val (slug, name) = (root.getTag(R.id.anilist_score_name) as? Pair<*, *>) ?: return@forEach
                bindWide(root, aid, slug as String, name as String)
            }
        }
        val targets = synchronized(this) { views.remove(aid) } ?: return
        withContext(Dispatchers.Main) {
            targets.mapNotNull { it.get() }.forEach { tv ->
                // Solo si la vista sigue mostrando ese anime (las listas reciclan vistas).
                if (tv.getTag(R.id.anilist_score_tag) != aid) return@forEach
                val (name, suffix) = (tv.getTag(R.id.anilist_score_name) as? Pair<*, *>)?.let { (it.first as? String ?: return@forEach) to (it.second as? String ?: "") } ?: return@forEach
                tv.text = format(tv, name, suffix, score.takeIf { it > 0 })
            }
        }
    }
}
