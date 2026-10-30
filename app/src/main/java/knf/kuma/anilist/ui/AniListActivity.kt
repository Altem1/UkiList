package knf.kuma.anilist.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.anilist.AniListAuth
import knf.kuma.commons.EAHelper
import knf.kuma.custom.GenericActivity
import knf.kuma.database.CacheDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sección AniList tipo AniHyou como pantalla propia (menú lateral). El contenido vive en [AniListListFragment]. */
class AniListActivity : GenericActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getTheme())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anilist_host)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val single = intent.getBooleanExtra(EXTRA_SINGLE, false)
        val tab = intent.getIntExtra(EXTRA_TAB, 1)
        title = when {
            !single -> AniListAuth.userName.value?.let { "AniList · $it" } ?: "AniList"
            tab == listTabCount -> getString(R.string.anilist_tab_stats)
            else -> getString(R.string.anilist_tab_profile)
        }
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, AniListListFragment.create(tab, single))
                .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Perfil")
        menu.add(0, 2, 0, "Estadísticas")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val user = AniListAuth.userName.value ?: ""
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> AniListDrawer.openUrl(this, "https://anilist.co/user/$user/")
            2 -> AniListDrawer.openUrl(this, "https://anilist.co/user/$user/stats/anime/overview")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        internal const val EXTRA_TAB = "tab"
        const val TAB_HISTORY = 0
        const val TAB_PLANNING = 2

        internal const val EXTRA_SINGLE = "single"
        internal const val listTabCount = 7 // tamaño de las pestañas de listas

        fun open(context: Context, tab: Int = 1) {
            context.startActivity(Intent(context, AniListActivity::class.java).putExtra(EXTRA_TAB, tab))
        }

        /** [stats] true abre Estadísticas, false abre Perfil. */
        fun openSingle(context: Context, stats: Boolean) {
            val tab = listTabCount + if (stats) 0 else 1
            context.startActivity(Intent(context, AniListActivity::class.java).putExtra(EXTRA_TAB, tab).putExtra(EXTRA_SINGLE, true))
        }
    }
}

/**
 * Historial y listas del usuario de AniList (+ Estadísticas y Perfil). Se usa en [AniListActivity] y como
 * pestaña de la pantalla de perfil. Con "single" se oculta la barra de pestañas y muestra solo una sección.
 */
class AniListListFragment : Fragment() {

