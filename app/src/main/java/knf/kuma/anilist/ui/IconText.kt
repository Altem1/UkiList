package knf.kuma.anilist.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Sustituye los marcadores {icon} del texto por íconos de la app (en vez de emojis),
 * del tamaño de la letra y con el color indicado.
 */
fun Context.iconText(text: String, icons: Map<String, Int>, sizeSp: Float, tint: Int? = null): CharSequence {
    val size = (sizeSp * resources.displayMetrics.scaledDensity * 1.1f).toInt()
    val out = SpannableStringBuilder()
    var i = 0
    val pattern = Regex("""\{(\w+)\}""")
    pattern.findAll(text).forEach { m ->
        out.append(text, i, m.range.first)
        val res = icons[m.groupValues[1]]
        val drawable = res?.let { ContextCompat.getDrawable(this, it)?.mutate() }
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size)
            tint?.let { DrawableCompat.setTint(drawable, it) }
            val start = out.length
            out.append(" ")
            out.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        i = m.range.last + 1
    }
    out.append(text, i, text.length)
    return out
}
