package app.syntheticresin.audiocast

import android.content.Context
import android.os.SystemClock
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.util.concurrent.Executors

object CastController {
    var context: CastContext? = null
        private set
    private var statusText: () -> String = { Strings.get(R.string.cast_waiting) }
    val status: String get() = statusText()
    private var deviceName: String? = null
    val device: String get() = deviceName ?: Strings.get(R.string.not_selected)
    val isConnected: Boolean get() = context?.sessionManager?.currentCastSession?.isConnected == true
    val isConnecting: Boolean get() = context?.sessionManager?.currentCastSession?.isConnecting == true
    private var loadedUrl: String? = null
    private var ownedUrl: String? = null
    private var bufferingSince = 0L
    private var lastAttempt = 0L
    private var app: Context? = null
    private var router: MediaRouter? = null
    private var autoTried = false
    private var discovering = false
    private var discoveryWanted = false
    private var initializing = false
    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { tryAuto() }
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { tryAuto() }
    }
    fun init(context: Context) {
        if (this.context != null || initializing) return
        app = context.applicationContext
        initializing = true
        val executor = Executors.newSingleThreadExecutor()
        try {
            CastContext.getSharedInstance(context.applicationContext, executor)
                .addOnSuccessListener { cast ->
                    this.context = cast; router = MediaRouter.getInstance(context.applicationContext)
                    cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                    statusText = { Strings.get(R.string.cast_disconnected) }
                    discovery(discoveryWanted)
                }.addOnFailureListener { error -> statusText = { Strings.get(R.string.cast_init_failed) }; Diagnostics.log("Cast init: ${error.javaClass.simpleName}") }
                .addOnCompleteListener { initializing = false; executor.shutdown() }
        } catch (e: Exception) { initializing = false; executor.shutdown(); statusText = { Strings.get(R.string.cast_init_failed) }; Diagnostics.log("Cast init: ${e.javaClass.simpleName}") }
    }
    fun discovery(enabled: Boolean) {
        discoveryWanted = enabled
        val cast = context ?: return
        val selector = cast.mergedSelector ?: return
        val mediaRouter = router ?: return
        if (enabled && !discovering) {
            autoTried = false
            discovering = true
            mediaRouter.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            tryAuto()
        } else if (!enabled && discovering) { discovering = false; mediaRouter.removeCallback(routeCallback) }
    }
    private fun tryAuto() {
        val prefs = app?.getSharedPreferences("settings", 0) ?: return
        if (autoTried || !prefs.getBoolean("remember", true) || !prefs.getBoolean("auto", false)) return
        if (context?.sessionManager?.currentCastSession?.isConnected == true) return
        val saved = prefs.getString("routeId", null) ?: return
        val selector = context?.mergedSelector ?: return
        val route = router?.routes?.firstOrNull { it.id == saved && it.isEnabled && it.matchesSelector(selector) } ?: return
        autoTried = true; statusText = { Strings.get(R.string.auto_attempt,route.name) }; route.select()
    }
    fun streamChanged() { loadedUrl = null; lastAttempt = 0; bufferingSince = 0 }
    fun tick(url: String?, ready: Boolean) {
        val session = context?.sessionManager?.currentCastSession ?: return
        if (!session.isConnected || !ready || url == null) return
        val remote = session.remoteMediaClient ?: return
        val media = remote.mediaStatus
        if (media?.playerState == MediaStatus.PLAYER_STATE_PLAYING) { statusText = { Strings.get(R.string.cast_playing) }; bufferingSince = 0 }
        else if (media?.playerState == MediaStatus.PLAYER_STATE_BUFFERING) {
            statusText = { Strings.get(R.string.cast_buffering) }
            if (bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - bufferingSince > 30000) { loadedUrl = null; bufferingSince = 0 }
        }
        if (loadedUrl == url && media?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            media.idleReason in listOf(MediaStatus.IDLE_REASON_ERROR, MediaStatus.IDLE_REASON_FINISHED)) loadedUrl = null
        if (loadedUrl == url || SystemClock.elapsedRealtime() - lastAttempt < 10000) return
        lastAttempt = SystemClock.elapsedRealtime(); loadedUrl = url; ownedUrl = url
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply { putString(MediaMetadata.KEY_TITLE, Strings.get(R.string.cast_title)) }
        val info = MediaInfo.Builder(url).setContentType("application/vnd.apple.mpegurl")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE).setHlsSegmentFormat(HlsSegmentFormat.AAC).setMetadata(metadata).build()
        statusText = { Strings.get(R.string.cast_loading) }
        try {
            remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()).setResultCallback { result ->
                if (loadedUrl != url || !LiveService.running) return@setResultCallback
                if (result.status.isSuccess) { statusText = { Strings.get(R.string.cast_accepted) }; Diagnostics.log("Cast load accepted") }
                else { loadedUrl = null; statusText = { Strings.get(R.string.cast_load_failed) }; Diagnostics.log("Cast load failed ${result.status.statusCode}") }
            }
        } catch (e: Exception) { loadedUrl = null; statusText = { Strings.get(R.string.cast_retry) }; Diagnostics.log("Cast load: ${e.javaClass.simpleName}") }
    }
    fun stopMedia() {
        val remote = context?.sessionManager?.currentCastSession?.remoteMediaClient
        try { if (ownedUrl != null && remote?.mediaInfo?.contentId == ownedUrl) remote?.stop() }
        catch (e: Exception) { Diagnostics.log("Cast stop: ${e.javaClass.simpleName}") }
        loadedUrl = null; ownedUrl = null; bufferingSince = 0
    }
    private fun connected(session: CastSession) {
        loadedUrl = null; ownedUrl = null; bufferingSince = 0; deviceName = session.castDevice?.friendlyName ?: Strings.get(R.string.cast_device)
        statusText = { Strings.get(R.string.cast_connected) }
        val prefs = app?.getSharedPreferences("settings", 0)
        if (prefs?.getBoolean("remember", true) == true) {
            prefs.edit().putString("routeId", router?.selectedRoute?.id).putString("routeName", device).apply()
        }
        Diagnostics.log("Cast session connected")
        tick(LiveService.streamUrl, LiveService.ready)
    }
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) { statusText = { Strings.get(R.string.cast_connecting) } }
        override fun onSessionStarted(session: CastSession, id: String) { connected(session) }
        override fun onSessionStartFailed(session: CastSession, error: Int) { statusText = { Strings.get(R.string.cast_connect_failed,error) }; Diagnostics.log(status) }
        override fun onSessionEnding(session: CastSession) { statusText = { Strings.get(R.string.cast_ending) } }
        override fun onSessionEnded(session: CastSession, error: Int) { loadedUrl = null; ownedUrl = null; statusText = { Strings.get(R.string.cast_ended,error) }; deviceName = null }
        override fun onSessionResuming(session: CastSession, id: String) { statusText = { Strings.get(R.string.cast_resuming) } }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) { connected(session) }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { statusText = { Strings.get(R.string.cast_resume_failed,error) } }
        override fun onSessionSuspended(session: CastSession, reason: Int) { loadedUrl = null; statusText = { Strings.get(R.string.cast_suspended,reason) } }
    }
}
