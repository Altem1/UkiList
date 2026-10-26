package knf.kuma.anilist

import android.net.Uri
import android.util.Log
import knf.kuma.commons.JsExtractor
import knf.kuma.database.CacheDB
import knf.kuma.pojos.av1.DirectoryAV1Min
import java.text.Normalizer

/** Empareja animes de animeav1 con AniList y cachea el resultado en anilist_mapping. */
object AniListMatcher {

    private val dao get() = CacheDB.INSTANCE.aniListMappingDAO()

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).lowercase().replace(Regex("[^a-z0-9]"), "")

    private val ordinal = Regex("(\\d+)(st|nd|rd|th)")
    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5")

    /**
     * Forma canónica más tolerante: quita "season"/"part"/"the", pasa "2nd" y "II" a "2" y descarta signos.
     * Así "Kimetsu no Yaiba Season 2" y "Kimetsu no Yaiba 2nd Season" coinciden.
     */
    fun canonical(s: String): String {
        val words = Normalizer.normalize(s, Normalizer.Form.NFD).lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(ordinal, "$1")
            .split(" ").filter { it.isNotBlank() }
            .map { roman[it] ?: it }
            .filter { it !in setOf("season", "part", "the", "tv", "cour") }
        return words.joinToString("")
    }

    /** AniList → app. Solo acepta coincidencias de título (exactas o canónicas) para no marcar el anime equivocado. */
    suspend fun findAppAnime(media: AniListApi.Media): AniListMapping? {
        dao.byAniList(media.id)?.let { return it }
        val wanted = media.titles.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val wantedCanon = media.titles.map(::canonical).filter { it.isNotEmpty() }.toSet()
        // Títulos (rōmaji, inglés, sinónimos) y, de respaldo, la parte antes de ":" del rōmaji.
        val queries = (media.titles.filter { it.any { c -> c.code < 0x3000 } } +
                media.titles.first().substringBefore(":").trim())
            .distinctBy(::normalize).take(5)
        val seen = mutableListOf<String>()
        for (title in queries) {
            val candidates = searchCatalog(title)
            seen += candidates.map { it.name }
            val match = candidates.firstOrNull { normalize(it.name) in wanted }
                ?: candidates.firstOrNull { canonical(it.name) in wantedCanon }
                ?: candidates.singleOrNull()?.takeIf { wanted.any { w -> normalize(it.name).startsWith(w) || w.startsWith(normalize(it.name)) } }
            if (match != null)
                return AniListMapping(match.aid, match.slug, media.id).also { dao.add(it) }
        }
        Log.i("AniList", "Sin coincidencia: ${media.titles.take(3)} | catálogo: ${seen.distinct().take(6)}")
        return null
    }

    sealed class Lookup {
        data class Found(val mediaId: Int) : Lookup()
        object NotFound : Lookup()
        object NetworkError : Lookup()
    }

    /** App → AniList. Usa el título del anime (rōmaji en animeav1) para buscar su Media ID. */
    fun findAniListId(aid: Int, slug: String, name: String): Lookup {
        if (aid != 0) dao.byAid(aid)?.let { return Lookup.Found(it.anilistId) }
        val results = AniListApi.search(name) ?: return Lookup.NetworkError
        val n = normalize(name)
        val match = results.firstOrNull { m -> m.titles.any { normalize(it) == n } } ?: results.firstOrNull()
            ?: return Lookup.NotFound
        if (aid != 0) dao.add(AniListMapping(aid, slug, match.id))
        return Lookup.Found(match.id)
    }

    /** Guarda la elección manual del usuario desde la pantalla de excepciones. */
    fun saveManual(aid: Int, slug: String, mediaId: Int) {
        if (aid != 0) dao.add(AniListMapping(aid, slug, mediaId))
    }

    /** Candidatos del catálogo para un título de AniList, cuando no hubo coincidencia exacta. */
    suspend fun catalogCandidates(title: String): List<DirectoryAV1Min> = runCatching { searchCatalog(title) }.getOrDefault(emptyList())

    fun saveMapping(aid: Int, slug: String, mediaId: Int) = dao.add(AniListMapping(aid, slug, mediaId))

    private suspend fun searchCatalog(query: String): List<DirectoryAV1Min> {
        val url = Uri.parse("https://animeav1.com/catalogo").buildUpon()
            .appendQueryParameter("search", query).build().toString()
        val result = JsExtractor.processLink(url) ?: return emptyList()
        return (0 until result.length()).mapNotNull { runCatching { DirectoryAV1Min.fromJson(result.getJSONObject(it)) }.getOrNull() }
    }
}
