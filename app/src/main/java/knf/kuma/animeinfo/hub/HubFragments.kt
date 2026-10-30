package knf.kuma.animeinfo.hub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.AniListHub
import knf.kuma.anilist.ui.AniListCharactersAdapter
import knf.kuma.anilist.ui.AniListOpener
import knf.kuma.commons.EAHelper
import knf.kuma.commons.load
import knf.kuma.database.CacheDB
import knf.kuma.directory.DirectoryAV1PageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

private val Fragment.hub get() = requireActivity() as AnimeHubActivity

private fun Fragment.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Pestaña Detalles: puntuación de AniList, director, estudio, idiomas de doblaje y personajes. */
class HubDetailsFragment : Fragment() {

    private lateinit var content: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(96))
        }
        return NestedScrollView(requireContext()).apply { addView(content) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(hub.details, hub.detailsDone, hub.themes) { d, done, themes -> Triple(d, done, themes) }
                .collectLatest { (d, done, themes) -> render(d, done, themes) }
        }
    }

    /** Fila de un opening/ending: foto del artista, nombre y artista; debajo los enlaces en horizontal. */
    private fun themeRow(theme: AniListHub.Theme) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val img = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        row.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)))
        com.bumptech.glide.Glide.with(this).load(theme.image).placeholder(R.drawable.ic_music_placeholder).circleCrop().into(img)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(TextView(ctx).apply {
            text = theme.label
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, EAHelper.getThemeColor()))
        })
        texts.addView(TextView(ctx).apply {
            text = theme.title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
        })
        if (theme.artists.isNotBlank()) texts.addView(TextView(ctx).apply {
            text = theme.artists
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        theme.links.forEach { (name, url) ->
            chips.addView(com.google.android.material.chip.Chip(ctx).apply {
                text = name
                setOnClickListener {
                    runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        content.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
    }

    private fun header(text: String) = content.addView(TextView(requireContext()).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
        setPadding(0, dp(14), 0, dp(4))
    })

    private fun line(text: String) = content.addView(TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
    })

    private fun render(d: AniListHub.Details?, done: Boolean, themes: List<AniListHub.Theme>?) {
        content.removeAllViews()
        if (d == null) {
            if (!done) content.addView(ProgressBar(requireContext()))
            else line("No se encontró este anime en AniList, así que no hay detalles extra.")
            return
        }
        val numbers = NumberFormat.getIntegerInstance()
        header("Puntuación")
        content.addView(TextView(requireContext()).apply {
            text = d.averageScore?.let { "$it%" } ?: "Sin puntuación aún"
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, EAHelper.getThemeColor()))
        })
        line("Promedio de votos de usuarios de AniList · ${numbers.format(d.popularity)} lo tienen en su lista · ${numbers.format(d.favourites)} favoritos")
        if (d.directors.isNotEmpty()) {
            header(if (d.directors.size > 1) "Directores" else "Director")
            line(d.directors.joinToString())
        }
        if (d.studios.isNotEmpty()) {
            header("Estudio")
            line(d.studios.joinToString())
        }
        header("Idiomas")
        line("Audio original: Japonés · Subtítulos: Español")
        val dubs = d.dubLanguages.filter { it != "Japonés" }
        line(if (dubs.isEmpty()) "Sin doblajes registrados" else "Doblajes: ${dubs.joinToString()}")
        if ("Español" in dubs) line("Tiene doblaje en español (puede no estar disponible en el servidor)")
        header("Openings y endings")
        when {
            themes == null -> content.addView(ProgressBar(requireContext()))
            themes.isEmpty() -> line("No se encontraron openings ni endings")
            else -> themes.forEach { themeRow(it) }
        }
        if (d.characters.isNotEmpty()) {
            header("Personajes")
            line("Toca uno para agregarlo o quitarlo de tus favoritos en AniList")
            content.addView(RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                adapter = AniListCharactersAdapter(d.characters)
                isNestedScrollingEnabled = false
                setPadding(0, dp(8), 0, 0)
            })
        }
    }
}

/** Pestaña Podría gustarte: recomendaciones de AniList quitando lo que el usuario ya terminó. */
class HubRecommendFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var message: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        message = TextView(requireContext()).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
        }
        recycler = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(context, 3)
            setPadding(dp(8), 0, dp(8), dp(96))
            clipToPadding = false
        }
        root.addView(message)
        root.addView(recycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(hub.details, hub.detailsDone) { d, done -> d to done }.collectLatest { (d, done) ->
                if (d == null) {
                    message.text = if (done) "Sin recomendaciones: este anime no se encontró en AniList." else "Cargando recomendaciones…"
                    message.isVisible = true
                    return@collectLatest
                }
                val list = withContext(Dispatchers.IO) { filterFinished(d.recommendations) }
                message.isVisible = list.isEmpty()
                message.text = "No hay recomendaciones nuevas para ti."
                recycler.adapter = RecommendAdapter(list)
            }
        }
    }

    /** Fuera los COMPLETED de AniList y los "Completado" locales (vía el mapeo AniList ↔ catálogo). */
    private fun filterFinished(recs: List<Pair<AniListApi.Media, String?>>): List<AniListApi.Media> {
        val mapping = CacheDB.INSTANCE.aniListMappingDAO()
        val organizer = CacheDB.INSTANCE.organizerDAO()
        return recs.filter { (media, status) ->
            if (status == "COMPLETED") return@filter false
            val aid = mapping.byAniList(media.id)?.aid ?: return@filter true
            organizer.getByAid(aid)?.organizer?.state != 3
        }.map { it.first }
    }

    private inner class RecommendAdapter(private val items: List<AniListApi.Media>) : RecyclerView.Adapter<DirectoryAV1PageAdapter.ItemHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DirectoryAV1PageAdapter.ItemHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_dir_grid_material, parent, false))

        override fun onBindViewHolder(holder: DirectoryAV1PageAdapter.ItemHolder, position: Int) {
            val media = items[position]
            holder.imageView.load(media.cover)
            holder.progressView.isVisible = false
            holder.textView.text = media.titles.firstOrNull()
            holder.cardView.setOnClickListener { AniListOpener.open(hub, media) }
        }
    }
}
