package knf.kuma.anilist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import knf.kuma.BottomFragment
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.AniListAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestaña del corazón con sesión de AniList: en vez de repetir favoritos que ya se vieron,
 * recomienda animes parecidos a ellos que aún no están en la lista del usuario.
 */
class AniListForYouFragment : BottomFragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: TextView
    private var items: List<AniListApi.Recommendation> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_anilist_for_you, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler)
        refresh = view.findViewById(R.id.refresh)
        empty = view.findViewById(R.id.empty)
        (recycler.layoutManager as GridLayoutManager).spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) 3 else 1
        }
        recycler.adapter = Adapter()
        refresh.setOnRefreshListener { load(force = true) }
        load(force = false)
    }

    private fun load(force: Boolean) {
        if (!force && cache != null) return show(cache!!)
        refresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AniListApi.favouriteRecommendations(AniListAuth.userId) }.getOrNull() }
            refresh.isRefreshing = false
            if (result == null) {
                empty.text = "No se pudo conectar con AniList. Desliza hacia abajo para reintentar."
                empty.isVisible = items.isEmpty()
                return@launch
            }
            cache = result
            show(result)
        }
    }

    private fun show(list: List<AniListApi.Recommendation>) {
        items = list
        recycler.adapter?.notifyDataSetChanged()
        empty.text = "Marca animes con el corazón para recibir recomendaciones parecidas."
        empty.isVisible = list.isEmpty()
    }

    override fun onReselect() {
        recycler.smoothScrollToPosition(0)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == 0) {
                val view = inflater.inflate(R.layout.item_anilist_for_you_header, parent, false)
                view.findViewById<View>(R.id.my_favs).setOnClickListener { (activity as? knf.kuma.MainMaterial)?.showLocalFavorites() }
                return object : RecyclerView.ViewHolder(view) {}
            }
            val view = inflater.inflate(R.layout.item_anilist_for_you, parent, false)
            // Portada con proporción fija (como las de AniList) para que la cuadrícula quede pareja.
            val cover = view.findViewById<ImageView>(R.id.img)
            cover.layoutParams.height = ((parent.width - parent.paddingStart - parent.paddingEnd) / 3 * 1.42f).toInt().coerceAtLeast(1)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) return
            val item = items[position - 1]
            val view = holder.itemView
            Glide.with(view).load(item.cover).centerCrop().into(view.findViewById(R.id.img))
            view.findViewById<TextView>(R.id.title).text = item.title
            view.findViewById<TextView>(R.id.because).text = "Porque te gustó ${item.because}"
            view.setOnClickListener {
                (activity as? AppCompatActivity)?.let { AniListOpener.open(it, AniListApi.Media(item.mediaId, item.titles, null)) }
            }
        }
    }

    companion object {
        /** Las recomendaciones no cambian a cada rato: se guardan mientras viva la app (deslizar recarga). */
        private var cache: List<AniListApi.Recommendation>? = null
    }
}
