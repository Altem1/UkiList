package knf.kuma.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.ui.AniListOpener
import knf.kuma.commons.DesignUtils
import knf.kuma.commons.EAHelper
import knf.kuma.commons.PrefsUtil
import knf.kuma.commons.load
import knf.kuma.commons.setSurfaceBars
import knf.kuma.commons.toast
import knf.kuma.commons.verifyManager
import knf.kuma.custom.GenericActivity
import knf.kuma.custom.VariantLinearLayoutManager
import knf.kuma.databinding.RecyclerGenreMaterialBinding
import knf.kuma.directory.DirectoryAV1PageAdapter
import knf.kuma.pojos.av1.SearchDataSource
import knf.kuma.pojos.av1.SearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Búsqueda avanzada del directorio: tipo, estado, año, géneros y orden van directo al catálogo de animeav1.
 * animeav1 no filtra por temporada, así que con temporada se consulta AniList y cada anime se abre con [AniListOpener].
 */
class AdvancedSearchActivity : GenericActivity() {

    private class Option(val label: String, val value: String?)

    private val types = listOf(Option("Todos", null), Option("Anime", "tv-anime"), Option("OVA / Especial", "ova"), Option("Película", "pelicula"))
    private val statuses = listOf(Option("Todos", null), Option("En emisión", "emision"), Option("Finalizado", "finalizado"), Option("Próximamente", "proximamente"))
    private val seasons = listOf(Option("Ninguna", null), Option("Invierno", "WINTER"), Option("Primavera", "SPRING"), Option("Verano", "SUMMER"), Option("Otoño", "FALL"))
    private val orders = listOf(Option("Nombre", "title"), Option("Popularidad", "popular"), Option("Últimos añadidos", "latest_released"))

    /** Géneros de animeav1 (slug) con su equivalente en AniList: género o, para demografías, etiqueta. */
    private class GenreOption(val label: String, val slug: String, val aniGenre: String? = null, val aniTag: String? = null)

    private val genres = listOf(
        GenreOption("Acción", "accion", "Action"), GenreOption("Aventura", "aventura", "Adventure"),
        GenreOption("Ciencia Ficción", "ciencia-ficcion", "Sci-Fi"), GenreOption("Comedia", "comedia", "Comedy"),
        GenreOption("Deportes", "deportes", "Sports"), GenreOption("Drama", "drama", "Drama"),
        GenreOption("Fantasía", "fantasia", "Fantasy"), GenreOption("Misterio", "misterio", "Mystery"),
        GenreOption("Recuentos de la Vida", "recuentos-de-la-vida", "Slice of Life"), GenreOption("Romance", "romance", "Romance"),
        GenreOption("Sobrenatural", "sobrenatural", "Supernatural"), GenreOption("Suspenso", "suspenso", "Thriller"),
        GenreOption("Terror", "terror", "Horror"), GenreOption("Seinen", "seinen", aniTag = "Seinen"),
        GenreOption("Shoujo", "shoujo", aniTag = "Shoujo"), GenreOption("Shounen", "shounen", aniTag = "Shounen")
    )

    // Filtros elegidos (índices en las listas de arriba)
    private var type = 0
    private var status = 0
    private var season = 0
    private var order = 0
    private var year: Int? = null
    private val selectedGenres = mutableSetOf<Int>()

