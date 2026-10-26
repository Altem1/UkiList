package xdroid.toaster

import androidx.annotation.StringRes
import knf.kuma.App
import knf.kuma.commons.RoundedToast

/**
 * Reemplazo local de com.shamanland:xdroid-toaster con la misma API, para que los 150+ avisos
 * de la app usen el toast redondeado y semitransparente sin tocar cada llamada.
 */
object Toaster {
    // Todas pasan por show(): llamar toast(String) desde aquí elegía la sobrecarga vararg y se llamaba a sí misma (StackOverflow).
    private fun show(text: String?, long: Boolean) { if (!text.isNullOrEmpty()) RoundedToast.show(text, long = long) }
    @JvmStatic fun toast(text: String?) = show(text, false)
    @JvmStatic fun toast(@StringRes res: Int) = show(App.context.getString(res), false)
    @JvmStatic fun toast(format: String, vararg args: Any?) = show(if (args.isEmpty()) format else String.format(format, *args), false)
    @JvmStatic fun toastLong(text: String?) = show(text, true)
    @JvmStatic fun toastLong(@StringRes res: Int) = show(App.context.getString(res), true)
    @JvmStatic fun toastLong(format: String, vararg args: Any?) = show(if (args.isEmpty()) format else String.format(format, *args), true)
}
