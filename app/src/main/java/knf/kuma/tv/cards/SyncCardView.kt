package knf.kuma.tv.cards

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import knf.kuma.R
import knf.kuma.tv.BindableCardView
import knf.kuma.tv.sync.SyncObject
import org.jetbrains.anko.find

class SyncCardView(context: Context) : BindableCardView<SyncObject>(context) {

    override val layoutResource: Int
        get() = R.layout.item_tv_card_sync

    override val imageView: ImageView
        get() = find(R.id.img)

    override fun bind(data: SyncObject) {
        imageView.setImageResource(data.image)
        find<TextView>(R.id.title).text = data.title
    }
}
