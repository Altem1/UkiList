package knf.kuma.anilist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.AniListFavouriteWorker
import knf.kuma.commons.toast

/** Personajes del anime en AniList: tocar uno lo agrega o quita de los favoritos del usuario. */
class AniListCharactersAdapter(private val characters: List<AniListApi.Character>) : RecyclerView.Adapter<AniListCharactersAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val fav: ImageView = view.findViewById(R.id.fav)
        val name: TextView = view.findViewById(R.id.name)
        val role: TextView = view.findViewById(R.id.role)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_anilist_character, parent, false))

    override fun getItemCount() = characters.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val character = characters[position]
        Glide.with(holder.image).load(character.image).circleCrop().into(holder.image)
        holder.name.text = character.name
        holder.role.text = when (character.role) {
            "MAIN" -> "Principal"
            "SUPPORTING" -> "Secundario"
            else -> "Extra"
        }
        holder.fav.isVisible = character.isFavourite
        holder.itemView.setOnClickListener {
            character.isFavourite = !character.isFavourite
            notifyItemChanged(holder.bindingAdapterPosition)
            AniListFavouriteWorker.character(it.context.applicationContext, character.id, character.isFavourite)
            (if (character.isFavourite) "${character.name} añadido a favoritos" else "${character.name} quitado de favoritos").toast()
        }
    }
}
