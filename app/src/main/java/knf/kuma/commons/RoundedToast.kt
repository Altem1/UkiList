package knf.kuma.commons

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import knf.kuma.App
import knf.kuma.R

/** Toast con esquinas redondeadas y fondo semitransparente. Se puede llamar desde cualquier hilo. */
object RoundedToast {
    fun show(text: String, long: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            try {
                val view = LayoutInflater.from(App.context).inflate(R.layout.toast_rounded, null) as TextView
                view.text = text
                @Suppress("DEPRECATION")
                Toast(App.context).apply {
                    duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    this.view = view
                }.show()
            } catch (e: Exception) {
                Toast.makeText(App.context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }
}
