package knf.kuma.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import knf.kuma.R
import knf.kuma.anilist.EXTRA_NUMBER
import knf.kuma.anilist.EXTRA_SLUG
import knf.kuma.anilist.withScrobble
import knf.kuma.commons.load
import knf.kuma.commons.roundedString
import knf.kuma.commons.toast
import knf.kuma.database.CacheDB
import knf.kuma.videoservers.NextEpisodeResolver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Autoplay del siguiente episodio: al terminar el video muestra una miniventana con cuenta regresiva
 * (Cancelar / Ver ahora). Resuelve el video con el mismo servidor y, si falla, con los demás.
 * El siguiente episodio se abre en una nueva instancia del reproductor, así el scrobble (tracker 85%)
 * y el guardado de estado funcionan igual que con un episodio abierto a mano.
 */
class NextEpisodeAutoplay(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val root: ViewGroup,
    private val intent: Intent
) {
    private val slug = intent.getStringExtra(EXTRA_SLUG)
    private val number = intent.getDoubleExtra(EXTRA_NUMBER, 0.0)
    private var next: Deferred<NextEpisodeResolver.Next?>? = null
    private var timer: CountDownTimer? = null
    private var overlay: View? = null
    private var launched = false

    val isAvailable get() = slug != null && number > 0 && !intent.getBooleanExtra("isPlayList", false)

    /** Busca de antemano cuál es el siguiente episodio (no resuelve el video todavía). */
    fun prefetch() {
        if (!isAvailable || next != null) return
        next = scope.async { runCatching { NextEpisodeResolver.findNext(slug!!, number) }.getOrNull() }
    }

    /** Llamar al terminar el video; si no hay siguiente episodio ejecuta [onFinish]. */
    fun onEnded(onFinish: () -> Unit) {
        if (!isAvailable) { onFinish(); return }
        prefetch()
        scope.launch {
            val n = next?.await()
            if (n == null || activity.isFinishing) { onFinish(); return@launch }
            val resolved = async {
                NextEpisodeResolver.resolve(activity, n, intent.getStringExtra("server_name"), intent.getIntExtra("server_lang", 0))
            }
            show(n, resolved, onFinish)
        }
    }

    private fun show(n: NextEpisodeResolver.Next, resolved: Deferred<NextEpisodeResolver.Resolved?>, onFinish: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.next_episode_overlay, root, false)
        root.addView(view)
        overlay = view
        view.visibility = View.VISIBLE
        shrinkPlayer(true)
        val wid = n.chapter.withID(n.anime)
        view.findViewById<TextView>(R.id.next_title).text = activity.getString(R.string.next_episode_title, n.anime.name, n.chapter.number.roundedString())
        runCatching { view.findViewById<ImageView>(R.id.next_thumb).load(wid.thumbnail) }
        val countdown = view.findViewById<TextView>(R.id.next_countdown)
        val progress = view.findViewById<ProgressBar>(R.id.next_progress)
        val play = { timer?.cancel(); start(n, resolved, onFinish) }
        view.findViewById<View>(R.id.next_cancel).setOnClickListener {
            timer?.cancel()
            resolved.cancel()
            onFinish()
        }
        view.findViewById<View>(R.id.next_play).setOnClickListener { play() }
        timer = object : CountDownTimer(COUNTDOWN, 100) {
            override fun onTick(left: Long) {
                countdown.text = activity.getString(R.string.next_episode_countdown, ((left + 999) / 1000).toInt())
                progress.progress = ((COUNTDOWN - left) * 1000 / COUNTDOWN).toInt()
            }

            override fun onFinish() { play() }
        }.start()
    }

    private fun start(n: NextEpisodeResolver.Next, resolved: Deferred<NextEpisodeResolver.Resolved?>, onFinish: () -> Unit) {
        if (launched) return
        launched = true
        overlay?.findViewById<TextView>(R.id.next_countdown)?.text = activity.getString(R.string.next_episode_searching)
        startJob = scope.launch {
            val r = runCatching { resolved.await() }.getOrNull()
            // Si salió mientras se buscaba servidor, no se reabre el reproductor.
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (r == null) {
                activity.getString(R.string.next_episode_not_found).toast()
                onFinish()
                return@launch
            }
            removeOverlay()
            val wid = n.chapter.withID(n.anime)
            // Igual que al abrirlo a mano: queda como visto (sin scrobble; eso lo hace el tracker al 85%).
            withContext(Dispatchers.IO) { runCatching { CacheDB.INSTANCE.recordAV1DAO().addChapter(wid.asRecord()) } }
            knf.kuma.achievements.AchievementManager.onPlayChapter()
            activity.startActivity(
                Intent(activity, activity.javaClass)
                    .setData(Uri.parse(r.url))
                    .putExtra("title", "${n.anime.name} ${n.chapter.number.roundedString()}")
                    .putExtra("headers", r.headers)
                    .withScrobble(wid)
                    .putExtra("server_name", r.serverName)
                    .putExtra("server_lang", intent.getIntExtra("server_lang", 0))
            )
            // BasicExoplayer es singleTask: onNewIntent carga el siguiente episodio en esta misma instancia.
        }
    }

    private var startJob: kotlinx.coroutines.Job? = null

    fun cancel() {
        timer?.cancel()
        timer = null
        startJob?.cancel()
        startJob = null
        removeOverlay()
    }

    private fun removeOverlay() {
        if (overlay != null) shrinkPlayer(false)
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    /** El episodio que terminó queda encogido arriba a la izquierda, por encima del fondo del siguiente. */
    private fun shrinkPlayer(shrink: Boolean) {
        val player = root.findViewById<View>(R.id.player) ?: return
        (player as? androidx.media3.ui.PlayerView)?.apply { if (shrink) hideController(); useController = !shrink }
        val margin = 24 * activity.resources.displayMetrics.density
        player.pivotX = 0f
        player.pivotY = 0f
        if (shrink) {
            player.translationZ = 20f
            player.animate().scaleX(0.38f).scaleY(0.38f).translationX(margin).translationY(margin).setDuration(350).start()
        } else {
            player.animate().cancel()
            player.scaleX = 1f; player.scaleY = 1f; player.translationX = 0f; player.translationY = 0f
            player.translationZ = 0f
        }
    }

    companion object {
        private const val COUNTDOWN = 10_000L
    }
}
