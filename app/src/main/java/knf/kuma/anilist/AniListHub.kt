package knf.kuma.anilist

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import knf.kuma.App
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Datos extra para la ficha estilo hub: detalles de AniList (portada, puntuación, director, personajes,
 * idiomas de doblaje, recomendaciones) y títulos/sinopsis de episodios (AniList + Kitsu) traducidos al español.
 * Todo es bloqueante: llamar fuera del hilo principal.
 */
object AniListHub {

    data class Details(
        val mediaId: Int,
        val banner: String?,
        val cover: String?,
        val averageScore: Int?,
        val popularity: Int,
        val favourites: Int,
        val season: String?,
        val seasonYear: Int?,
        val status: String?,
        val episodes: Int?,
        val duration: Int?,
        val studios: List<String>,
        val directors: List<String>,
        val characters: List<AniListApi.Character>,
        val dubLanguages: List<String>,
        val episodeTitles: Map<Int, String>,
        val recommendations: List<Pair<AniListApi.Media, String?>>,
        val trailerYoutube: String?
    )

    /** Opening/ending (AnimeThemes): tipo "OP 1", canción, artistas, foto del artista y enlaces para escucharla. */
    data class Theme(val label: String, val title: String, val artists: String, val image: String?, val links: List<Pair<String, String>>)

    data class EpisodeMeta(val title: String?, val synopsis: String?)

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val prefs get() = App.context.getSharedPreferences("hub_meta", Context.MODE_PRIVATE)

