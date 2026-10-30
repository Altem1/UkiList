package knf.kuma.animeinfo.hub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayoutMediator
import knf.kuma.R
import knf.kuma.achievements.AchievementManager
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.AniListAuth
import knf.kuma.anilist.AniListFavouriteWorker
import knf.kuma.anilist.AniListHub
import knf.kuma.anilist.AniListMatcher
import knf.kuma.anilist.AniListStatusWorker
import knf.kuma.anilist.AniListSync
import knf.kuma.animeinfo.AnimeViewModel
import knf.kuma.animeinfo.fragments.ChaptersFragmentMaterial
import knf.kuma.backup.firestore.syncData
import knf.kuma.cast.CastMedia
import knf.kuma.commons.CastUtil
import knf.kuma.commons.EAHelper
import knf.kuma.commons.roundedString
import knf.kuma.commons.toast
import knf.kuma.custom.GenericActivity
import knf.kuma.database.CacheDB
import knf.kuma.databinding.ActivityAnimeHubBinding
import knf.kuma.pojos.av1.Chapter
import knf.kuma.pojos.av1.DirectoryAV1
import knf.kuma.recommended.RankType
import knf.kuma.recommended.RecommendHelper
import knf.kuma.search.GenreActivityMaterial
import knf.kuma.videoservers.FileActions
import knf.kuma.videoservers.ServersFactory
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ficha del anime estilo hub (se activa en Ajustes > Personalización). Recibe el mismo Intent que ActivityAnimeMaterial.
 * Los datos del catálogo salen de [AnimeViewModel]; los extra (portada, puntuación, personajes, recomendaciones,
 * títulos de episodios) de AniList/Kitsu vía [AniListHub].
 */
class AnimeHubActivity : GenericActivity() {

    private val binding by lazy { ActivityAnimeHubBinding.inflate(layoutInflater) }
    private val viewModel: AnimeViewModel by viewModels()
    private val favDao = CacheDB.INSTANCE.favoriteAV1DAO()

    /** Detalles de AniList; null mientras cargan o si no hubo coincidencia. */
    val details = MutableStateFlow<AniListHub.Details?>(null)
    /** true cuando terminó la búsqueda en AniList (con o sin resultado). */
    val detailsDone = MutableStateFlow(false)
    /** Título y sinopsis traducidos por número de episodio (los usa la pestaña Episodios). */
    val episodeMeta = MutableStateFlow<Map<Int, AniListHub.EpisodeMeta>>(emptyMap())
    /** Openings/endings (null mientras cargan). */
    val themes = MutableStateFlow<List<AniListHub.Theme>?>(null)

    private val bannerAdapter = BannerAdapter()

