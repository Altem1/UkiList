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
        Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase().replace(Regex("[^a-z0-9]"), "")

    private val ordinal = Regex("(\\d+)(st|nd|rd|th)")
    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5")
    private val numberWords = mapOf("first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5")
    private val otherRelease = listOf("movie", "special", "ova", "ona", "recap")
    private val stopWords = setOf("season", "part", "the", "tv", "cour", "movie", "gekijouban")

    /**
     * Forma canónica más tolerante: quita "season"/"part"/"movie N"/"the", pasa "2nd", "Second" y "II" a "2",
     * descarta signos y unifica rōmaji ("ou"/"o", "uu"/"u").
     * Así "Kimetsu no Yaiba Season 2" y "Kimetsu no Yaiba 2nd Season" coinciden.
     */
    fun canonical(s: String): String =
        s.split(":").joinToString("") { part ->
            val words = Normalizer.normalize(part, Normalizer.Form.NFKD).lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(ordinal, "$1")
                .split(" ").filter { it.isNotBlank() }
                .map { roman[it] ?: numberWords[it] ?: if (it == "come") "comedy" else it }
            // El número que sigue a "movie" es solo el orden de la película ("InuYasha Movie 3: ...").
            val kept = words.filterIndexed { i, w -> w !in stopWords && !(i > 0 && words[i - 1] == "movie" && w.all(Char::isDigit)) }
            // Si una parte era solo palabras ignoradas ("Sin: The Movie") se conserva, para no confundirla con la serie.
            (kept.ifEmpty { words }).joinToString("")
        }.replace("ou", "o").replace("uu", "u")

    /** Partes separadas por ":" sin importar el orden ("Tensura Nikki: Slime" = "Slime: Tensura Nikki"). */
    private fun partsKey(s: String): String =
        s.split(":").map(::canonical).filter { it.isNotEmpty() }.sorted().joinToString("|")

    /** Erratas pequeñas en títulos largos ("Maidragon"/"Maid Dragon"). */
    fun nearlyEqual(a: String, b: String): Boolean {
        val shortest = minOf(a.length, b.length)
        // Con números no se arriesga: "Tensei Part 2" y "Tensei II" quedan a 2 letras de distancia.
        if (shortest < 12 || (a + b).any(Char::isDigit)) return false
        // Si uno solo le agrega algo al final es otra entrega ("Dorei Majutsu" / "Dorei Majutsu ULT").
        if (a.startsWith(b) || b.startsWith(a)) return false
        return distance(a, b) <= if (shortest >= 24) 2 else 1
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1).also { it[0] = i + 1 }
            for (j in b.indices)
                cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun stripAccents(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{Mn}"), "")

    /**
     * Película con película y OVA/especial con OVA/especial (categorías de animeav1: 1 anime, 2 película,
     * 3 OVA, otras especial). Evita "Bocchi the Rock! Movie 1" → la serie. Sin formato no se filtra.
     */
    fun sameKind(format: String?, category: Int): Boolean = when (format) {
        null -> true
        "MOVIE" -> category == 2
        "OVA", "SPECIAL" -> category != 1 && category != 2
        else -> category != 2
    }

    /**
     * Elige el candidato del catálogo que corresponde a alguno de los títulos de AniList, o null.
     * [loose] permite el último recurso del resultado único que extiende el título.
     */
    fun pickMatch(titles: List<String>, names: List<String>, loose: Boolean = true): Int? {
        val wanted = titles.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val wantedCanon = titles.map(::canonical).filter { it.isNotEmpty() }.toSet()
        val wantedParts = titles.map(::partsKey).filter { it.isNotEmpty() }.toSet()
        fun first(test: (String) -> Boolean) = names.indexOfFirst(test).takeIf { it >= 0 }
        // Primero el título idéntico: "NEW GAME!" y "New Game!!" son iguales sin signos.
        val exact = titles.map { it.trim().lowercase() }.toSet()
        return first { it.trim().lowercase() in exact }
            ?: first { normalize(it) in wanted }
            ?: first { canonical(it) in wantedCanon }
            ?: first { partsKey(it) in wantedParts }
            ?: first { n -> wantedCanon.any { nearlyEqual(canonical(n), it) } }
            // El catálogo agrega un subtítulo ("Hanyou no Yashahime: Sengoku Otogizoushi"): solo si es el único.
            // Se descartan subtítulos con número o que marcan otra entrega (película, especial, OVA).
            ?: names.indices.filter { ":" in names[it] && canonical(names[it].substringBefore(":")) in wantedCanon }
                .filter { i -> names[i].substringAfter(":").none(Char::isDigit) && otherRelease.none { it in names[i].lowercase() } }
                .singleOrNull()
            // Único resultado que extiende el título; al revés no ("Trinity Seven Movie" no es "Trinity Seven").
            ?: names.singleOrNull()?.takeIf { n -> loose && wanted.any { w -> normalize(n).startsWith(w) } }
                ?.let { 0 }
    }

    /** AniList → app. Solo acepta coincidencias de título (exactas o canónicas) para no marcar el anime equivocado. */
    suspend fun findAppAnime(media: AniListApi.Media): AniListMapping? {
        dao.byAniList(media.id)?.let { return it }
        // Títulos (rōmaji, inglés, sinónimos) y, de respaldo, la parte antes de ":" del rōmaji; sin acentos ("Pokémon").
        val queries = (media.titles.filter { it.any { c -> c.code < 0x3000 } } +
                media.titles.first().substringBefore(":").trim())
            .map(::stripAccents).distinctBy(::normalize).take(5)
        val seen = mutableListOf<String>()
        // Primero solo el año de estreno (animeav1 filtra por año igual que AniList): "NEW GAME!" (2016) no choca
        // con "New Game!!" (2017). Si el año no coincide entre sitios, se repite sin filtro.
        for (year in if (media.year != null) listOf(media.year, null) else listOf(null))
            for (title in queries) {
                val candidates = searchCatalog(title, year).filter { sameKind(media.format, it.category) }
                seen += candidates.map { it.name }
                val match = pickMatch(media.titles, candidates.map { it.name })?.let(candidates::get)
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
        // Mismas reglas que la importación; sin coincidencia no se adivina (antes se tomaba el primer resultado
        // y "Bocchi the Rock!" acababa en la película "Bocchi the Rock! Re:").
        val match = results.firstOrNull { m -> pickMatch(m.titles, listOf(name), loose = false) != null }
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

    private suspend fun searchCatalog(query: String, year: Int? = null): List<DirectoryAV1Min> {
        val url = Uri.parse("https://animeav1.com/catalogo").buildUpon()
            .appendQueryParameter("search", query)
            .apply { if (year != null) appendQueryParameter("minYear", "$year").appendQueryParameter("maxYear", "$year") }
            .build().toString()
        val result = JsExtractor.processLink(url) ?: return emptyList()
        return (0 until result.length()).mapNotNull { runCatching { DirectoryAV1Min.fromJson(result.getJSONObject(it)) }.getOrNull() }
    }
}
