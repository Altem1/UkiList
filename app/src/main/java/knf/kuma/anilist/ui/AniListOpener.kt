package knf.kuma.anilist.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.AniListMatcher
import knf.kuma.commons.toast
import knf.kuma.database.CacheDB
import knf.kuma.pojos.av1.DirectoryAV1Min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Abre en la app la ficha de un anime de AniList; la relación sale del mapeo AniList ↔ catálogo. */
object AniListOpener {

    fun open(activity: AppCompatActivity, media: AniListApi.Media) {
        activity.lifecycleScope.launch {
            val mapping = withContext(Dispatchers.IO) {
                CacheDB.INSTANCE.aniListMappingDAO().byAniList(media.id)
                    ?: runCatching { AniListMatcher.findAppAnime(media) }.getOrNull()
            }
            if (mapping != null) return@launch openSlug(activity, mapping.slug)
            // Sin coincidencia exacta: se busca en el catálogo y, si hay duda, elige el usuario (y se recuerda).
            val title = media.titles.firstOrNull() ?: ""
            val candidates = withContext(Dispatchers.IO) { AniListMatcher.catalogCandidates(title) }.take(8)
            when (candidates.size) {
                0 -> {
                    activity.getString(R.string.anilist_not_in_catalog, title).toast()
                    AniListDrawer.openUrl(activity, "https://anilist.co/anime/${media.id}/")
                }
                1 -> pick(activity, media.id, candidates[0])
                else -> MaterialDialog(activity).show {
                    title(R.string.anilist_pick_anime)
                    listItems(items = candidates.map { it.name }) { _, index, _ -> pick(activity, media.id, candidates[index]) }
                }
            }
        }
    }

    private fun pick(activity: AppCompatActivity, mediaId: Int, anime: DirectoryAV1Min) {
        activity.lifecycleScope.launch(Dispatchers.IO) { AniListMatcher.saveMapping(anime.aid, anime.slug, mediaId) }
        openSlug(activity, anime.slug)
    }

    private fun openSlug(activity: AppCompatActivity, slug: String) =
        knf.kuma.animeinfo.ActivityAnimeMaterial.open(activity, "https://animeav1.com/media/$slug")
}
