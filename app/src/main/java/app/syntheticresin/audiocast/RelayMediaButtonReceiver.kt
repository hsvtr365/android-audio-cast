package app.syntheticresin.audiocast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

// Relay controls expose Stop only; media buttons must never start audio capture.
class RelayMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON || !LiveService.running) return
        val key = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
        if (key.action == KeyEvent.ACTION_DOWN && key.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            context.startService(Intent(context, LiveService::class.java).setAction("STOP"))
        }
    }
}
