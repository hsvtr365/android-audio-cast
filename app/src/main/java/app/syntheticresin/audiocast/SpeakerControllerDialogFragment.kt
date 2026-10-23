package app.syntheticresin.audiocast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment

class SpeakerControllerDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?): MediaRouteControllerDialog =
        object : MediaRouteControllerDialog(context) {
            override fun onCreateMediaControlView(savedInstanceState: Bundle?): View {
                val controlsContext = getContext()
                val padding = (16 * controlsContext.resources.displayMetrics.density).toInt()
                return LinearLayout(controlsContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                    addView(TextView(controlsContext).apply {
                        text = Strings.get(R.string.group_volume_hint)
                        textSize = 14f
                    })
                    addView(AppCompatButton(controlsContext).apply {
                        text = Strings.get(R.string.open_google_home)
                        isAllCaps = false
                        setOnClickListener {
                            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://support.google.com/googlehome/answer/7174267"))
                            try { context.startActivity(launch) }
                            catch (_: ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, Strings.get(R.string.app_unavailable), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
}
