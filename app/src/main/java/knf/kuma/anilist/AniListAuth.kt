package knf.kuma.anilist

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import knf.kuma.App
import knf.kuma.R
import knf.kuma.commons.toast
import knf.kuma.BuildConfig
import knf.kuma.commons.noCrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object AniListAuth {

    const val REDIRECT_URI = "ukiku://anilist-callback"
    private const val AUTH_URL = "https://anilist.co/api/v2/oauth/authorize"
    const val API_URL = "https://graphql.anilist.co"

    private const val KEY_TOKEN = "access_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_PENDING_STATE = "pending_state"
    private const val KEY_PENDING_UNTIL = "pending_until"
    private const val LOGIN_WINDOW_MS = 10 * 60 * 1000L
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_BANNER = "banner_url"

    val client: OkHttpClient by lazy {
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(SniSocketFactory(sslContext.socketFactory), trustManager)
            .build()
    }

    val userName = MutableLiveData<String?>()

    val avatarUrl = MutableLiveData<String?>()

    /** Banner (portada) del perfil de AniList; null si no tiene. */
    val bannerUrl = MutableLiveData<String?>()

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "anilist_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            App.context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        noCrash {
            userName.postValue(if (isLoggedIn) prefs.getString(KEY_USER_NAME, null) else null)
            avatarUrl.postValue(if (isLoggedIn) prefs.getString(KEY_AVATAR, null) else null)
            bannerUrl.postValue(if (isLoggedIn) prefs.getString(KEY_BANNER, null) else null)
        }
    }

    val token: String?
        get() {
            if (!isLoggedIn && prefs.getString(KEY_TOKEN, null) != null) {
                // Caducó (1 año): se cierra la sesión y se avisa, en vez de que los scrobbles fallen en silencio.
                logout()
                App.context.getString(R.string.anilist_session_expired).toast()
            }
            return if (isLoggedIn) prefs.getString(KEY_TOKEN, null) else null
        }

    val userId: Int
        get() = prefs.getInt(KEY_USER_ID, -1)

    val isLoggedIn: Boolean
        get() = prefs.getString(KEY_TOKEN, null) != null &&
                System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, Long.MAX_VALUE)

    val isConfigured: Boolean
        get() = BuildConfig.ANILIST_CLIENT_ID.isNotBlank()

    fun launchLogin(context: Context) {
        // Login de un solo uso: sin un login iniciado desde la app, el callback se rechaza (Login CSRF).
        // Se guarda en prefs porque Android puede matar la app mientras el usuario está en el navegador.
        val state = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit {
            putString(KEY_PENDING_STATE, state)
            putLong(KEY_PENDING_UNTIL, System.currentTimeMillis() + LOGIN_WINDOW_MS)
        }
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.ANILIST_CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("state", state)
            .build()
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    /** Implicit grant: ukiku://anilist-callback#access_token=...&expires_in=... */
    fun parseCallback(uri: Uri): Boolean {
        val expected = prefs.getString(KEY_PENDING_STATE, null)
        val inWindow = System.currentTimeMillis() <= prefs.getLong(KEY_PENDING_UNTIL, 0)
        prefs.edit { remove(KEY_PENDING_STATE); remove(KEY_PENDING_UNTIL) }
        if (expected == null || !inWindow) return false
        val params = Uri.parse("?" + (uri.fragment ?: return false))
        val returned = params.getQueryParameter("state") ?: uri.getQueryParameter("state")
        if (returned != null && returned != expected) return false
        val accessToken = params.getQueryParameter("access_token") ?: return false
        val expiresIn = params.getQueryParameter("expires_in")?.toLongOrNull()
        prefs.edit {
            putString(KEY_TOKEN, accessToken)
            putLong(KEY_EXPIRES, expiresIn?.let { System.currentTimeMillis() + it * 1000 } ?: Long.MAX_VALUE)
        }
        return true
    }

    suspend fun fetchViewer(): Boolean = withContext(Dispatchers.IO) {
        val data = query("query { Viewer { id name avatar { large } bannerImage } }") ?: return@withContext false
        val viewer = data.optJSONObject("Viewer") ?: return@withContext false
        val banner = if (viewer.isNull("bannerImage")) null else viewer.optString("bannerImage").takeIf { it.isNotBlank() }
        prefs.edit {
            putInt(KEY_USER_ID, viewer.getInt("id"))
            putString(KEY_USER_NAME, viewer.getString("name"))
            putString(KEY_AVATAR, viewer.optJSONObject("avatar")?.optString("large"))
            putString(KEY_BANNER, banner)
        }
        userName.postValue(viewer.getString("name"))
        avatarUrl.postValue(viewer.optJSONObject("avatar")?.optString("large"))
        bannerUrl.postValue(banner)
        true
    }

    fun logout() {
        prefs.edit { clear() }
        userName.postValue(null)
        avatarUrl.postValue(null)
        bannerUrl.postValue(null)
    }

    /** Ejecuta una query/mutación GraphQL y devuelve el objeto "data", o null si falla. Llamar fuera del hilo principal. */
    fun query(query: String, variables: JSONObject = JSONObject()): JSONObject? {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(API_URL).post(body)
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) logout()
                if (!response.isSuccessful) {
                    android.util.Log.w("AniList", "HTTP ${response.code} (${response.header("Retry-After") ?: "-"}) en ${query.take(60)}")
                    return null
                }
                JSONObject(response.body.string()).optJSONObject("data")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
