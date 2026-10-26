package knf.kuma.ads

import knf.kuma.BuildConfig

// Sin anuncios: solo se conservan los IDs que aún referencian los adapters.
object AdsUtilsMob {
    val RECENT_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.RECENT_BANNER
    val RECENT_BANNER2 get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.RECENT_BANNER2
    val FAVORITE_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.FAVORITE_BANNER
    val FAVORITE_BANNER2 get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.FAVORITE_BANNER2
    val DIRECTORY_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.DIRECTORY_BANNER
    val HOME_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.HOME_BANNER
    val HOME_BANNER2 get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.HOME_BANNER2
    val EMISSION_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.EMISSION_BANNER
    val SEEING_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.SEEING_BANNER
    val RECOMMEND_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.RECOMMEND_BANNER
    val QUEUE_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.QUEUE_BANNER
    val RECORD_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.RECORD_BANNER
    val RANDOM_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.RANDOM_BANNER
    val NEWS_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.NEWS_BANNER
    val INFO_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.INFO_BANNER
    val ACHIEVEMENT_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.ACHIEVEMENT_BANNER
    val EXPLORER_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.EXPLORER_BANNER
    val CAST_BANNER get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else AdmobID.CAST_BANNER
    val LIST_NATIVE get() = if (BuildConfig.DEBUG) "ca-app-pub-5390653757953587/5447863415" else AdmobID.LIST_NATIVE
    val REWARDED get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/5224354917" else AdmobID.REWARDED
    val INTERSTITIAL get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/1033173712" else AdmobID.INTERSTITIAL

    //val adRequest: AdRequest get() = AdRequest.Builder().build()
    val ACHIEVEMENT_NATIVE = "achievement_native"
}

object AdmobID {
    const val RECENT_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val RECENT_BANNER2 = "ca-app-pub-8896186846999141/5663284088"
    const val FAVORITE_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val FAVORITE_BANNER2 = "ca-app-pub-8896186846999141/5663284088"
    const val DIRECTORY_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val HOME_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val HOME_BANNER2 = "ca-app-pub-8896186846999141/5663284088"
    const val EMISSION_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val SEEING_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val RECOMMEND_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val QUEUE_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val RECORD_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val RANDOM_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val NEWS_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val INFO_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val ACHIEVEMENT_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val EXPLORER_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val CAST_BANNER = "ca-app-pub-8896186846999141/5663284088"
    const val LIST_NATIVE = "ca-app-pub-8896186846999141/5113437989"
    const val REWARDED = "ca-app-pub-8896186846999141/9219385713"
    const val INTERSTITIAL = "ca-app-pub-8896186846999141/7231764047"
}