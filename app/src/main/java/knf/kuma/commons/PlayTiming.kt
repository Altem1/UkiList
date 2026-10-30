package knf.kuma.commons

import android.os.SystemClock
import android.util.Log

/** Marcas de tiempo del arranque de un episodio (logcat "PlayTiming") para ver qué etapa tarda. */
object PlayTiming {
    private var start = 0L

    fun start(stage: String) {
        start = SystemClock.elapsedRealtime()
        Log.i("PlayTiming", "0 ms: $stage")
    }

    fun mark(stage: String) {
        if (start != 0L) Log.i("PlayTiming", "${SystemClock.elapsedRealtime() - start} ms: $stage")
    }
}