    var anime: DirectoryAV1? = null
        private set
    /** true si AniList no encontró este anime automáticamente (sin mapeo). */
    private var lookupNotFound = false
    /** Episodios que no se pudieron subir a AniList. */
    private var failedEpisodes: List<knf.kuma.anilist.AniListScrobbleState> = emptyList()
    private var target: Chapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getThemeNA())
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> HubDetailsFragment()
                1 -> ChaptersFragmentMaterial()
                else -> HubRecommendFragment()
            }
        }
        binding.pager.offscreenPageLimit = 2
        binding.bannerPager.adapter = bannerAdapter
        binding.bannerPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // En el tráiler se quita el oscurecido y el título para que se vea completo; al volver se detiene.
                binding.bannerShade.animate().alpha(if (position == 1) 0f else 1f).start()
                binding.title.animate().alpha(if (position == 1) 0f else 1f).start()
                bannerAdapter.setTrailerActive(position == 1)
            }
        })
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> "Detalles"
                1 -> "Episodios"
                else -> "Podría gustarte"
            }
        }.attach()
        binding.pager.setCurrentItem(1, false)

        if (intent.getBooleanExtra("aid_only", false))
            viewModel.init(intent.getStringExtra("aid")?.toIntOrNull() ?: -1)
        else
            viewModel.init(intent.dataString, intent.getBooleanExtra("persist", true))

        lifecycleScope.launch {
            viewModel.infoFlow.drop(1).collectLatest { info ->
                binding.loading.isVisible = false
                if (info == null) {
                    "Error al cargar información del anime".toast()
                    finish()
                } else bind(info)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_anime_info, menu)
        menu.findItem(R.id.action_share)?.isVisible = false
        CastUtil.registerActivity(this, menu, R.id.castMenu)
        return true
    }

    private fun bind(info: DirectoryAV1) {
        val first = anime == null
        anime = info
        binding.title.text = info.name
        binding.description.text = info.description
        binding.description.setOnClickListener {
            binding.description.maxLines = if (binding.description.maxLines == 4) Int.MAX_VALUE else 4
        }
        binding.genres.removeAllViews()
        info.genres.forEach { genre ->
            binding.genres.addView(Chip(this).apply {
                text = genre.name
                setOnClickListener { GenreActivityMaterial.open(this@AnimeHubActivity, genre) }
            })
        }
        binding.info.text = listOf(info.typeText, "${info.chapters.size} episodios").joinToString(" · ")
        if (details.value?.banner == null) bannerAdapter.setImage(info.imageUrl)
        RecommendHelper.registerAll(info.genres, RankType.CHECK)
        bindActions()
        binding.play.setOnClickListener { target?.let { play(it) } }
        binding.download.setOnClickListener { target?.let { download(it) } }
        if (first) {
            // El episodio a reproducir se recalcula cada vez que cambia el historial.
            lifecycleScope.launch {
                CacheDB.INSTANCE.recordAV1DAO().lastSeenFlow(1).collectLatest { updateTarget() }
            }
            loadAniList(info)
            CacheDB.INSTANCE.aniListScrobbleDAO().failedForSlugLive(info.slug).observe(this) {
                failedEpisodes = it
                bindActions()
            }
        }
    }

    /** Siguiente episodio sin ver después del último visto; si ya vio todo (o nada), el primero. */
    private suspend fun updateTarget() {
        val info = anime ?: return
        val sorted = info.chapters.sortedBy { it.number }
        if (sorted.isEmpty()) {
            binding.play.isEnabled = false
            binding.download.isVisible = false
            return
        }
        val (chapter, label) = withContext(Dispatchers.IO) {
            val records = CacheDB.INSTANCE.recordAV1DAO()
            val last = records.getLastByAid(info.aid)
            when {
                last == null -> sorted.first() to "Reproducir"
                else -> sorted.firstOrNull { it.number > last.number && !records.chapterIsSeen(info.aid, it.number) }
                    ?.let { it to "Continuar" }
                    ?: sorted.firstOrNull { !records.chapterIsSeen(info.aid, it.number) }?.let { it to "Continuar" }
                    ?: (sorted.first() to "Ver de nuevo")
            }
        }
        target = chapter
        val n = chapter.number.roundedString()
        binding.play.text = "$label · Episodio $n"
        binding.download.text = "Descargar episodio $n"
        binding.download.isVisible = true
    }

    private fun play(chapter: Chapter) {
        val info = anime ?: return
        val records = CacheDB.INSTANCE.recordAV1DAO()
        if (chapter.fileWrapper(info).exist) {
            lifecycleScope.launch(Dispatchers.IO) { records.addChapter(chapter.asRecord(info)) }
            ServersFactory.startPlay(this, chapter.episodeName(info), chapter.fileWrapper(info).name())
            return
        }
        FileActions.stream(this, this, info, chapter, binding.root) { state, extra ->
            if (state == FileActions.CallbackState.START_STREAM || state == FileActions.CallbackState.START_CAST) {
                if (state == FileActions.CallbackState.START_CAST)
                    CastUtil.get().play(binding.root, CastMedia.create(info, chapter, extra as? String))
                lifecycleScope.launch(Dispatchers.IO) { records.addChapter(chapter.asRecord(info)) }
            }
        }
    }

    private fun download(chapter: Chapter) {
        val info = anime ?: return
        FileActions.download(this, this, info, chapter, binding.root) { state, _ ->
            if (state == FileActions.CallbackState.START_DOWNLOAD) "Descargando episodio ${chapter.number.roundedString()}".toast()
        }
    }

    // ---------- Fila de botones: Favorito, Viendo, Volver a ver, Compartir ----------

    private fun bindActions() {
        val info = anime ?: return
        lifecycleScope.launch {
            val (isFav, state) = withContext(Dispatchers.IO) {
                favDao.isFav(info.aid) to (CacheDB.INSTANCE.organizerDAO().getByAid(info.aid)?.organizer?.state ?: 0)
            }
            binding.actionsRow.removeAllViews()
            addAction(if (isFav) R.drawable.ic_heart_full_menu else R.drawable.ic_heart, if (isFav) "Favorito" else "Favoritos", isFav) { toggleFav(info, !isFav) }
            addAction(if (state == 1) R.drawable.ic_check else R.drawable.ic_add_list, if (state == 1) "Viendo" else "Agregar a viendo", state == 1) { setWatching(info, state != 1) }
            if (AniListAuth.isLoggedIn && (lookupNotFound || failedEpisodes.isNotEmpty()))
                addAction(R.drawable.ic_anilist_pending, "Sin sincronizar", false, warning = true) { showSyncDialog(info) }
            if (state == 3) addAction(R.drawable.ic_hub_replay, "Volver a ver", false) { rewatch(info) }
            addAction(R.drawable.ic_share, "Compartir", false) { share(info) }
        }
    }

    private fun addAction(icon: Int, label: String, active: Boolean, warning: Boolean = false, onClick: () -> Unit) {
        val color = when {
            warning -> 0xFFFFB74D.toInt()
            active -> ContextCompat.getColor(this, EAHelper.getThemeColor())
            else -> ContextCompat.getColor(this, R.color.textPrimary)
        }
        val dp = resources.displayMetrics.density
        binding.actionsRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.background_ripple)
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(color)
            }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()))
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toggleFav(info: DirectoryAV1, favourite: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val fav = info.asFavorite()
                if (favourite) favDao.addFav(fav) else favDao.delete(fav)
            }
            RecommendHelper.registerAll(info.genres, if (favourite) RankType.FAV else RankType.UNFAV)
            if (favourite) AchievementManager.onFavAdded(info.genres)
            AniListFavouriteWorker.anime(applicationContext, info.aid, info.slug, info.name, favourite)
            syncData { favs() }
            (if (favourite) "Añadido a favoritos" else "Removido de favoritos").toast()
            bindActions()
        }
    }

    private fun setWatching(info: DirectoryAV1, watching: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = CacheDB.INSTANCE.organizerDAO()
                if (watching) {
                    dao.add(info.asOrganizer(1))
                    AniListStatusWorker.enqueue(applicationContext, info.aid, info.slug, info.name, 1)
                } else dao.remove(info.asOrganizer(0))
            }
            syncData { seeing() }
            (if (watching) "Agregado a Viendo" else "Quitado de Viendo").toast()
            bindActions()
        }
    }

    /** Lo pasa a Viendo; en AniList queda como "Volviendo a ver" con una repetición más. Reproducir empieza en el 1. */
    private fun rewatch(info: DirectoryAV1) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                CacheDB.INSTANCE.organizerDAO().add(info.asOrganizer(1))
                if (AniListAuth.isLoggedIn && AniListSync.isScrobbleEnabled) {
                    (details.value?.mediaId ?: (AniListMatcher.findAniListId(info.aid, info.slug, info.name) as? AniListMatcher.Lookup.Found)?.mediaId)?.let { id ->
                        val entry = AniListApi.listEntry(id)
                        AniListApi.saveProgress(id, 0, "REPEATING", (entry?.repeat ?: 0) + 1)
                    }
                }
            }
            syncData { seeing() }
            info.chapters.minByOrNull { it.number }?.let { first ->
                target = first
                binding.play.text = "Volver a ver · Episodio ${first.number.roundedString()}"
            }
            "¡A verlo otra vez!".toast()
            bindActions()
        }
    }

    /** El enlace abre una página del sitio que manda a la app (ukilist://anime/slug) o a la descarga si no está instalada. */
    private fun share(info: DirectoryAV1) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "${info.name}\nMíralo en UkiList: https://ukilist.altem.site/a/?s=${info.slug}"), "Compartir"))
            AchievementManager.onShare()
        } catch (e: ActivityNotFoundException) {
            "No se encontraron aplicaciones para enviar".toast()
        }
    }

    override fun onPause() {
        super.onPause()
        bannerAdapter.setTrailerActive(false)
    }

    override fun onResume() {
        super.onResume()
        if (binding.bannerPager.currentItem == 1) bannerAdapter.setTrailerActive(true)
    }

    override fun onDestroy() {
        bannerAdapter.release()
        super.onDestroy()
    }

    /** Portada + tráiler de YouTube (WebView con el reproductor embebido, empieza en silencio). */
    private inner class BannerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var image: String? = null
        private var trailer: String? = null
        private var webView: WebView? = null
        private var active = false

        fun setImage(url: String) {
            image = url
            notifyItemChanged(0)
        }

        fun setTrailer(id: String) {
            if (trailer != null) return
            trailer = id
            notifyItemInserted(1)
        }

        fun setTrailerActive(value: Boolean) {
            active = value
            val web = webView ?: return
            val id = trailer ?: return
            if (value) {
                web.onResume()
                web.loadDataWithBaseURL("https://ukilist.altem.site", trailerHtml(id), "text/html", "utf-8", null)
            } else {
                web.loadUrl("about:blank")
                web.onPause()
            }
        }

        fun release() {
            webView?.destroy()
            webView = null
        }

        private fun trailerHtml(id: String) = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;background:#000;height:100vh;overflow:hidden">
            <iframe style="border:0;width:100%;height:100%" src="https://www.youtube.com/embed/$id?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1"
              allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe></body></html>
        """.trimIndent()

        override fun getItemCount() = if (trailer != null) 2 else 1
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == 0) ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Portada"
            } else WebView(parent.context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webView = this
            }
            view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) image?.let { Glide.with(holder.itemView).load(it).into(holder.itemView as ImageView) }
            else if (active) setTrailerActive(true)
        }
    }

    // ---------- AniList ----------

    // ---------- Sin sincronizar: elegir el anime de AniList a mano o reintentar ----------

    private fun showSyncDialog(info: DirectoryAV1) {
        val reasons = listOfNotNull(
            "No pudimos encontrar este anime en AniList automáticamente.".takeIf { lookupNotFound },
            "${failedEpisodes.size} episodio(s) no se pudieron subir a tu lista.".takeIf { failedEpisodes.isNotEmpty() }
        )
        MaterialDialog(this).show {
            title(text = "Sin sincronizar con AniList")
            message(text = reasons.joinToString("\n\n") + "\n\nPuedes elegir el anime correcto de AniList y se subirá tu progreso.")
            positiveButton(text = "Elegir en AniList") { pickManually(info) }
            if (!lookupNotFound && failedEpisodes.isNotEmpty())
                neutralButton(text = "Reintentar") { retryFailed(info, 0) }
            negativeButton(text = "Cerrar")
        }
    }

    private fun pickManually(info: DirectoryAV1) {
        MaterialDialog(this).show {
            title(text = "Buscar en AniList")
            input(prefill = info.name) { _, text -> searchManual(info, text.toString()) }
            positiveButton(text = "Buscar")
            negativeButton(text = "Cancelar")
        }
    }

    private fun searchManual(info: DirectoryAV1, query: String) {
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { runCatching { AniListApi.search(query) }.getOrNull() }
            when {
                results == null -> "No se pudo conectar con AniList".toast()
                results.isEmpty() -> "Sin resultados para \"$query\"".toast()
                else -> MaterialDialog(this@AnimeHubActivity).show {
                    title(text = "¿Cuál es?")
                    listItems(items = results.map { m -> listOfNotNull(m.titles.firstOrNull(), m.format, m.year?.toString()).joinToString(" · ") }) { _, index, _ ->
                        val media = results[index]
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { AniListMatcher.saveManual(info.aid, info.slug, media.id) }
                            lookupNotFound = false
                            retryFailed(info, media.id)
                            "Vinculado con AniList".toast()
                            bindActions()
                            loadAniList(info)
                        }
                    }
                }
            }
        }
    }

    /** Reintenta los episodios fallidos; si no había, sube el último visto y el estado de la lista. */
    private fun retryFailed(info: DirectoryAV1, mediaId: Int) {
        lifecycleScope.launch {
            if (failedEpisodes.isNotEmpty()) {
                failedEpisodes.forEach { knf.kuma.anilist.AniListScrobbleWorker.enqueue(this@AnimeHubActivity, info.aid, info.slug, info.name, it.episode, mediaId) }
            } else {
                val (last, state) = withContext(Dispatchers.IO) {
                    CacheDB.INSTANCE.recordAV1DAO().getLastByAid(info.aid) to (CacheDB.INSTANCE.organizerDAO().getByAid(info.aid)?.organizer?.state ?: 0)
                }
                last?.let { knf.kuma.anilist.AniListScrobbleWorker.enqueue(this@AnimeHubActivity, info.aid, info.slug, info.name, kotlin.math.floor(it.number).toInt(), mediaId) }
                if (state > 0) AniListStatusWorker.enqueue(applicationContext, info.aid, info.slug, info.name, state)
            }
            "Sincronizando con AniList…".toast()
        }
    }

    private fun loadAniList(info: DirectoryAV1) {
        lifecycleScope.launch {
            val lookup = withContext(Dispatchers.IO) { runCatching { AniListMatcher.findAniListId(info.aid, info.slug, info.name) }.getOrNull() }
            lookupNotFound = lookup is AniListMatcher.Lookup.NotFound
            if (lookupNotFound) bindActions()
            val data = (lookup as? AniListMatcher.Lookup.Found)?.mediaId?.let { id ->
                withContext(Dispatchers.IO) { runCatching { AniListHub.details(id) }.getOrNull() }
            }
            details.value = data
            detailsDone.value = true
            if (data == null) return@launch
            (data.banner ?: data.cover)?.let { bannerAdapter.setImage(it) }
            data.trailerYoutube?.let { id ->
                bannerAdapter.setTrailer(id)
                // A los 5 s se desliza solo al tráiler (si el usuario no se movió ya).
                launch {
                    kotlinx.coroutines.delay(5000)
                    if (binding.bannerPager.currentItem == 0) binding.bannerPager.setCurrentItem(1, true)
                }
            }
            launch { themes.value = withContext(Dispatchers.IO) { runCatching { AniListHub.themes(data.mediaId) }.getOrDefault(emptyList()) } }
            val season = AniListHub.seasonName(data.season)
            val status = AniListHub.statusName(data.status)
            binding.info.text = listOfNotNull(
                listOfNotNull(season, data.seasonYear?.toString()).joinToString(" ").ifEmpty { null },
                status, info.typeText, "${info.chapters.size} episodios"
            ).joinToString(" · ")
            binding.basicInfo.text = listOfNotNull(
                season?.let { "Temporada: $it" },
                data.seasonYear?.let { "Año: $it" },
                status?.let { "Estado: $it" },
                data.episodes?.let { "Episodios: $it" + (data.duration?.let { d -> " ($d min c/u)" } ?: "") },
                data.studios.takeIf { it.isNotEmpty() }?.let { "Estudio: ${it.joinToString()}" }
            ).joinToString("\n")
            episodeMeta.value = withContext(Dispatchers.IO) { runCatching { AniListHub.episodes(data.mediaId, data.episodeTitles) }.getOrDefault(emptyMap()) }
        }
    }
}
