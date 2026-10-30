package knf.kuma.preferences

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import knf.kuma.R
import knf.kuma.commons.EAHelper
import knf.kuma.custom.GenericActivity

/** Configuración como pantalla propia (se abre desde el Perfil); antes era una pestaña de la barra inferior. */
class SettingsActivity : GenericActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getTheme())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anilist_host)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "Configuración"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction().replace(R.id.container, BottomPreferencesMaterialFragment.get()).commit()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, SettingsActivity::class.java))
    }
}
