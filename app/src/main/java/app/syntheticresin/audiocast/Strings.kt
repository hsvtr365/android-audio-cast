package app.syntheticresin.audiocast

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate(); Strings.app = this
        val saved=getSharedPreferences("settings",0).getInt("theme",AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(if(saved in intArrayOf(-1,1,2)) saved else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}

object Strings {
    lateinit var app: Context
    fun get(id: Int, vararg args: Any): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val context = if (locales.isEmpty) app else app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(locales.toLanguageTags())) }
        )
        return context.getString(id, *args)
    }
}
