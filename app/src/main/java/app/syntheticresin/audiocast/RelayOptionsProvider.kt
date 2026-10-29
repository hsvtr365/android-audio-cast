package app.syntheticresin.audiocast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

class RelayOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(context.getString(R.string.cast_receiver_app_id))
        .setResumeSavedSession(context.getSharedPreferences("settings", 0).let { it.getBoolean("auto", false) && it.getBoolean("remember", true) })
        // Expose Cast playback to system routing; our service owns the stop notification.
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(true).build())
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
