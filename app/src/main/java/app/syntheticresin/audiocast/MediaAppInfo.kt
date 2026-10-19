package app.syntheticresin.audiocast

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

// Access is used only for MediaSessionManager; notification content is not read.
class MediaAccessService : NotificationListenerService()

object MediaAppInfo {
    data class Candidate(val packageName: String, val playing: Boolean, val local: Boolean)
    fun playingPackages(candidates: List<Candidate>): List<String> = candidates
        .filter { it.playing && it.local }.map { it.packageName }.distinct()

    fun allowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java))

    fun packages(context: Context): List<String> {
        if (!allowed(context)) return emptyList()
        return try {
            val controllers=context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context,MediaAccessService::class.java))
            playingPackages(controllers.filter { it.packageName!=context.packageName }.map {
                Candidate(it.packageName,it.playbackState?.state==PlaybackState.STATE_PLAYING,
                    it.playbackInfo?.playbackType==MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
            })
        } catch (_: SecurityException) { emptyList() }
    }
}
