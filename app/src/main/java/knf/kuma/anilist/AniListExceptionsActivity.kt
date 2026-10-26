package knf.kuma.anilist

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import knf.kuma.R
import knf.kuma.commons.EAHelper
import knf.kuma.commons.safeShow
import knf.kuma.commons.toast
import knf.kuma.custom.GenericActivity
import knf.kuma.database.CacheDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Episodios que no se pudieron sincronizar con AniList: reintentar, elegir el anime a mano o descartar. */
class AniListExceptionsActivity : GenericActivity() {

    private val dao = CacheDB.INSTANCE.aniListScrobbleDAO()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getTheme())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anilist_exceptions)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.anilist_exceptions_title)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        val empty = findViewById<View>(R.id.empty)
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@AniListExceptionsActivity)
            adapter = this@AniListExceptionsActivity.adapter
        }
        dao.failedLive().observe(this) {
            adapter.items = it
            invalidateOptionsMenu()
            empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (adapter.items.isNotEmpty())
            menu.add(R.string.anilist_retry_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) return super.onOptionsItemSelected(item)
        adapter.items.forEach { AniListScrobbleWorker.enqueue(this, it.aid, it.slug, it.name, it.episode) }
        getString(R.string.anilist_retrying).toast()
        return true
    }

    private fun onItem(item: AniListScrobbleState) {
        MaterialDialog(this).safeShow {
            title(text = "${item.name} · Ep ${item.episode}")
            val options = listOf(
                getString(R.string.anilist_action_retry),
                getString(R.string.anilist_action_pick_manual),
                getString(R.string.anilist_action_dismiss)
            )
            listItems(items = options) { _, index, _ ->
                when (index) {
                    0 -> retry(item)
                    1 -> pickManually(item)
                    2 -> lifecycleScope.launch(Dispatchers.IO) { dao.remove(item.slug, item.episode) }
                }
            }
        }
    }

    private fun retry(item: AniListScrobbleState, mediaId: Int = 0) {
        AniListScrobbleWorker.enqueue(this, item.aid, item.slug, item.name, item.episode, mediaId)
        getString(R.string.anilist_retrying).toast()
    }

    private fun pickManually(item: AniListScrobbleState) {
        MaterialDialog(this).safeShow {
            title(res = R.string.anilist_search_title)
            input(prefill = item.name) { _, text -> search(item, text.toString()) }
            positiveButton(res = R.string.anilist_search_action)
            negativeButton(res = R.string.cancel)
        }
    }

    private fun search(item: AniListScrobbleState, query: String) {
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { AniListApi.search(query) }
            when {
                results == null -> getString(R.string.anilist_error_network).toast()
                results.isEmpty() -> getString(R.string.anilist_search_no_results, query).toast()
                else -> MaterialDialog(this@AniListExceptionsActivity).safeShow {
                    title(res = R.string.anilist_pick_which)
                    listItems(items = results.map { m -> m.titles.take(2).joinToString(" · ") }) { _, index, _ ->
                        val media = results[index]
                        lifecycleScope.launch(Dispatchers.IO) { AniListMatcher.saveManual(item.aid, item.slug, media.id) }
                        retry(item, media.id)
                    }
                }
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        var items: List<AniListScrobbleState> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val subtitle: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = getString(R.string.anilist_item_episode_format, item.name, item.episode.toString())
            holder.subtitle.text = item.reason ?: getString(R.string.anilist_error_unknown)
            holder.itemView.setOnClickListener { onItem(item) }
        }
    }

    companion object {
        /** Muestra la entrada del menú lateral solo si hay scrobbles fallidos, con el número como insignia. */
        fun bindDrawerItem(owner: LifecycleOwner, item: MenuItem) {
            val badge = item.actionView as? TextView
            badge?.apply {
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(context, EAHelper.getThemeColor()))
            }
            CacheDB.INSTANCE.aniListScrobbleDAO().failedCountLive().observe(owner) { count ->
                item.isVisible = count > 0
                badge?.text = count.toString()
            }
        }
    }
}
