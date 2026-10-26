package knf.kuma.anilist.ui

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import knf.kuma.R
import knf.kuma.anilist.AniListImportWorker

/**
 * Ventana con el avance de la sincronización manual con AniList (la primera al conectar, o "Sincronizar ahora"):
 * pide dejar la app abierta, muestra el anime en curso con su carátula (solo en caché de Glide)
 * y cuenta los sincronizados y los que no se encontraron, con una animación de rechazo.
 */
object AniListSyncDialog {

    fun observe(activity: AppCompatActivity) {
        var dialog: AlertDialog? = null
        var views: Views? = null
        var lastTitle: String? = null
        WorkManager.getInstance(activity).getWorkInfosForUniqueWorkLiveData(AniListImportWorker.NAME).observe(activity) { infos ->
            val info = infos?.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: infos?.lastOrNull() ?: return@observe
            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    if (dialog == null) {
                        val v = Views(LayoutInflater.from(activity).inflate(R.layout.dialog_anilist_sync, null))
                        views = v
                        dialog = AlertDialog.Builder(activity)
                            .setTitle(R.string.anilist_sync_dialog_title)
                            .setView(v.root)
                            .setCancelable(false)
                            .setPositiveButton(R.string.anilist_sync_dialog_hide, null)
                            .create()
                        runCatching { dialog?.show() }
                    }
                    val title = views?.update(activity, info, lastTitle)
                    if (title != null) lastTitle = title
                }
                WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    val d = dialog ?: return@observe
                    d.setTitle(R.string.anilist_sync_dialog_done)
                    views?.finish()
                    d.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(R.string.anilist_sync_dialog_close)
                    dialog = null
                    views = null
                }
                else -> {}
            }
        }
    }

    private class Views(val root: View) {
        val frame: View = root.findViewById(R.id.sync_cover_frame)
        val cover: ImageView = root.findViewById(R.id.sync_cover)
        val denied: ImageView = root.findViewById(R.id.sync_denied)
        val title: TextView = root.findViewById(R.id.sync_title)
        val animeProgress: TextView = root.findViewById(R.id.sync_anime_progress)
        val bar: ProgressBar = root.findViewById(R.id.sync_bar)
        val step: TextView = root.findViewById(R.id.sync_step)
        val counters: TextView = root.findViewById(R.id.sync_counters)

        /** Devuelve el título mostrado, para cargar la carátula solo cuando cambia de anime. */
        fun update(activity: AppCompatActivity, info: WorkInfo, lastTitle: String?): String? {
            val p = info.progress
            val total = p.getInt(AniListImportWorker.P_TOTAL, 0)
            if (total == 0) return null
            val index = p.getInt(AniListImportWorker.P_INDEX, 0)
            val name = p.getString(AniListImportWorker.P_TITLE)
            val episodes = p.getInt(AniListImportWorker.P_EPISODES, -1)
            val failed = p.getBoolean(AniListImportWorker.P_FAILED, false)
            bar.isIndeterminate = false
            bar.max = total
            bar.progress = index
            step.text = activity.getString(R.string.anilist_sync_dialog_step, index, total)
            counters.text = activity.getString(R.string.anilist_sync_dialog_counters,
                p.getInt(AniListImportWorker.P_OK, 0), p.getInt(AniListImportWorker.P_FAIL, 0))
            title.text = name
            if (name != lastTitle) {
                denied.alpha = 0f
                Glide.with(activity).load(p.getString(AniListImportWorker.P_COVER))
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE).into(cover)
            }
            animeProgress.text = when {
                failed -> activity.getString(R.string.anilist_sync_dialog_failed)
                episodes < 0 -> activity.getString(R.string.anilist_sync_dialog_searching)
                episodes == 0 -> activity.getString(R.string.anilist_sync_dialog_no_episodes)
                else -> activity.getString(R.string.anilist_sync_dialog_episodes, episodes)
            }
            if (failed) deny()
            return name
        }

        /** Sacudida de "no" con una X roja sobre la carátula. */
        private fun deny() {
            denied.animate().alpha(1f).setDuration(150).start()
            ObjectAnimator.ofFloat(frame, View.TRANSLATION_X, 0f, -18f, 18f, -12f, 12f, -6f, 6f, 0f)
                .setDuration(450).start()
        }

        fun finish() {
            bar.isIndeterminate = false
            bar.progress = bar.max
            animeProgress.text = ""
            title.text = ""
            denied.alpha = 0f
            frame.visibility = View.GONE
        }
    }
}
