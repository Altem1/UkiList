package knf.kuma.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.Player
import knf.kuma.R
import knf.kuma.anilist.EXTRA_AID
import knf.kuma.anilist.EXTRA_NAME
import knf.kuma.anilist.EXTRA_NUMBER
import knf.kuma.anilist.EXTRA_SLUG
import kotlinx.coroutines.launch

/**
 * Con los tiempos de AniSkip: botón "Saltar opening/resumen", y al empezar el ending avisa a
 * [onEnding] (miniventana del siguiente). En un ending con escenas nuevas (mixed-ed) solo se ofrece
 * el botón de siguiente episodio, sin encoger el video.
 */
class SkipSegments(
    private val scope: LifecycleCoroutineScope,
    private val intent: Intent,
    private val button: TextView,
    private val onEnding: () -> Unit,
    private val onNext: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var times: AniSkip.Times? = null
    private var endingFired = false

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 500)
        }
    }

    fun attach(player: Player) {
        this.player = player
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: return
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val number = intent.getDoubleExtra(EXTRA_NUMBER, 0.0)
        if (number <= 0 || number % 1.0 != 0.0) return
        scope.launch {
            times = AniSkip.fetch(intent.getIntExtra(EXTRA_AID, 0), slug, name, number.toInt()) ?: return@launch
            handler.post(tick)
        }
    }

    fun detach() {
        handler.removeCallbacks(tick)
        button.isVisible = false
        player = null
    }

    private fun check() {
        val player = player ?: return
        val times = times ?: return
        // Si nuestro video dura distinto que el de AniSkip, los tiempos no son confiables.
        if (!times.matches(player.duration.takeIf { it > 0 } ?: return)) return detach()
        val position = player.currentPosition
        val current = times.intervals.firstOrNull { position in it }
        when (current?.type) {
            AniSkip.Type.OP, AniSkip.Type.MIXED_OP, AniSkip.Type.RECAP -> {
                button.setText(if (current.type == AniSkip.Type.RECAP) R.string.skip_recap else R.string.skip_opening)
                button.setOnClickListener { player.seekTo(current.end) }
                button.isVisible = true
            }
            AniSkip.Type.MIXED_ED -> {
                button.setText(R.string.next_episode_label)
                button.setOnClickListener { onNext() }
                button.isVisible = true
            }
            AniSkip.Type.ED -> {
                button.isVisible = false
                // Solo al entrar al ending una vez (si el usuario regresa con la barra, no se repite).
                if (!endingFired) {
                    endingFired = true
                    onEnding()
                }
            }
            null -> button.isVisible = false
        }
    }
}
