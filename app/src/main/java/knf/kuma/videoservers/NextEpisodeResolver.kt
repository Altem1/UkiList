package knf.kuma.videoservers

import android.content.Context
import android.util.Log
import knf.kuma.commons.JsExtractor
import knf.kuma.commons.roundedString
import knf.kuma.commons.toArray
import knf.kuma.database.CacheDB
import knf.kuma.pojos.av1.Chapter
import knf.kuma.pojos.av1.DirectoryAV1
import knf.kuma.retrofit.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Busca el siguiente episodio y su video sin mostrar diálogos (para el autoplay del reproductor).
 * Replica la obtención de servidores de FileActions.execute.
 */
object NextEpisodeResolver {

    class Next(val anime: DirectoryAV1, val chapter: Chapter)

    class Resolved(val url: String, val headers: Array<String>?, val serverName: String)

    /** Siguiente episodio (el menor número mayor al actual). Refresca desde la web si el cacheado no lo tiene. */
    suspend fun findNext(slug: String, number: Double): Next? = withContext(Dispatchers.IO) {
        fun DirectoryAV1.next() = chapters.filter { it.number > number }.minByOrNull { it.number }?.let { Next(this, it) }
        CacheDB.INSTANCE.directoryDAO().findBySlug(slug)?.next()
            ?: runCatching { Repository.getDirectory(slug) }.getOrNull()?.next()
    }

    /**
     * Prueba primero [preferredServer] y después el resto en el orden de FileActions hasta encontrar un video
     * reproducible. [lang] 0 = subtitulado, 1 = doblado (si no hay doblado se usa subtitulado).
     */
    suspend fun resolve(context: Context, next: Next, preferredServer: String?, lang: Int): Resolved? = withContext(Dispatchers.IO) {
        val url = "https://animeav1.com/media/${next.anime.slug}/${next.chapter.number.roundedString()}"
        val servers = try {
            loadServers(context, url, lang)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
        val ordered = servers.sortedBy { if (it.name == preferredServer) 0 else 1 }
        for (server in ordered) {
            try {
                val video = server.verified ?: continue
                val option = video.options.firstOrNull { !it.needTabs && !it.url.isNullOrBlank() } ?: continue
                Log.i("Autoplay", "Episodio ${next.chapter.number}: usando ${server.name}")
                return@withContext Resolved(option.url!!, option.headers?.headers?.toArray(), server.name)
            } catch (e: Exception) {
                Log.w("Autoplay", "Falló ${server.name}: ${e.message}")
            }
        }
        null
    }

    private suspend fun loadServers(context: Context, url: String, lang: Int): List<Server> {
        val response = JsExtractor.processLinkMultiple(url, listOf("embeds", "downloads"))
        val sub = mutableListOf<Server>()
        val dub = mutableListOf<Server>()
        response.forEach { (_, array) ->
            val obj = array?.optJSONObject(0) ?: return@forEach
            for ((key, target) in listOf("SUB" to sub, "DUB" to dub)) {
                val list = obj.optJSONArray(key) ?: continue
                for (i in 0 until list.length()) {
                    val link = list.getJSONObject(i).getString("url")
                    fun key(l: String) = l.substringBeforeLast("?").substringAfterLast("/")
                    if (target.any { key(it.baseLink) == key(link) }) continue
                    // Los servidores web (que abren una página) no sirven para el autoplay.
                    Server.check(context, link)?.takeIf { it.canStream }?.let { target.add(it) }
                }
            }
        }
        val chosen = if (lang == 1 && dub.isNotEmpty()) dub else sub
        return chosen.sortedWith(compareBy({ it.name.contains("(WEB)") }, { it.name }))
    }
}
