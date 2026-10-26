package knf.kuma.ads

import android.app.Activity
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import knf.kuma.pojos.Achievement
import knf.kuma.pojos.FavoriteObject
import knf.kuma.pojos.av1.BaseRecentAV1

enum class AdsType {
    RECENT_BANNER,
    RECENT_BANNER2,
    FAVORITE_BANNER,
    FAVORITE_BANNER2,
    DIRECTORY_BANNER,
    HOME_BANNER,
    HOME_BANNER2,
    EMISSION_BANNER,
    SEEING_BANNER,
    RECOMMEND_BANNER,
    QUEUE_BANNER,
    RECORD_BANNER,
    NEWS_BANNER,
    RANDOM_BANNER,
    INFO_BANNER,
    ACHIEVEMENT_BANNER,
    EXPLORER_BANNER,
    CAST_BANNER,
    REWARDED,
    INTERSTITIAL
}

object AdsUtils {
    // Configuración local (antes Firebase Remote Config) con los mismos valores por defecto.
    val remoteConfigs = LocalConfig(
        "admob_enabled" to false,
        "appbrains_enabled" to false,
        "startapp_enabled" to false,
        "appodeal_enabled" to false,
        "applovin_enabled" to true,
        "admob_use_fallback" to false,
        "ads_forced" to false,
        "ads_remote_banner" to true,
        "ads_remote_full" to true,
        "ads_remote" to 1.0,
        "admob_percent" to 100.0,
        "appodeal_percent" to 100.0,
        "applovin_percent" to 100.0,
        "appbrains_percent" to 0.0,
        "startapp_percent" to 0.0,
        "appodeal_fullscreen_percent" to 100.0,
        "applovin_fullscreen_percent" to 100.0,
        "admob_fullscreen_percent" to 100.0,
        "appbrains_fullscreen_percent" to 100.0,
        "startappp_fullscreen_percent" to 100.0,
        "appodeal_fullscreen_percent" to 100.0,
        "rewarded_percent" to 10.0,
        "interstitial_percent" to 90.0,
        "disqus_version" to "9e3da5ae8d7caf8389087c4c35a6ca1b",
        "min_version" to 169L,
        "samsung_disable_foreground" to false,
        "bypass_show_reload" to false,
        "bypass_clear_cookies" to false,
        "bypass_max_tries" to 3L,
        "bypass_skip_captcha" to true,
        "bypass_use_dialog" to true,
        "bypass_dialog_style" to 1L,
        "full_show_extra_probability" to 60.0,
        "full_show_probability" to 70.0,
        "migration_percent" to 35
    )

    // Sin anuncios: todo queda desactivado.
    val isRemoteAdsEnabled = false
    val isRemoteBannerEnabled = false
    val isRemoteFullEnabled = false
    val isAdmobEnabled = false
    val isApplovinEnabled = false

    fun setUp(context: Activity, callback: () -> Unit) = callback()
}

fun MutableList<BaseRecentAV1>.implAdsRecent() {}

fun MutableList<FavoriteObject>.implAdsFavorite() {}

fun MutableList<Achievement>.implAdsAchievement() {}

fun ViewGroup.implBannerCast() {}

fun ViewGroup.implBanner(unitID: String, isSmart: Boolean = false) {}

fun ViewGroup.implBanner(unitID: AdsType, isSmart: Boolean = false) {}

private class NoAdLoader(private val onUpdate: () -> Unit) : FullscreenAdLoader {
    override fun load() = onUpdate()
    override fun show() {}
}

fun getFAdLoaderRewarded(context: Activity, onUpdate: () -> Unit = {}): FullscreenAdLoader = NoAdLoader(onUpdate)

fun getFAdLoaderInterstitial(context: Activity, onUpdate: () -> Unit = {}): FullscreenAdLoader = NoAdLoader(onUpdate)

fun showRandomInterstitial(context: AppCompatActivity, probability: Float = 0f) {}

interface FullscreenAdLoader {
    fun load()
    fun show()
}

class LocalConfig(vararg defaults: Pair<String, Any>) {
    private val values = mapOf(*defaults)
    fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false
    fun getDouble(key: String): Double = (values[key] as? Number)?.toDouble() ?: 0.0
    fun getLong(key: String): Long = (values[key] as? Number)?.toLong() ?: 0L
    fun getString(key: String): String = values[key]?.toString() ?: ""
}
