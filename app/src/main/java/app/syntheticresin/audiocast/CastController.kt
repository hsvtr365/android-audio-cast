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
    private val recovery = CastRecovery()
    private var loadGeneration = 0L
    private var lastAttempt = 0L
    private var lastDiagnosticState = ""
    private var lastHeartbeat = 0L
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
    fun streamChanged() { recovery.reset(); loadedUrl = null; lastAttempt = 0; bufferingSince = 0 }
    fun tick(url: String?, ready: Boolean) {
        val session = context?.sessionManager?.currentCastSession ?: return
        if (!session.isConnected || url == null || !ready) return
        val remote = session.remoteMediaClient ?: return
        val media = remote.mediaStatus
        val now = SystemClock.elapsedRealtime()
        val state = "connected=${session.isConnected} player=${media?.playerState} idle=${media?.idleReason} owned=${remote.mediaInfo?.contentId == ownedUrl}"
        if (state != lastDiagnosticState || now - lastHeartbeat >= 10000) {
            Diagnostics.log("Cast state $state positionMs=${remote.approximateStreamPosition} bufferingMs=${if (bufferingSince == 0L) 0 else now - bufferingSince} loaded=${loadedUrl != null}")
            lastDiagnosticState = state; lastHeartbeat = now
        }
        if (media?.playerState == MediaStatus.PLAYER_STATE_PLAYING) { statusText = { Strings.get(R.string.cast_playing) }; bufferingSince = 0 }
        else if (media?.playerState == MediaStatus.PLAYER_STATE_BUFFERING) {
            statusText = { Strings.get(R.string.cast_buffering) }
            if (bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()

        }
        // Do not reclaim another app's media or a stream the user paused.
        if (loadedUrl == url && remote.mediaInfo?.contentId != ownedUrl && remote.mediaInfo != null) return
        if (recovery.shouldRetry(now,
                media?.playerState == MediaStatus.PLAYER_STATE_PLAYING,
                media?.playerState == MediaStatus.PLAYER_STATE_BUFFERING)) {
            Diagnostics.log("Cast retry reason=repeated_buffering fresh_live_url=true")
            loadedUrl = null
        }
        if (loadedUrl == url && media?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            media.idleReason in listOf(MediaStatus.IDLE_REASON_ERROR, MediaStatus.IDLE_REASON_FINISHED)) { Diagnostics.log("Cast retry reason=idle_${media.idleReason}"); loadedUrl = null }
        if (loadedUrl == url || SystemClock.elapsedRealtime() - lastAttempt < 10000) return
        Diagnostics.log("Cast load attempt device=$device ready=$ready previousLoaded=${loadedUrl != null} player=${media?.playerState}")
        lastAttempt = SystemClock.elapsedRealtime(); loadedUrl = url
        val generation = ++loadGeneration
        val requestUrl = "${url.substringBeforeLast('/')}/master.m3u8?attempt=$generation"
        ownedUrl = requestUrl
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply { putString(MediaMetadata.KEY_TITLE, Strings.get(R.string.cast_title)) }
        val info = MediaInfo.Builder(requestUrl).setContentType("application/vnd.apple.mpegurl")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE).setHlsSegmentFormat(HlsSegmentFormat.AAC).setMetadata(metadata).build()
        statusText = { Strings.get(R.string.cast_loading) }
        try {
            remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()).setResultCallback { result ->
                if (generation != loadGeneration || loadedUrl != url || !LiveService.running) return@setResultCallback
                if (result.status.isSuccess) { statusText = { Strings.get(R.string.cast_accepted) }; Diagnostics.log("Cast load accepted (awaiting receiver PLAYING)") }
                else { loadedUrl = null; statusText = { Strings.get(R.string.cast_load_failed) }; Diagnostics.log("Cast load failed code=${result.status.statusCode} message=${result.status.statusMessage}") }
            }
        } catch (e: Exception) { loadedUrl = null; statusText = { Strings.get(R.string.cast_retry) }; Diagnostics.error("Cast load", e) }
    }
    fun stopMedia() {
        val remote = context?.sessionManager?.currentCastSession?.remoteMediaClient
        try { if (ownedUrl != null && remote?.mediaInfo?.contentId == ownedUrl) remote?.stop() }
        catch (e: Exception) { Diagnostics.log("Cast stop: ${e.javaClass.simpleName}") }
        recovery.reset(); loadGeneration++; loadedUrl = null; ownedUrl = null; bufferingSince = 0
    }
    private fun connected(session: CastSession) {
        recovery.reset(); loadGeneration++; loadedUrl = null; ownedUrl = null; bufferingSince = 0; deviceName = session.castDevice?.friendlyName ?: Strings.get(R.string.cast_device)
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
        override fun onSessionStarted(session: CastSession, id: String) { Diagnostics.log("Cast session started id=$id"); connected(session) }
        override fun onSessionStartFailed(session: CastSession, error: Int) { statusText = { Strings.get(R.string.cast_connect_failed,error) }; Diagnostics.log(status) }
        override fun onSessionEnding(session: CastSession) { Diagnostics.log("Cast session ending"); statusText = { Strings.get(R.string.cast_ending) } }
        override fun onSessionEnded(session: CastSession, error: Int) { Diagnostics.log("Cast session ended error=$error"); loadedUrl = null; ownedUrl = null; statusText = { Strings.get(R.string.cast_ended,error) }; deviceName = null }
        override fun onSessionResuming(session: CastSession, id: String) { statusText = { Strings.get(R.string.cast_resuming) } }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) { Diagnostics.log("Cast session resumed suspended=$suspended"); connected(session) }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { Diagnostics.log("Cast resume failed error=$error"); statusText = { Strings.get(R.string.cast_resume_failed,error) } }
        override fun onSessionSuspended(session: CastSession, reason: Int) { Diagnostics.log("Cast session suspended reason=$reason"); loadedUrl = null; statusText = { Strings.get(R.string.cast_suspended,reason) } }
    }
}
