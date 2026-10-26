package knf.kuma.anilist

import org.json.JSONArray
import org.json.JSONObject

/** Consultas GraphQL a AniList. Todas son bloqueantes: llamar fuera del hilo principal. */
object AniListApi {

    data class Media(val id: Int, val titles: List<String>, val episodes: Int?, val cover: String? = null)

    data class Entry(val media: Media, val status: String, val progress: Int)

    private val MEDIA_FIELDS = "id episodes synonyms title { romaji english native } coverImage { medium }"

    /** Elemento de la lista del usuario para la pantalla AniList (portada, progreso, puntuación). */
    data class ListItem(
        val mediaId: Int,
        val title: String,
        val cover: String?,
        val status: String,
        val progress: Int,
        val episodes: Int?,
        val score: Double,
        val updatedAt: Long
    )

    fun userListDetailed(userId: Int): List<ListItem>? {
        val data = AniListAuth.query(
            "query (\$u: Int) { MediaListCollection(userId: \$u, type: ANIME) { lists { entries { status progress score(format: POINT_10_DECIMAL) updatedAt media { id episodes title { romaji english native } coverImage { large } } } } } }",
            JSONObject().put("u", userId)
        ) ?: return null
        val lists = data.getJSONObject("MediaListCollection").getJSONArray("lists")
        val result = mutableListOf<ListItem>()
        for (i in 0 until lists.length()) {
            val entries = lists.getJSONObject(i).getJSONArray("entries")
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                val m = e.getJSONObject("media")
                result += ListItem(
                    m.getInt("id"),
                    m.getJSONObject("title").let { t -> listOf("romaji", "english", "native").mapNotNull { k -> t.optString(k).takeIf { it.isNotBlank() && it != "null" } }.firstOrNull() ?: "" },
                    m.optJSONObject("coverImage")?.optString("large"),
                    e.getString("status"),
                    e.optInt("progress"),
                    if (m.isNull("episodes")) null else m.getInt("episodes"),
                    e.optDouble("score", 0.0),
                    e.optLong("updatedAt")
                )
            }
        }
        return result.distinctBy { it.mediaId }.sortedByDescending { it.updatedAt }
    }

    private fun JSONObject.toMedia(): Media {
        val title = getJSONObject("title")
        val titles = listOf("romaji", "english", "native").mapNotNull { title.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } } +
                (optJSONArray("synonyms") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        return Media(getInt("id"), titles, if (isNull("episodes")) null else getInt("episodes"),
            optJSONObject("coverImage")?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" })
    }

    /** null si falló la conexión; lista vacía si AniList no encontró nada. */
    fun search(title: String): List<Media>? {
        val data = AniListAuth.query(
            "query (\$s: String) { Page(perPage: 8) { media(search: \$s, type: ANIME) { $MEDIA_FIELDS } } }",
            JSONObject().put("s", title)
        ) ?: return null
        val arr = data.getJSONObject("Page").getJSONArray("media")
        return (0 until arr.length()).map { arr.getJSONObject(it).toMedia() }
    }

    fun userList(userId: Int): List<Entry>? {
        val data = AniListAuth.query(
            "query (\$u: Int) { MediaListCollection(userId: \$u, type: ANIME) { lists { entries { status progress media { $MEDIA_FIELDS } } } } }",
            JSONObject().put("u", userId)
        ) ?: return null
        val lists = data.getJSONObject("MediaListCollection").getJSONArray("lists")
        val result = mutableListOf<Entry>()
        for (i in 0 until lists.length()) {
            val entries = lists.getJSONObject(i).getJSONArray("entries")
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                result += Entry(e.getJSONObject("media").toMedia(), e.getString("status"), e.optInt("progress"))
            }
        }
        return result.distinctBy { it.media.id }
    }

    /** Entrada del usuario en ese anime: progreso/estado/repeticiones (null si no está en su lista) y total de episodios. */
    data class ListEntry(val progress: Int?, val total: Int?, val status: String?, val repeat: Int)

    fun listEntry(mediaId: Int): ListEntry? {
        val data = AniListAuth.query(
            "query (\$m: Int) { Media(id: \$m) { episodes mediaListEntry { progress status repeat } } }",
            JSONObject().put("m", mediaId)
        )?.optJSONObject("Media") ?: return null
        val entry = data.optJSONObject("mediaListEntry")
        val total = if (data.isNull("episodes")) null else data.getInt("episodes")
        return ListEntry(entry?.optInt("progress"), total, entry?.optString("status"), entry?.optInt("repeat") ?: 0)
    }

    /** Progreso actual del usuario en ese anime (null si no está en su lista) y total de episodios. */
    fun currentProgress(mediaId: Int): Pair<Int?, Int?>? = listEntry(mediaId)?.let { it.progress to it.total }

    /** status: CURRENT, COMPLETED, REPEATING... repeat != null cambia el contador de rewatch. True si AniList lo aceptó. */
    fun saveProgress(mediaId: Int, progress: Int, status: String?, repeat: Int? = null): Boolean {
        val vars = JSONObject().put("m", mediaId).put("p", progress)
        status?.let { vars.put("s", it) }
        repeat?.let { vars.put("r", it) }
        return AniListAuth.query(
            "mutation (\$m: Int, \$p: Int, \$s: MediaListStatus, \$r: Int) { SaveMediaListEntry(mediaId: \$m, progress: \$p, status: \$s, repeat: \$r) { id progress status repeat } }",
            vars
        )?.optJSONObject("SaveMediaListEntry") != null
    }

    data class Stat(val label: String, val count: Int, val minutesWatched: Int, val meanScore: Double)

    data class AnimeStatistics(
        val count: Int,
        val episodesWatched: Int,
        val minutesWatched: Int,
        val meanScore: Double,
        val standardDeviation: Double,
        val scores: List<Stat>,
        val genres: List<Stat>,
        val releaseYears: List<Stat>,
        val formats: List<Stat>,
        val statuses: List<Stat>
    )

    data class FavouriteAnime(val mediaId: Int, val title: String, val cover: String?)

    /** Personaje, staff o estudio favorito: se abre en la web de AniList. */
    data class FavouriteItem(val name: String, val image: String?, val url: String?)

    data class UserProfile(
        val id: Int,
        val name: String,
        val about: String?,
        val avatarUrl: String?,
        val bannerUrl: String?,
        val createdAt: Long,
        val siteUrl: String?,
        val favourites: List<FavouriteAnime>,
        val favCharacters: List<FavouriteItem>,
        val favStaff: List<FavouriteItem>,
        val favStudios: List<FavouriteItem>,
        val stats: AnimeStatistics
    )

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONArray?.stats(labelKey: String): List<Stat> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val label = o.str(labelKey) ?: return@mapNotNull null
            Stat(label, o.optInt("count"), o.optInt("minutesWatched"), o.optDouble("meanScore", 0.0).takeIf { !it.isNaN() } ?: 0.0)
        }
    }

    /** Perfil + estadísticas de anime del usuario en una sola petición. null si falló. */
    fun userProfile(userId: Int): UserProfile? {
        val stat = "count minutesWatched meanScore"
        val data = AniListAuth.query(
            "query (\$u: Int) { User(id: \$u) { id name about(asHtml: false) avatar { large } bannerImage createdAt siteUrl " +
                    "favourites { anime(page: 1, perPage: 15) { nodes { id title { romaji english native } coverImage { large } } } " +
                    "characters(page: 1, perPage: 15) { nodes { name { userPreferred } image { large } siteUrl } } " +
                    "staff(page: 1, perPage: 15) { nodes { name { userPreferred } image { large } siteUrl } } " +
                    "studios(page: 1, perPage: 15) { nodes { name siteUrl } } } " +
                    "statistics { anime { count episodesWatched minutesWatched meanScore standardDeviation " +
                    "statuses { status $stat } formats { format $stat } scores(sort: MEAN_SCORE) { score count minutesWatched } " +
                    "genres(limit: 15, sort: COUNT_DESC) { genre $stat } releaseYears(sort: ID_DESC) { releaseYear $stat } } } } }",
            JSONObject().put("u", userId)
        )?.optJSONObject("User") ?: return null
        val a = data.optJSONObject("statistics")?.optJSONObject("anime") ?: JSONObject()
        val favNodes = data.optJSONObject("favourites")?.optJSONObject("anime")?.optJSONArray("nodes") ?: JSONArray()
        val favs = (0 until favNodes.length()).mapNotNull { i ->
            val m = favNodes.optJSONObject(i) ?: return@mapNotNull null
            val t = m.optJSONObject("title") ?: JSONObject()
            FavouriteAnime(m.optInt("id"), listOf("romaji", "english", "native").firstNotNullOfOrNull { t.str(it) } ?: "", m.optJSONObject("coverImage")?.str("large"))
        }
        val favRoot = data.optJSONObject("favourites")
        fun people(key: String) = favRoot?.optJSONObject(key)?.optJSONArray("nodes").let { arr ->
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val n = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val name = n.optJSONObject("name")?.str("userPreferred") ?: n.str("name") ?: return@mapNotNull null
                FavouriteItem(name, n.optJSONObject("image")?.str("large"), n.str("siteUrl"))
            }
        }
        return UserProfile(
            data.optInt("id"), data.str("name") ?: "", data.str("about"), data.optJSONObject("avatar")?.str("large"),
            data.str("bannerImage"), data.optLong("createdAt"), data.str("siteUrl"), favs,
            people("characters"), people("staff"), people("studios"),
            AnimeStatistics(
                a.optInt("count"), a.optInt("episodesWatched"), a.optInt("minutesWatched"),
                a.optDouble("meanScore", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                a.optDouble("standardDeviation", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                a.optJSONArray("scores").stats("score").sortedByDescending { it.label.toIntOrNull() ?: 0 },
                a.optJSONArray("genres").stats("genre"),
                a.optJSONArray("releaseYears").stats("releaseYear"),
                a.optJSONArray("formats").stats("format").sortedByDescending { it.count },
                a.optJSONArray("statuses").stats("status").sortedByDescending { it.count }
            )
        )
    }
}
