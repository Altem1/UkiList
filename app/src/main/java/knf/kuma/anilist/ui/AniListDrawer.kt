package knf.kuma.anilist.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import knf.kuma.R
import knf.kuma.anilist.AniListAuth
import knf.kuma.anilist.AniListImportWorker
import knf.kuma.commons.safeShow
import knf.kuma.commons.toast
import kotlinx.coroutines.launch

/** Header del menú lateral: usuario y avatar de AniList, y la nube con los apartados de AniList. */
object AniListDrawer {

    fun bind(activity: AppCompatActivity, header: View, openSettings: () -> Unit) {
        if (header.getTag(R.id.header_avatar) == true) return
        header.setTag(R.id.header_avatar, true)
        val name = header.findViewById<TextView>(R.id.backupLocation)
        val avatar = header.findViewById<ImageView>(R.id.header_avatar)
        AniListAuth.userName.observe(activity) { user ->
            name.text = user ?: "Conectar con AniList"
        }
        AniListAuth.avatarUrl.observe(activity) { url ->
            if (url.isNullOrBlank())
                avatar.setImageResource(R.mipmap.ic_launcher)
            else
                Glide.with(activity).load(url).circleCrop().placeholder(R.mipmap.ic_launcher).into(avatar)
        }
        // Sin sesión o sin portada: degradado azul-morado; con portada de AniList, la portada oscurecida.
        val banner = header.findViewById<View>(R.id.img)
        AniListAuth.bannerUrl.observe(activity) { url -> loadBanner(activity, banner, url) }
        // Sesiones iniciadas antes de guardar el avatar o la portada: los pedimos una vez por sesión de la app.
        if (AniListAuth.isLoggedIn && !viewerRefreshed) {
            viewerRefreshed = true
            activity.lifecycleScope.launch { AniListAuth.fetchViewer() }
        }
        val onUserClick = View.OnClickListener {
            if (AniListAuth.isLoggedIn) openSettings() else AniListAuth.launchLogin(activity)
        }
        name.setOnClickListener(onUserClick)
        avatar.setOnClickListener(onUserClick)
        header.findViewById<View>(R.id.action_login).setOnClickListener { showMenu(activity, openSettings) }
    }

    /** Botón Perfil de la barra inferior: muestra la foto de AniList (sin el tinte de la barra); sin sesión, el ícono genérico. */
    fun bindBottomAvatar(activity: AppCompatActivity, bottom: com.google.android.material.navigation.NavigationBarView) {
        val item = bottom.menu.findItem(R.id.action_bottom_profile) ?: return
        AniListAuth.avatarUrl.observe(activity) { url ->
            val itemView = bottom.findViewById<com.google.android.material.navigation.NavigationBarItemView>(R.id.action_bottom_profile)
            if (url.isNullOrBlank()) {
                item.setIcon(R.drawable.ic_bottom_profile)
                itemView?.setIconTintList(bottom.itemIconTintList)
                return@observe
            }
            Glide.with(activity).asBitmap().load(url).circleCrop()
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                        item.icon = android.graphics.drawable.BitmapDrawable(activity.resources, resource)
                        itemView?.setIconTintList(null)
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        }
    }

    private var viewerRefreshed = false

    /** Para quien repinte el header (p. ej. al cambiar el tema): vuelve a poner la portada o el degradado. */
    fun refreshBanner(activity: AppCompatActivity, header: View) =
        loadBanner(activity, header.findViewById(R.id.img), AniListAuth.bannerUrl.value)

    private fun loadBanner(activity: AppCompatActivity, banner: View, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(activity).clear(bannerTarget(banner))
            banner.setBackgroundResource(knf.kuma.commons.EAHelper.getThemeImg())
            return
        }
        banner.post {
            Glide.with(activity).load(url)
                .override(banner.width.coerceAtLeast(1), banner.height.coerceAtLeast(1))
                .centerCrop()
                .into(bannerTarget(banner))
        }
    }

    private fun bannerTarget(banner: View) = (banner.getTag(R.id.img) as? CustomViewTarget<View, Drawable>)
        ?: object : CustomViewTarget<View, Drawable>(banner) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                view.background = LayerDrawable(arrayOf(resource,
                    ContextCompat.getDrawable(view.context, R.drawable.side_nav_bar_scrim)))
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                view.setBackgroundResource(knf.kuma.commons.EAHelper.getThemeImg())
            }

            override fun onResourceCleared(placeholder: Drawable?) {}
        }.also { banner.setTag(R.id.img, it) }

    private fun showMenu(activity: AppCompatActivity, openSettings: () -> Unit) {
        if (!AniListAuth.isLoggedIn) {
            AniListAuth.launchLogin(activity)
            return
        }
        val user = AniListAuth.userName.value ?: ""
        val options = listOf("Mi lista", "Perfil en AniList", "Estadísticas", "Sincronizar ahora", "Ajustes de sincronización", "Cerrar sesión")
        MaterialDialog(activity).safeShow {
            title(text = user)
            listItems(items = options) { _, index, _ ->
                when (index) {
                    0 -> AniListActivity.open(activity)
                    1 -> openUrl(activity, "https://anilist.co/user/$user/")
                    2 -> openUrl(activity, "https://anilist.co/user/$user/stats/anime/overview")
                    3 -> {
                        AniListImportWorker.runNow(activity)
                        "Sincronizando con AniList...".toast()
                    }
                    4 -> openSettings()
                    5 -> MaterialDialog(activity).safeShow {
                        message(text = "¿Cerrar sesión de AniList?")
                        positiveButton(text = "Cerrar sesión") { AniListAuth.logout() }
                        negativeButton(text = "Cancelar")
                    }
                }
            }
        }
    }

    fun openUrl(activity: AppCompatActivity, url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            "No se pudo abrir el navegador".toast()
        }
    }
}
