package knf.kuma.custom

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import knf.kuma.R

// Barra inferior flotante: redondeada, semitransparente y con el contenido pasando por debajo
fun FragmentActivity.setFloatingBottomBar(bar: BottomNavigationView) {
    val density = resources.displayMetrics.density
    val surface = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurface)
    bar.background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(28 * density).build()).apply {
        // Gris de la superficie aclarado hacia blanco para que la barra se distinga del fondo
        val tinted = ColorUtils.blendARGB(surface, Color.WHITE, 0.14f)
        fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tinted, 0xB3))
        setStroke(1 * density, ColorUtils.setAlphaComponent(Color.WHITE, 0x22))
    }
    bar.clipToOutline = true
    findViewById<View>(R.id.bottomGroup)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        applyBottomInset(findViewById(R.id.root))
    }
    supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            v.post { applyBottomInset(v) }
        }
    }, true)
}

// Las listas reciben padding inferior para que su último elemento no quede tapado por la barra
private fun FragmentActivity.applyBottomInset(view: View?) {
    val group = findViewById<View>(R.id.bottomGroup) ?: return
    val inset = group.height + (20 * resources.displayMetrics.density).toInt()
    fun pad(v: ViewGroup) {
        val original = (v.getTag(R.id.bottomGroup) as? Int)
                ?: v.paddingBottom.also { v.setTag(R.id.bottomGroup, it) }
        v.clipToPadding = false
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, original + inset)
    }
    fun walk(v: View) {
        when {
            // Filas horizontales (Inicio): no se desplazan hacia abajo, el espacio extra solo agrandaba cada sección.
            v is RecyclerView && (v.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.orientation == RecyclerView.HORIZONTAL -> {}
            v is RecyclerView -> pad(v)
            // Pantallas con scroll general: el espacio va al final de la página, no a las listas de adentro.
            v is androidx.core.widget.NestedScrollView -> pad(v)
            v is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    view?.let { walk(it) }
}
