package knf.kuma.animeinfo.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import knf.kuma.App
import knf.kuma.BottomFragment
import knf.kuma.R
import knf.kuma.animeinfo.AnimeViewModel
import knf.kuma.animeinfo.MoveCallback
import knf.kuma.animeinfo.viewholders.AnimeChaptersMaterialHolder
import knf.kuma.commons.EAHelper
import knf.kuma.commons.FileUtil
import knf.kuma.commons.PrefsUtil
import knf.kuma.custom.snackbar.SnackProgressBar
import knf.kuma.custom.snackbar.SnackProgressBarManager
import knf.kuma.download.FileAccessHelper
import knf.kuma.download.MultipleDownloadManager
import knf.kuma.pojos.av1.Chapter
import knf.kuma.pojos.av1.DirectoryAV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import knf.kuma.anilist.AniListSync
import knf.kuma.cast.CastMedia
import knf.kuma.commons.CastUtil
import knf.kuma.commons.roundedString
import knf.kuma.database.CacheDB
import knf.kuma.videoservers.FileActions
import knf.kuma.videoservers.ServersFactory
import xdroid.toaster.Toaster
import kotlin.time.Duration.Companion.seconds

class ChaptersFragmentMaterial : BottomFragment(), AnimeChaptersMaterialHolder.ChapHolderCallback, MoveCallback {
    private var holder: AnimeChaptersMaterialHolder? = null
    private var moveFile: String? = null
    private var anime: DirectoryAV1? = null
    private var chapters: List<Chapter> = emptyList()
    private lateinit var snackManager: SnackProgressBarManager
    private var recyclerInitialPadding: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.recycler_chapters, container, false)
        holder = AnimeChaptersMaterialHolder(view, this, this).also {
            snackManager = SnackProgressBarManager(it.recyclerView)
                    .setProgressBarColor(EAHelper.getThemeColor())
                    .setOverlayLayoutAlpha(0.4f)
                    .setOverlayLayoutColor(android.R.color.background_dark)
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // En la ficha hub, al llegar los títulos/sinopsis traducidos se redibujan las tarjetas.
        (activity as? knf.kuma.animeinfo.hub.AnimeHubActivity)?.let { hub ->
            // La lista avisa al encabezado de la ficha al desplazarse (si no, al subir el encabezado no regresaba).
            holder?.recyclerView?.isNestedScrollingEnabled = true
            viewLifecycleOwner.lifecycleScope.launch {
                hub.episodeMeta.collect { holder?.recyclerView?.adapter?.notifyDataSetChanged() }
            }
        }
        holder?.recyclerView?.apply {
            if (recyclerInitialPadding == null) {
                recyclerInitialPadding = paddingBottom
            }
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val navigationBarsInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    recyclerInitialPadding?.plus(navigationBarsInsets.bottom) ?: navigationBarsInsets.bottom
                )
                insets
            }
        }
        activity?.let { activity ->
            viewLifecycleOwner.lifecycleScope.launch {
                ViewModelProvider(activity)[AnimeViewModel::class.java].infoFlow.collectLatest { animeObject ->
                    animeObject?.chapters?.let {
                        anime = animeObject
                        launch(Dispatchers.Main) {
                            chapters = animeObject.chapters.let {
                                if (PrefsUtil.isChapsAsc) {
                                    it.sortedBy { it.number }
                                } else {
                                    it.sortedByDescending { it.number }
                                }
                            }
                            holder?.setAdapter(this@ChaptersFragmentMaterial, animeObject, chapters)
                            updatePlayButton()
                            delay(1.seconds)
                            holder?.goToChapter()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (anime != null) updatePlayButton()
    }

    /** Episodio para continuar: el último visto si quedó a medias; si se terminó (scrobble), el siguiente. */
    private suspend fun continueTarget(anime: DirectoryAV1): Chapter? = withContext(Dispatchers.IO) {
        val sorted = anime.chapters.sortedBy { it.number }
        if (sorted.isEmpty()) return@withContext null
        val recordsDAO = CacheDB.INSTANCE.recordAV1DAO()
        val lastSeen = sorted.lastOrNull { recordsDAO.chapterIsSeen(it.eid) } ?: return@withContext sorted.first()
        val finished = !AniListSync.isScrobbleEnabled ||
                CacheDB.INSTANCE.aniListScrobbleDAO().episodes(anime.slug).contains(kotlin.math.floor(lastSeen.number).toInt())
        if (finished) sorted.firstOrNull { it.number > lastSeen.number } ?: lastSeen else lastSeen
    }

    private fun updatePlayButton() {
        val fab = view?.findViewById<ExtendedFloatingActionButton>(R.id.play_continue) ?: return
        // La ficha hub ya tiene su propio botón Reproducir arriba.
        if (activity is knf.kuma.animeinfo.hub.AnimeHubActivity) {
            fab.isVisible = false
            return
        }
        val anime = anime ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val chapter = continueTarget(anime) ?: return@launch
            val seenBefore = withContext(Dispatchers.IO) { CacheDB.INSTANCE.recordAV1DAO().chapterIsSeen(chapter.eid) }
            fab.text = "${if (seenBefore) "Continuar" else "Ver"} EP ${chapter.number.roundedString()}"
            fab.isVisible = true
            fab.setOnClickListener { playChapter(anime, chapter) }
        }
    }

    fun playChapter(anime: DirectoryAV1, chapter: Chapter) {
        val markSeen = {
            lifecycleScope.launch(Dispatchers.IO) { CacheDB.INSTANCE.recordAV1DAO().addChapter(chapter.asRecord(anime)) }
            chapter.isSeen = true
            holder?.refresh()
        }
        if (chapter.fileWrapper(anime).exist) {
            markSeen()
            ServersFactory.startPlay(requireContext(), chapter.episodeName(anime), chapter.fileWrapper(anime).name())
            return
        }
        FileActions.quickStream(this, anime, chapter) { state, extra ->
            when (state) {
                FileActions.CallbackState.START_STREAM -> markSeen()
                FileActions.CallbackState.START_CAST -> {
                    holder?.recyclerView?.let { CastUtil.get().play(it, CastMedia.create(anime, chapter, extra as? String)) }
                    markSeen()
                }
                else -> {}
            }
        }
    }

    override fun onReselect() {
        holder?.smoothGoToChapter()
    }

    override fun onMove(to: String) {
        this.moveFile = to
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("video/mp4"), 55698)
    }

    override fun onImportMultiple(chapters: MutableList<Chapter>) {
        anime ?: return
        when (chapters.size) {
            0 -> Toaster.toast("No se puede importar ningun episodio")
            1 -> {
                this.moveFile = chapters[0].filePath(anime!!)
                onMove(chapters[0].filePath(anime!!))
            }
            else -> {
                this.chapters = chapters
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        .setType("video/mp4"), 55698)
            }
        }
    }

    override fun onDownloadMultiple(addQueue: Boolean, chapters: List<Chapter>) {
        anime?: return
        holder?.let { holder ->
            MultipleDownloadManager.startDownload(this, holder.recyclerView, anime!!, chapters.sortedBy { it.number }, addQueue)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK)
            try {
                holder?.adapter?.isImporting = true
                if (data?.clipData == null || (data.clipData?.itemCount ?: 0) == 0) {
                    if (moveFile == null && chapters.isNotEmpty()) {
                        val uri = data?.data
                        val file = DocumentFile.fromSingleUri(App.context, uri ?: Uri.EMPTY)
                        val last = getLastNumber(file?.name)
                        moveFile = chapters.find { it.number == last }?.filePath(anime!!)
                    }
                    val snackbar = SnackProgressBar(SnackProgressBar.TYPE_HORIZONTAL, "Importando...")
                            .setIsIndeterminate(false)
                            .setProgressMax(100)
                            .setShowProgressPercentage(true)
                    snackManager.show(snackbar, SnackProgressBarManager.LENGTH_INDEFINITE)
                    FileUtil.moveFile(App.context.contentResolver, data?.data, FileAccessHelper.getOutputStream(moveFile)).observe(this, Observer { pair ->
                        try {
                            if (pair != null) {
                                if (pair.second) {
                                    if (pair.first == -1) {
                                        Toaster.toast("Error al importar")
                                        FileAccessHelper.delete(moveFile)
                                    } else
                                        Toaster.toast("Importado exitosamente")
                                    holder?.adapter?.notifyDataSetChanged()
                                    moveFile = null
                                    snackManager.dismiss()
                                    holder?.adapter?.isImporting = false
                                } else
                                    snackManager.setProgress(pair.first)
                            }
                        } catch (e: Exception) {
                            Toaster.toast("Error al importar")
                        }
                    })
                } else {
                    val snackbar = SnackProgressBar(SnackProgressBar.TYPE_HORIZONTAL, "Importando...")
                            .setIsIndeterminate(false)
                            .setProgressMax(100)
                            .setShowProgressPercentage(true)
                    snackManager.show(snackbar, SnackProgressBarManager.LENGTH_INDEFINITE)
                    val moveRequests = ArrayList<Pair<Uri, String>>()
                    val count = data.clipData?.itemCount ?: 0
                    for (i in 0 until count) {
                        try {
                            val uri = data.clipData?.getItemAt(i)?.uri ?: Uri.EMPTY
                            val file = DocumentFile.fromSingleUri(App.context, uri)
                            val last = getLastNumber(file?.name)
                            chapters.find { it.number == last }?.let {
                                moveRequests.add(Pair(uri, it.filePath(anime!!)))
                            }
                        } catch (e: Exception) {
                            //
                        }
                    }
                    if (moveRequests.size == 0) {
                        Toaster.toast("No se pudo inferir el numero de los episodios")
                        snackManager.dismiss()
                    } else {
                        FileUtil.moveFiles(App.context.contentResolver, moveRequests).observe(this@ChaptersFragmentMaterial, Observer { pairBooleanPair ->
                            try {
                                if (pairBooleanPair != null) {
                                    if (pairBooleanPair.second) {
                                        Toaster.toast("Importados ${pairBooleanPair.first.second} archivos exitosamente")
                                        holder?.adapter?.notifyDataSetChanged()
                                        chapters = ArrayList()
                                        snackManager.dismiss()
                                        holder?.adapter?.isImporting = false
                                    } else {
                                        snackbar.setMessage(pairBooleanPair.first.first)
                                        snackManager.updateTo(snackbar)
                                        snackManager.setProgress(pairBooleanPair.first.second)
                                    }
                                }
                            } catch (e: Exception) {
                                Toaster.toast("Error al importar")
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toaster.toast("Error al importar")
            }

    }

    private fun getLastNumber(name: String?): Double? {
        if (name.isNullOrEmpty()) return null
        return try {
            name.replace(".mp4", "").substringAfterLast("-").toDouble()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {

        fun get(): ChaptersFragmentMaterial {
            return ChaptersFragmentMaterial()
        }
    }
}
