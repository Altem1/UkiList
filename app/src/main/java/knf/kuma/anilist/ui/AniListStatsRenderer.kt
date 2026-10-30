package knf.kuma.anilist.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import knf.kuma.R
import knf.kuma.anilist.AniListApi
import knf.kuma.database.CacheDB
import knf.kuma.pojos.Achievement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

/** Construye las pestañas "Estadísticas" y "Perfil" de la sección AniList (estilo AniHyou). */
class AniListStatsRenderer(private val activity: androidx.appcompat.app.AppCompatActivity, private val container: LinearLayout, private val onFavourite: (AniListApi.FavouriteAnime) -> Unit) {

    private val ctx get() = activity
    private val nf = NumberFormat.getIntegerInstance()

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } else fallback
    }

    private val accent by lazy { attrColor(androidx.appcompat.R.attr.colorAccent, Color.BLUE) }
    private val secondaryText by lazy { attrColor(android.R.attr.textColorSecondary, Color.GRAY) }

    fun showMessage(text: String) {
        container.removeAllViews()
        container.addView(text(text, 15f).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(24)) },
            LinearLayout.LayoutParams(-1, -2))
    }

    // ---------- helpers ----------

    /** Texto con {star} cambiado por la estrella de puntuación, del color de acento. */
    private fun star(s: String, size: Float): CharSequence = ctx.iconText(s, mapOf("star" to R.drawable.ic_score_star), size, accent)

    private fun text(s: CharSequence, size: Float = 14f, bold: Boolean = false, secondary: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (secondary) setTextColor(secondaryText)
    }

    private fun card(title: String?, build: LinearLayout.() -> Unit): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            useCompatPadding = true
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        title?.let { inner.addView(text(it, 16f, bold = true).apply { setPadding(0, 0, 0, dp(8)); setTextColor(accent) }) }
        inner.build()
        card.addView(inner)
        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) })
        return card
    }

    private fun LinearLayout.bar(label: CharSequence, value: Int, max: Int, detail: CharSequence) {
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(label, 13f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(detail, 12f, secondary = true))
        })
        addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = 100
            progress = if (max <= 0) 0 else (value * 100f / max).roundToInt()
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(-1, dp(10)).apply { bottomMargin = dp(8) })
    }

    private fun LinearLayout.tileRow(vararg tiles: Pair<String, String>) {
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.forEach { (value, label) ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    addView(text(value, 20f, bold = true).apply { setTextColor(accent) })
                    addView(text(label, 12f, secondary = true))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
    }

    private fun pretty(enum: String) = enum.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    // ---------- Estadísticas ----------

    fun renderStats(p: AniListApi.UserProfile) {
        container.removeAllViews()
        val s = p.stats
        val days = s.minutesWatched / 1440.0
        card(ctx.getString(R.string.anilist_stat_summary)) {
            tileRow(nf.format(s.count) to ctx.getString(R.string.anilist_stat_anime),
                nf.format(s.episodesWatched) to ctx.getString(R.string.anilist_stat_episodes))
            tileRow(String.format("%.1f", days) to ctx.getString(R.string.anilist_stat_days),
                nf.format(s.minutesWatched / 60) to ctx.getString(R.string.anilist_stat_hours),
                (if (s.meanScore > 0) String.format("%.1f", s.meanScore) else "-") to ctx.getString(R.string.anilist_stat_mean))
        }
        if (s.scores.isNotEmpty()) card(ctx.getString(R.string.anilist_stat_scores)) {
            val max = s.scores.maxOf { it.count }
            s.scores.forEach { bar(star("{star} ${it.label}", 13f), it.count, max, ctx.getString(R.string.anilist_count_suffix, it.count)) }
        }
        if (s.genres.isNotEmpty()) card(ctx.getString(R.string.anilist_stat_genres)) {
            val top = s.genres.take(10)
            val max = top.maxOf { it.count }
            top.forEachIndexed { i, g ->
                val score = if (g.meanScore > 0) " · {star} ${g.meanScore.roundToInt()}" else ""
                bar("${i + 1}. ${g.label}", g.count, max,
                    star(ctx.getString(R.string.anilist_count_suffix, g.count) + " · " + String.format("%.1f d", g.minutesWatched / 1440.0) + score, 12f))
            }
        }
        if (s.releaseYears.isNotEmpty()) card(ctx.getString(R.string.anilist_stat_years)) {
            val years = s.releaseYears.sortedByDescending { it.label.toIntOrNull() ?: 0 }.take(15)
            val max = years.maxOf { it.count }
            years.forEach { y ->
                val score = if (y.meanScore > 0) " · {star} ${y.meanScore.roundToInt()}" else ""
                bar(y.label, y.count, max, star(ctx.getString(R.string.anilist_count_suffix, y.count) + score, 12f))
            }
        }
        if (s.formats.isNotEmpty()) card(ctx.getString(R.string.anilist_stat_formats)) {
            val max = s.formats.maxOf { it.count }
            s.formats.forEach { bar(pretty(it.label), it.count, max, nf.format(it.count)) }
        }
        if (s.statuses.isNotEmpty()) card(ctx.getString(R.string.anilist_stat_statuses)) {
            val max = s.statuses.maxOf { it.count }
            s.statuses.forEach { bar(pretty(it.label), it.count, max, nf.format(it.count)) }
        }
    }

    // ---------- Perfil ----------

    private fun medals(s: AniListApi.AnimeStatistics): List<Pair<Int, Boolean>> {
        val topGenre = s.genres.maxByOrNull { it.count }?.count ?: 0
        return listOf(
            R.string.anilist_medal_1 to (s.count >= 10),
            R.string.anilist_medal_2 to (s.count >= 100),
            R.string.anilist_medal_3 to (s.count >= 300),
            R.string.anilist_medal_4 to (s.minutesWatched >= 10_080),
            R.string.anilist_medal_5 to (s.minutesWatched >= 43_200),
            R.string.anilist_medal_6 to (s.minutesWatched >= 144_000),
            R.string.anilist_medal_7 to (s.episodesWatched >= 500),
            R.string.anilist_medal_8 to (s.episodesWatched >= 1_000),
            R.string.anilist_medal_9 to (s.scores.sumOf { it.count } >= 50),
            R.string.anilist_medal_10 to (s.genres.count { it.count >= 5 } >= 10),
            R.string.anilist_medal_11 to (topGenre >= 50)
        )
    }

    fun renderProfile(p: AniListApi.UserProfile) {
        container.removeAllViews()
        // Cabecera: banner + avatar + nombre
        val header = FrameLayout(ctx)
        val banner = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(accent) }
        header.addView(banner, FrameLayout.LayoutParams(-1, dp(150)))
        header.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xCC000000.toInt()))
        }, FrameLayout.LayoutParams(-1, dp(150)))
        p.bannerUrl?.let { Glide.with(banner).load(it).into(banner) }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        val avatar = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        p.avatarUrl?.let { Glide.with(avatar).load(it).circleCrop().into(avatar) }
        row.addView(avatar, LinearLayout.LayoutParams(dp(72), dp(72)))
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text(p.name, 20f, bold = true).apply { setTextColor(Color.WHITE) })
            if (p.createdAt > 0) addView(text(ctx.getString(R.string.anilist_member_since,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.createdAt * 1000))), 12f).apply { setTextColor(0xDDFFFFFF.toInt()) })
            addView(text(ctx.getString(R.string.anilist_open_web), 13f, bold = true).apply {
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { AniListDrawer.openUrl(ctx, p.siteUrl ?: "https://anilist.co/user/${p.name}/") }
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        container.addView(header, LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(4) })

        // Biografía
        card(ctx.getString(R.string.anilist_bio)) {
            val about = p.about?.let { plain(it) }?.takeIf { it.isNotBlank() }
            addView(text(about ?: ctx.getString(R.string.anilist_no_bio), 14f, secondary = about == null))
        }

        // Medallas
        val list = medals(p.stats)
        card(ctx.getString(R.string.anilist_medals, list.count { it.second }, list.size)) {
            list.chunked(2).forEach { pair ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEach { (res, unlocked) -> addView(medal(res, unlocked), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
                    if (pair.size == 1) addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
                })
            }
        }

        // Logros locales
        val achievementsCard = card(ctx.getString(R.string.anilist_local_achievements)) {
            addView(text(ctx.getString(R.string.anilist_loading), 13f, secondary = true))
        }
        activity.lifecycleScope.launch {
            val unlocked = withContext(Dispatchers.IO) {
                runCatching { CacheDB.INSTANCE.achievementsDAO().completedAchievements }.getOrNull() ?: emptyList<Achievement>()
            }.filter { it.key !in IGNORED_ACHIEVEMENTS }
            val inner = (achievementsCard as MaterialCardView).getChildAt(0) as LinearLayout
            while (inner.childCount > 1) inner.removeViewAt(1)
            if (unlocked.isEmpty()) inner.addView(text(ctx.getString(R.string.anilist_no_achievements), 13f, secondary = true))
            unlocked.forEach { a ->
                inner.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(text(ctx.iconText("{trophy} ${a.name}", mapOf("trophy" to R.drawable.ic_trophy_gold), 14f), 14f, bold = true))
                    if (a.description.isNotBlank()) addView(text(a.description, 12f, secondary = true))
                })
            }
        }

        // Favoritos
        card(ctx.getString(R.string.anilist_favourites)) {
            if (p.favourites.isEmpty()) {
                addView(text(ctx.getString(R.string.anilist_no_favourites), 13f, secondary = true))
                return@card
            }
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            p.favourites.forEach { f ->
                strip.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    f.cover?.let { Glide.with(cover).load(it).into(cover) }
                    addView(cover, LinearLayout.LayoutParams(dp(100), dp(145)))
                    addView(text(f.title, 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(100), -2))
                    setOnClickListener { onFavourite(f) }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }

        favouriteStrip(R.string.anilist_fav_characters, p.favCharacters)
        favouriteStrip(R.string.anilist_fav_staff, p.favStaff)
        if (p.favStudios.isNotEmpty()) card(ctx.getString(R.string.anilist_fav_studios)) {
            p.favStudios.forEach { st ->
                addView(text("• ${st.name}", 14f).apply {
                    setPadding(0, dp(4), 0, dp(4))
                    st.url?.let { url -> setOnClickListener { AniListDrawer.openUrl(ctx, url) } }
                })
            }
        }
    }

    /** Personajes / staff favoritos: fila horizontal de retratos redondos que abren su página en AniList. */
    private fun favouriteStrip(titleRes: Int, items: List<AniListApi.FavouriteItem>) {
        if (items.isEmpty()) return
        card(ctx.getString(titleRes)) {
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { f ->
                strip.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    f.image?.let { Glide.with(img).load(it).circleCrop().into(img) }
                    addView(img, LinearLayout.LayoutParams(dp(80), dp(80)))
                    addView(text(f.name, 12f).apply {
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(88), -2))
                    f.url?.let { url -> setOnClickListener { AniListDrawer.openUrl(ctx, url) } }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
    }

    private fun medal(res: Int, unlocked: Boolean): View {
        val (name, desc) = ctx.getString(res).split('|').let { it[0] to it.getOrElse(1) { "" } }
        return MaterialCardView(ctx).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = if (unlocked) accent else secondaryText
            alpha = if (unlocked) 1f else 0.45f
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(text(ctx.iconText((if (unlocked) "{medal} " else "{lock} ") + name,
                    mapOf("medal" to R.drawable.ic_trophy_gold, "lock" to R.drawable.ic_locked), 13f, if (unlocked) null else secondaryText), 13f, bold = true).apply { if (!unlocked) setTextColor(secondaryText) })
                addView(text(desc, 11f, secondary = true))
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun plain(raw: String): String {
        val noMarkup = raw.replace(Regex("~!|!~|__|~~~|img\\d*%?\\(([^)]*)\\)|youtube\\(([^)]*)\\)|webm\\(([^)]*)\\)"), "")
        return Html.fromHtml(noMarkup.replace("\n", "<br>")).toString().trim()
    }

    companion object {
        private val IGNORED_ACHIEVEMENTS = setOf(30L, 46L, 47L, 48L, 49L, 50L, 51L)
    }
}