    fun details(mediaId: Int): Details? {
        val m = AniListAuth.query(
            "query (\$m: Int) { Media(id: \$m) { id bannerImage coverImage { extraLarge } averageScore popularity favourites season seasonYear status episodes duration " +
                    "studios(isMain: true) { nodes { name } } staff(perPage: 15, sort: [RELEVANCE]) { edges { role node { name { full } } } } " +
                    "characters(sort: [ROLE, RELEVANCE], perPage: 18) { edges { role node { id isFavourite name { full } image { medium } } voiceActors { languageV2 } } } " +
                    "streamingEpisodes { title } trailer { id site } " +
                    "recommendations(sort: RATING_DESC, perPage: 25) { nodes { mediaRecommendation { id format startDate { year } episodes synonyms title { romaji english native } coverImage { large } mediaListEntry { status } } } } } }",
            JSONObject().put("m", mediaId)
        )?.optJSONObject("Media") ?: return null

        fun JSONObject.str(k: String) = optString(k).takeIf { !isNull(k) && it.isNotBlank() && it != "null" }
        fun JSONObject.int(k: String) = if (isNull(k)) null else optInt(k)
        fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

        val staff = m.optJSONObject("staff")?.optJSONArray("edges").objects()
        val chars = m.optJSONObject("characters")?.optJSONArray("edges").objects()
        val episodeRegex = Regex("""^Episode\s+(\d+)\s*-\s*(.+)$""", RegexOption.IGNORE_CASE)
        val recs = m.optJSONObject("recommendations")?.optJSONArray("nodes").objects().mapNotNull { node ->
            val r = node.optJSONObject("mediaRecommendation") ?: return@mapNotNull null
            val title = r.getJSONObject("title")
            val titles = listOf("romaji", "english", "native").mapNotNull { title.str(it) }
            AniListApi.Media(r.getInt("id"), titles, r.int("episodes"), r.optJSONObject("coverImage")?.str("large"), r.str("format"),
                r.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }) to r.optJSONObject("mediaListEntry")?.str("status")
        }
        return Details(
            mediaId,
            m.str("bannerImage"),
            m.optJSONObject("coverImage")?.str("extraLarge"),
            m.int("averageScore"),
            m.optInt("popularity"),
            m.optInt("favourites"),
            m.str("season"),
            m.int("seasonYear"),
            m.str("status"),
            m.int("episodes"),
            m.int("duration"),
            m.optJSONObject("studios")?.optJSONArray("nodes").objects().mapNotNull { it.str("name") },
            staff.filter { it.optString("role") == "Director" }.mapNotNull { it.optJSONObject("node")?.optJSONObject("name")?.str("full") },
            chars.map { e ->
                val node = e.getJSONObject("node")
                AniListApi.Character(node.getInt("id"), node.getJSONObject("name").str("full") ?: "", node.optJSONObject("image")?.str("medium"),
                    e.optString("role"), node.optBoolean("isFavourite"))
            },
            chars.flatMap { e -> e.optJSONArray("voiceActors").objects().mapNotNull { it.str("languageV2") } }.distinct().map(::languageName).sorted(),
            m.optJSONArray("streamingEpisodes").objects().mapNotNull { e ->
                episodeRegex.find(e.optString("title"))?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() }
            }.toMap(),
            recs,
            m.optJSONObject("trailer")?.takeIf { it.optString("site") == "youtube" }?.str("id")
        )
    }

    /** Openings y endings desde api.animethemes.moe usando el id de AniList; lista vacía si no hay. */
    fun themes(mediaId: Int): List<Theme> {
        val json = get("https://api.animethemes.moe/anime?filter%5Bhas%5D=resources&filter%5Bsite%5D=AniList&filter%5Bexternal_id%5D=$mediaId" +
                "&include=animethemes.song.artists.images,animethemes.animethemeentries.videos") ?: return emptyList()
        val anime = json.optJSONArray("anime")?.optJSONObject(0) ?: return emptyList()
        val themes = anime.optJSONArray("animethemes") ?: return emptyList()
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return (0 until themes.length()).mapNotNull { i ->
            val t = themes.getJSONObject(i)
            val song = t.optJSONObject("song") ?: return@mapNotNull null
            val title = song.optString("title").takeIf { !song.isNull("title") && it.isNotBlank() } ?: return@mapNotNull null
            val artistsArr = song.optJSONArray("artists") ?: JSONArray()
            val artists = (0 until artistsArr.length()).map { artistsArr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }.joinToString(", ")
            val image = (0 until artistsArr.length()).firstNotNullOfOrNull { a ->
                artistsArr.getJSONObject(a).optJSONArray("images")?.let { imgs -> (0 until imgs.length()).firstNotNullOfOrNull { imgs.getJSONObject(it).optString("link").takeIf { l -> l.isNotBlank() } } }
            }
            val type = when (t.optString("type")) { "OP" -> "Opening"; "ED" -> "Ending"; else -> t.optString("type") }
            val seq = if (t.isNull("sequence")) "" else " ${t.optInt("sequence")}"
            val video = t.optJSONArray("animethemeentries")?.optJSONObject(0)?.optJSONArray("videos")?.optJSONObject(0)?.optString("link")?.takeIf { it.isNotBlank() }
            val query = enc("$title $artists".trim())
            val links = listOfNotNull(
                "YouTube" to "https://www.youtube.com/results?search_query=$query",
                "Spotify" to "https://open.spotify.com/search/$query",
                "Apple Music" to "https://music.apple.com/search?term=$query",
                "YouTube Music" to "https://music.youtube.com/search?q=$query",
                video?.let { "Video del $type".lowercase().replaceFirstChar { c -> c.uppercase() } to it }
            )
            Theme("$type$seq", title, artists, image, links)
        }.sortedWith(compareBy({ !it.label.startsWith("Opening") }, { it.label }))
    }

    /**
     * Título y sinopsis en español por número de episodio. Usa los títulos de AniList y, si existe, Kitsu para la sinopsis;
     * se traduce con ML Kit (en el dispositivo) y se guarda en caché para no repetir descargas ni traducciones.
     */
    fun episodes(mediaId: Int, aniListTitles: Map<Int, String>): Map<Int, EpisodeMeta> {
        val key = "eps_$mediaId"
        prefs.getString(key, null)?.let { cached -> return parse(cached) }
        val raw = mutableMapOf<Int, EpisodeMeta>()
        aniListTitles.forEach { (n, t) -> raw[n] = EpisodeMeta(t, null) }
        runCatching { kitsuEpisodes(mediaId) }.getOrNull()?.forEach { (n, meta) ->
            raw[n] = EpisodeMeta(raw[n]?.title ?: meta.title, meta.synopsis)
        }
        if (raw.isEmpty()) return emptyMap()
        val translated = translate(raw) ?: return raw.mapValues { EpisodeMeta(null, null) }
        prefs.edit { putString(key, serialize(translated)) }
        return translated
    }

    private fun get(url: String): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.api+json").build()).execute().use {
            if (it.isSuccessful) JSONObject(it.body.string()) else null
        }
    }.getOrNull()

    private fun kitsuEpisodes(mediaId: Int): Map<Int, EpisodeMeta> {
        val mapping = get("https://kitsu.io/api/edge/mappings?filter%5BexternalSite%5D=anilist/anime&filter%5BexternalId%5D=$mediaId&include=item")
        val kitsuId = mapping?.optJSONArray("included")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val result = mutableMapOf<Int, EpisodeMeta>()
        var offset = 0
        while (offset < 400) {
            val page = get("https://kitsu.io/api/edge/anime/$kitsuId/episodes?page%5Blimit%5D=20&page%5Boffset%5D=$offset&sort=number") ?: break
            val data = page.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val a = data.getJSONObject(i).getJSONObject("attributes")
                if (a.isNull("number")) continue
                val title = a.optString("canonicalTitle").takeIf { !a.isNull("canonicalTitle") && it.isNotBlank() && !it.matches(Regex("(?i)episode \\d+")) }
                val synopsis = a.optString("synopsis").takeIf { !a.isNull("synopsis") && it.isNotBlank() }
                    ?.replace(Regex("""\s*\((Source|Fuente):[^)]*\)\s*$"""), "")?.trim()
                result[a.getInt("number")] = EpisodeMeta(title, synopsis)
            }
            if (data.length() < 20) break
            offset += 20
        }
        return result
    }

    /** null si no se pudo bajar el modelo de traducción (sin internet la primera vez). */
    private fun translate(raw: Map<Int, EpisodeMeta>): Map<Int, EpisodeMeta>? {
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.SPANISH).build()
        )
        return try {
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 3, TimeUnit.MINUTES)
            fun tr(s: String?) = s?.let { Tasks.await(translator.translate(it), 30, TimeUnit.SECONDS) }
            raw.mapValues { (_, meta) -> EpisodeMeta(tr(meta.title), tr(meta.synopsis)) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            translator.close()
        }
    }

    private fun serialize(map: Map<Int, EpisodeMeta>) = JSONObject().apply {
        map.forEach { (n, m) -> put(n.toString(), JSONObject().put("t", m.title ?: JSONObject.NULL).put("s", m.synopsis ?: JSONObject.NULL)) }
    }.toString()

    private fun parse(json: String): Map<Int, EpisodeMeta> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associate { k ->
            val e = o.getJSONObject(k)
            k.toInt() to EpisodeMeta(e.optString("t").takeIf { !e.isNull("t") }, e.optString("s").takeIf { !e.isNull("s") })
        }
    }.getOrDefault(emptyMap())

    fun seasonName(season: String?) = when (season) {
        "WINTER" -> "Invierno"
        "SPRING" -> "Primavera"
        "SUMMER" -> "Verano"
        "FALL" -> "Otoño"
        else -> null
    }

    fun statusName(status: String?) = when (status) {
        "RELEASING" -> "En emisión"
        "FINISHED" -> "Finalizado"
        "NOT_YET_RELEASED" -> "Próximamente"
        "CANCELLED" -> "Cancelado"
        "HIATUS" -> "En pausa"
        else -> null
    }

    private fun languageName(lang: String) = when (lang) {
        "Japanese" -> "Japonés"
        "English" -> "Inglés"
        "Spanish" -> "Español"
        "Portuguese" -> "Portugués"
        "French" -> "Francés"
        "German" -> "Alemán"
        "Italian" -> "Italiano"
        "Korean" -> "Coreano"
        "Chinese" -> "Chino"
        "Thai" -> "Tailandés"
        "Indonesian" -> "Indonesio"
        "Arabic" -> "Árabe"
        "Hebrew" -> "Hebreo"
        "Hungarian" -> "Húngaro"
        "Polish" -> "Polaco"
        "Russian" -> "Ruso"
        "Turkish" -> "Turco"
        else -> lang
    }
}