    private val tabs = listOf(
        "Historial" to null, "Viendo" to "CURRENT", "Pendientes" to "PLANNING",
        "Completado" to "COMPLETED", "En pausa" to "PAUSED", "Abandonado" to "DROPPED", "Repitiendo" to "REPEATING"
    )
    private var all: List<AniListApi.ListItem> = emptyList()
    private val adapter = Adapter()
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var statsScroll: View
    private lateinit var renderer: AniListStatsRenderer
    private var profile: AniListApi.UserProfile? = null
    private var profileLoading = false
    private val statsTab get() = tabs.size
    private val host get() = requireActivity() as AppCompatActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.activity_anilist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // La barra de título la pone quien contiene al fragmento.
        view.findViewById<Toolbar>(R.id.toolbar).visibility = View.GONE
        refresh = view.findViewById(R.id.refresh)
        empty = view.findViewById(R.id.empty)
        tabLayout = view.findViewById(R.id.tabs)
        view.findViewById<RecyclerView>(R.id.recycler).adapter = adapter
        statsScroll = view.findViewById(R.id.stats_scroll)
        renderer = AniListStatsRenderer(host, view.findViewById(R.id.stats_container)) { openFavourite(it) }
        tabs.forEach { tabLayout.addTab(tabLayout.newTab().setText(it.first)) }
        tabLayout.addTab(tabLayout.newTab().setText(R.string.anilist_tab_stats))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.anilist_tab_profile))
        // Estadísticas y Perfil son entradas propias del menú lateral: se abren solas, sin la barra de pestañas.
        if (arguments?.getBoolean(AniListActivity.EXTRA_SINGLE) == true) tabLayout.visibility = View.GONE
        val start = savedInstanceState?.getInt(AniListActivity.EXTRA_TAB) ?: arguments?.getInt(AniListActivity.EXTRA_TAB, 1) ?: 1
        tabLayout.getTabAt(start.coerceIn(0, tabLayout.tabCount - 1))?.select()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = filter()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        refresh.setOnRefreshListener { profile = null; load() }
        load()
        filter()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::tabLayout.isInitialized) outState.putInt(AniListActivity.EXTRA_TAB, tabLayout.selectedTabPosition.coerceAtLeast(0))
    }

    private fun load() {
        if (!AniListAuth.isLoggedIn) {
            refresh.isRefreshing = false
            empty.text = "Conecta tu cuenta de AniList en Ajustes > Sincronización"
            empty.visibility = View.VISIBLE
            return
        }
        refresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { AniListApi.userListDetailed(AniListAuth.userId) }.getOrNull() }
            refresh.isRefreshing = false
            if (list == null) {
                adapter.submit(emptyList())
                empty.text = "No se pudo cargar tu lista de AniList"
                empty.visibility = View.VISIBLE
            } else {
                all = list
                filter()
            }
        }
    }

    private fun filter() {
        val pos = tabLayout.selectedTabPosition.coerceAtLeast(0)
        if (pos >= tabs.size) {
            refresh.visibility = View.GONE
            statsScroll.visibility = View.VISIBLE
            showExtra(pos)
            return
        }
        statsScroll.visibility = View.GONE
        refresh.visibility = View.VISIBLE
        val status = tabs[pos].second
        val items = if (status == null) all.filter { it.updatedAt > 0 }.take(100) else all.filter { it.status == status }
        adapter.submit(items)
        empty.text = "Nada por aquí"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showExtra(pos: Int) {
        val p = profile
        if (p != null) {
            if (pos == statsTab) renderer.renderStats(p) else renderer.renderProfile(p)
            return
        }
        if (!AniListAuth.isLoggedIn) {
            renderer.showMessage("Conecta tu cuenta de AniList en Ajustes > Sincronización")
            return
        }
        renderer.showMessage(getString(R.string.anilist_loading))
        if (profileLoading) return
        profileLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AniListApi.userProfile(AniListAuth.userId) }.getOrNull() }
            profileLoading = false
            profile = result
            val current = tabLayout.selectedTabPosition
            if (current < tabs.size) return@launch
            if (result == null) renderer.showMessage(getString(R.string.anilist_load_error)) else showExtra(current)
        }
    }

    /** Favorito: abre la ficha en la app si hay mapeo; si no, la web de AniList. */
    private fun openFavourite(fav: AniListApi.FavouriteAnime) {
        lifecycleScope.launch {
            val mapping = withContext(Dispatchers.IO) { runCatching { CacheDB.INSTANCE.aniListMappingDAO().byAniList(fav.mediaId) }.getOrNull() }
            if (mapping == null) AniListDrawer.openUrl(host, "https://anilist.co/anime/${fav.mediaId}/")
            else knf.kuma.animeinfo.ActivityAnimeMaterial.open(host, "https://animeav1.com/media/${mapping.slug}")
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<AniListApi.ListItem> = emptyList()

        fun submit(list: List<AniListApi.ListItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.cover)
            val title: TextView = v.findViewById(R.id.title)
            val text: TextView = v.findViewById(R.id.progress_text)
            val bar: ProgressBar = v.findViewById(R.id.progress_bar)
            val openAnime: View = v.findViewById(R.id.open_anime)
            val openWeb: View = v.findViewById(R.id.open_web)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_anilist_entry, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            val total = item.episodes?.toString() ?: "?"
            val score = if (item.score > 0) " · {star} ${item.score}" else ""
            holder.text.text = holder.itemView.context.iconText("Ep. ${item.progress}/$total$score", mapOf("star" to R.drawable.ic_score_star), 12f, holder.text.currentTextColor)
            holder.bar.max = item.episodes ?: maxOf(item.progress, 1)
            holder.bar.progress = item.progress
            Glide.with(holder.cover).load(item.cover).into(holder.cover)
            holder.openWeb.setOnClickListener {
                AniListDrawer.openUrl(host, "https://anilist.co/anime/${item.mediaId}/")
            }
            val openAnime = View.OnClickListener { AniListOpener.open(host, AniListApi.Media(item.mediaId, listOf(item.title), item.episodes)) }
            holder.openAnime.setOnClickListener(openAnime)
            holder.itemView.setOnClickListener(openAnime)
        }
    }

    companion object {
        fun create(tab: Int = 1, single: Boolean = false) = AniListListFragment().apply {
            arguments = bundleOf(AniListActivity.EXTRA_TAB to tab, AniListActivity.EXTRA_SINGLE to single)
        }
    }
}