    private val binding by lazy { RecyclerGenreMaterialBinding.inflate(layoutInflater) }
    private val catalogAdapter: DirectoryAV1PageAdapter by lazy { DirectoryAV1PageAdapter(this) }
    private val seasonAdapter = SeasonAdapter()
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getTheme())
        super.onCreate(savedInstanceState)
        setSurfaceBars()
        setContentView(binding.root)
        binding.toolbar.title = "Búsqueda avanzada"
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(false)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (PrefsUtil.layType == "0") {
            binding.recycler.layoutManager = VariantLinearLayoutManager(this)
        }
        binding.recycler.verifyManager()
        showFilters()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Filtros").setIcon(R.drawable.ic_filter).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) showFilters()
        return super.onOptionsItemSelected(item)
    }

    private fun showFilters() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun header(text: String) = root.addView(TextView(this).apply {
            this.text = text
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })

        fun singleGroup(title: String, options: List<Option>, selected: Int, onPick: (Int) -> Unit) {
            header(title)
            val group = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
            options.forEachIndexed { i, opt ->
                group.addView(Chip(this).apply {
                    text = opt.label
                    isCheckable = true
                    isChecked = i == selected
                    setOnCheckedChangeListener { _, checked -> if (checked) onPick(i) }
                })
            }
            root.addView(group)
        }

        var dType = type
        var dStatus = status
        var dSeason = season
        var dOrder = order
        val dGenres = selectedGenres.toMutableSet()
        singleGroup("Tipo", types, dType) { dType = it }
        singleGroup("Estado", statuses, dStatus) { dStatus = it }
        singleGroup("Temporada", seasons, dSeason) { dSeason = it }
        header("Año")
        val yearInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Cualquiera (con temporada: ${Calendar.getInstance().get(Calendar.YEAR)})"
            setText(year?.toString() ?: "")
        }
        root.addView(yearInput)
        header("Géneros")
        val genreGroup = ChipGroup(this)
        genres.forEachIndexed { i, g ->
            genreGroup.addView(Chip(this).apply {
                text = g.label
                isCheckable = true
                isChecked = i in selectedGenres
                setOnCheckedChangeListener { _, checked -> if (checked) dGenres += i else dGenres -= i }
            })
        }
        root.addView(genreGroup)
        singleGroup("Orden (sin temporada)", orders, dOrder) { dOrder = it }

        MaterialDialog(this).show {
            title(text = "Filtros")
            customView(view = root, scrollable = true)
            positiveButton(text = "Buscar") {
                type = dType; status = dStatus; season = dSeason; order = dOrder
                year = yearInput.text.toString().toIntOrNull()?.takeIf { it in 1900..2100 }
                selectedGenres.clear(); selectedGenres += dGenres
                search()
            }
            negativeButton(text = "Cancelar") { if (searchJob == null) finish() }
        }
    }

    private fun search() {
        searchJob?.cancel()
        binding.toolbar.subtitle = describeFilters()
        searchJob = if (season == 0) searchCatalog() else searchSeason()
    }

    private fun describeFilters(): String = listOfNotNull(
        types[type].takeIf { type != 0 }?.label,
        statuses[status].takeIf { status != 0 }?.label,
        seasons[season].takeIf { season != 0 }?.label,
        year?.toString() ?: if (season != 0) Calendar.getInstance().get(Calendar.YEAR).toString() else null,
        selectedGenres.sorted().joinToString { genres[it].label }.takeIf { it.isNotEmpty() }
    ).joinToString(" · ").ifEmpty { "Sin filtros" }

    private fun searchCatalog() = lifecycleScope.launch {
        binding.recycler.adapter = catalogAdapter
        val state = SearchState(
            genres = selectedGenres.sorted().map { genres[it].slug },
            categories = types[type].value?.let { if (it == "ova") listOf("ova", "especial") else listOf(it) },
            status = statuses[status].value,
            minYear = year,
            maxYear = year,
            order = orders[order].value ?: "title"
        )
        catalogAdapter.submitData(PagingData.empty())
        Pager(PagingConfig(20, enablePlaceholders = false)) { SearchDataSource(state) }.flow.collectLatest {
            catalogAdapter.submitData(it)
        }
    }

    private fun searchSeason() = lifecycleScope.launch {
        binding.recycler.adapter = seasonAdapter
        seasonAdapter.submit(emptyList())
        val formats = when (types[type].value) {
            "tv-anime" -> listOf("TV", "TV_SHORT")
            "ova" -> listOf("OVA", "ONA", "SPECIAL")
            "pelicula" -> listOf("MOVIE")
            else -> null
        }
        val aniStatus = when (statuses[status].value) {
            "emision" -> "RELEASING"
            "finalizado" -> "FINISHED"
            "proximamente" -> "NOT_YET_RELEASED"
            else -> null
        }
        val chosen = selectedGenres.map { genres[it] }
        val seasonYear = year ?: Calendar.getInstance().get(Calendar.YEAR)
        val result = mutableListOf<AniListApi.Media>()
        var page = 1
        while (page <= 4) {
            val response = withContext(Dispatchers.IO) {
                AniListApi.seasonMedia(seasons[season].value!!, seasonYear, page, formats, aniStatus,
                    chosen.mapNotNull { it.aniGenre }, chosen.mapNotNull { it.aniTag })
            }
            if (response == null) {
                if (result.isEmpty()) "No se pudo conectar con AniList".toast()
                break
            }
            val (items, hasNext) = response
            result += items
            seasonAdapter.submit(result.toList())
            if (!hasNext) break
            page++
        }
        if (result.isEmpty()) "Sin resultados para esa temporada".toast()
    }

    /** Resultados de AniList con la misma tarjeta del directorio. */
    private inner class SeasonAdapter : RecyclerView.Adapter<DirectoryAV1PageAdapter.ItemHolder>() {
        private var items: List<AniListApi.Media> = emptyList()

        fun submit(list: List<AniListApi.Media>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DirectoryAV1PageAdapter.ItemHolder {
            val layout = if (PrefsUtil.layType == "0") {
                if (DesignUtils.isFlat) R.layout.item_dir_material else R.layout.item_dir
            } else {
                if (DesignUtils.isFlat) R.layout.item_dir_grid_material else R.layout.item_dir_grid
            }
            return DirectoryAV1PageAdapter.ItemHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: DirectoryAV1PageAdapter.ItemHolder, position: Int) {
            val media = items[position]
            holder.imageView.load(media.cover)
            holder.progressView.isVisible = false
            holder.textView.text = media.titles.firstOrNull()
            holder.cardView.setOnClickListener { AniListOpener.open(this@AdvancedSearchActivity, media) }
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, AdvancedSearchActivity::class.java))
    }
}
