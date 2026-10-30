package knf.kuma.home

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import knf.kuma.R
import knf.kuma.animeinfo.ActivityAnime
import knf.kuma.commons.DesignUtils
import knf.kuma.commons.bind
import knf.kuma.commons.doOnUI
import knf.kuma.commons.inflate
import knf.kuma.commons.load
import knf.kuma.commons.noCrash
import knf.kuma.commons.transform
import knf.kuma.pojos.av1.Record
import org.jetbrains.anko.sdk27.coroutines.onClick

/** Últimos animes vistos (su episodio más reciente) para retomarlos desde el inicio. */
class LastSeenAdapter(val fragment: Fragment) : UpdateableAdapter<LastSeenAdapter.LastSeenHolder>() {

    private var list: List<Record> = emptyList()

    override fun updateList(list: List<Any>) {
        this.list = list.transform()
        fragment.doOnUI { notifyDataSetChanged() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LastSeenHolder = LastSeenHolder(parent.inflate(if (DesignUtils.isFlat) R.layout.item_fav_grid_card_simple_material else R.layout.item_fav_grid_card_simple))

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: LastSeenHolder, position: Int) {
        noCrash {
            val item = list[position]
            holder.img.load(item.imageUrl())
            knf.kuma.anilist.AniListScores.bind(holder.title, item.aid, item.slug, item.name, "\n${item.chapter()}")
            holder.root.onClick { fragment.activity?.let { ActivityAnime.open(it, item, holder.img) } }
        }
    }

    class LastSeenHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: View by itemView.bind(R.id.card)
        val img: ImageView by itemView.bind(R.id.img)
        val title: TextView by itemView.bind(R.id.title)
    }
}
