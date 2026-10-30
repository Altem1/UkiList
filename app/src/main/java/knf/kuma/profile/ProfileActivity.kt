package knf.kuma.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayoutMediator
import knf.kuma.R
import knf.kuma.achievements.AchievementManager
import knf.kuma.anilist.AniListAuth
import knf.kuma.anilist.ui.AniListListFragment
import knf.kuma.AppInfoActivityMaterial
import knf.kuma.commons.EAHelper
import knf.kuma.commons.PrefsUtil
import knf.kuma.commons.setSurfaceBars
import knf.kuma.commons.showSnackbar
import knf.kuma.commons.verifyManager
import knf.kuma.custom.GenericActivity
import knf.kuma.database.CacheDB
import knf.kuma.databinding.ActivityProfileBinding
import knf.kuma.explorer.ExplorerCreator
import knf.kuma.explorer.ExplorerPagerAdapterMaterial
import knf.kuma.explorer.FragmentBase
import knf.kuma.explorer.OnFileStateChange
import knf.kuma.explorer.ThumbServer
import knf.kuma.record.RecordsAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Perfil (botón de la barra inferior entre Favoritos y Directorio): portada y foto de AniList, nombre,
 * "Acerca de" arriba a la derecha y pestañas AniList / Descargas / Historial.
 */
class ProfileActivity : GenericActivity(), OnFileStateChange {

    private val binding by lazy { ActivityProfileBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getThemeNA())
        super.onCreate(savedInstanceState)
        setSurfaceBars()
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.info.setOnClickListener { AppInfoActivityMaterial.open(this) }
        binding.settings.setOnClickListener { knf.kuma.preferences.SettingsActivity.open(this) }
        if (savedInstanceState == null) ExplorerCreator.onDestroy()

        AniListAuth.userName.observe(this) { name ->
            binding.name.text = name ?: "Invitado"
            binding.subtitle.text = if (name != null) "Cuenta de AniList conectada" else "Conecta AniList en Ajustes > Sincronización"
        }
        AniListAuth.avatarUrl.observe(this) { url ->
            Glide.with(this).load(url).placeholder(R.mipmap.ic_launcher).circleCrop().into(binding.avatar)
        }
        AniListAuth.bannerUrl.observe(this) { url ->
            if (url != null) Glide.with(this).load(url).into(binding.cover)
            else binding.cover.setImageResource(R.drawable.side_nav_bar_ukilist)
        }

        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> AniListListFragment.create(1)
                1 -> ProfileDownloadsFragment()
                else -> ProfileHistoryFragment()
            }
        }
        binding.pager.isUserInputEnabled = false // las pestañas internas (AniList, Descargas) usan el deslizamiento horizontal
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> "AniList"
                1 -> "Descargas"
                else -> "Historial"
            }
        }.attach()
        onBackPressedDispatcher.addCallback(this) {
            val downloads = supportFragmentManager.findFragmentByTag("f1") as? ProfileDownloadsFragment
            if (binding.pager.currentItem != 1 || downloads?.onBackPressed() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onChange(isFile: Boolean) {}

    override fun onDestroy() {
        super.onDestroy()
        ThumbServer.stop()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, ProfileActivity::class.java))
    }
}

/** Pestaña Descargas: el mismo explorador (archivos descargados + descargas en curso). */
class ProfileDownloadsFragment : Fragment() {
    private var adapter: ExplorerPagerAdapterMaterial? = null
    private var pager: androidx.viewpager.widget.ViewPager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile_downloads, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pager = view.findViewById<androidx.viewpager.widget.ViewPager>(R.id.pager).also { this.pager = it }
        adapter = ExplorerPagerAdapterMaterial(requireActivity(), childFragmentManager)
        pager.offscreenPageLimit = 2
        pager.adapter = adapter
        view.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabs).setupWithViewPager(pager)
    }

    /** true si la carpeta abierta consumió el "atrás" (vuelve a la lista de animes). */
    fun onBackPressed(): Boolean = (adapter?.getItem(pager?.currentItem ?: 0) as? FragmentBase)?.onBackPressed() == true
}

/** Pestaña Historial: episodios vistos (deslizar a la derecha para borrar uno). */
class ProfileHistoryFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(if (PrefsUtil.layType == "0") R.layout.recycler_records_material else R.layout.recycler_records_grid_material, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.toolbar)?.let { (it.parent as? View)?.isVisible = false }
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val progress = view.findViewById<View>(R.id.progress)
        val error = view.findViewById<View>(R.id.error)
        val adapter = RecordsAdapter(requireActivity() as androidx.appcompat.app.AppCompatActivity)
        recycler.verifyManager()
        recycler.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.RIGHT, ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.remove(viewHolder.bindingAdapterPosition)
                recycler.showSnackbar("Elemento eliminado")
            }
        }).attachToRecyclerView(recycler)
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.loadStateFlow.collectLatest {
                progress.isVisible = it.append is androidx.paging.LoadState.Loading
                if (it.append.endOfPaginationReached) error.isVisible = adapter.itemCount == 0
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            PrefsUtil.recordOrderFlow.distinctUntilChanged().flatMapLatest { order ->
                Pager(PagingConfig(20, enablePlaceholders = false)) {
                    if (order == 0) CacheDB.INSTANCE.recordAV1DAO().allPaged else CacheDB.INSTANCE.recordAV1DAO().allPagedMaxOnly
                }.flow
            }.collectLatest {
                adapter.submitData(PagingData.empty())
                adapter.submitData(it)
            }
        }
        AchievementManager.onRecordsOpened()
    }
}
