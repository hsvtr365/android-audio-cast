package app.syntheticresin.audiocast

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.*
import android.net.*
import android.os.*
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.util.UUID
import kotlin.math.*

class LiveService : Service() {
    companion object {
        @Volatile var selectedUid = -1
        @Volatile var selectedPackage: String? = null
        @Volatile var running = false
        @Volatile var ready = false
        @Volatile private var statusText: (() -> String) = { Strings.get(R.string.idle) }
        var status: String
            get() = statusText()
            private set(value) { statusText = { value } }
        @Volatile var streamUrl: String? = null
        @Volatile var networkStatus = Strings.get(R.string.network_waiting)
        @Volatile var elapsedSeconds = 0L
        @Volatile var inputDbfs = -120.0
        @Volatile var bufferedSeconds = 0.0
        @Volatile var targetDelay = 6
        @Volatile var qualityBitrate = 192000
        @Volatile var sampleMode = false
        @Volatile var captureActive = false
        @Volatile var foregroundActive = false
        val levels = AudioSpectrum(enabled=false)
        fun wifiAddress(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            return wifi?.let { cm.getLinkProperties(it)?.linkAddresses?.firstOrNull { address -> address.address is Inet4Address && !address.address.isLoopbackAddress }?.address?.hostAddress }
        }
    }
    private var relaySession: android.support.v4.media.session.MediaSessionCompat? = null
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var active = false
    private var projection: MediaProjection? = null
    private var wake: PowerManager.WakeLock? = null
    private var server: LocalStreamServer? = null
    private var address: String? = null
    private lateinit var hls: HlsStream
    private val token = UUID.randomUUID().toString().replace("-", "")
    private var callbackRegistered = false
    private var lastNetworkCheck = 0L
    private var sample = false
    private var stopReason = Strings.get(R.string.stopped)
    @Volatile private var permissionEnded = false
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { refreshNetwork() } }
        override fun onLost(network: Network) { main.post { refreshNetwork() } }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { main.post { refreshNetwork() } }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (server?.healthy != true || SystemClock.elapsedRealtime()-lastNetworkCheck>=10000) refreshNetwork()
            val requested=getSharedPreferences("settings",0).getInt("buffer",6).coerceIn(6,30)
            if(requested!=hls.bufferSeconds) {
                hls.updateBufferSeconds(requested); targetDelay=requested
                bufferedSeconds=hls.bufferDuration(); CastController.streamChanged()
                Diagnostics.log("Buffer target updated $requested seconds")
            }
            ready = hls.ready() && server?.healthy == true
            CastController.tick(streamUrl, ready)
            relaySession?.apply {
                isActive = !CastController.isConnected
                setPlaybackState(android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP)
                    .setState(if (ready) android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                        else android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING,
                        android.support.v4.media.session.PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f).build())
            }
            main.postDelayed(this, 2000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopReason = Strings.get(R.string.user_stopped); active = false; if (worker == null) stopSelf(); return START_NOT_STICKY }
        if (worker != null) return START_NOT_STICKY
        try {
            Diagnostics.init(this)
            sample = intent?.action == "SAMPLE"
            val consent = intent?.getParcelableExtra<Intent>("consent")
            if (!sample && consent == null) { stopSelf(); return START_NOT_STICKY }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("relay", Strings.get(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 20, Intent(this, LiveService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            relaySession = android.support.v4.media.session.MediaSessionCompat(this, "AudioCast relay").apply {
                setMetadata(android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                        Strings.get(if (sample) R.string.sample_notification else R.string.capture_notification)).build())
                setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                    override fun onStop() {
                        startService(Intent(this@LiveService, LiveService::class.java).setAction("STOP"))
                    }
                })
                setPlaybackState(android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP)
                    .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING,
                        android.support.v4.media.session.PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f).build())
                isActive = true
            }
            androidx.mediarouter.media.MediaRouter.getInstance(this).setMediaSessionCompat(relaySession)
            val notification = Notification.Builder(this, "relay").setSmallIcon(R.drawable.ic_relay_notification)
                .setContentTitle(if (sample) Strings.get(R.string.sample_notification) else Strings.get(R.string.capture_notification))
                .setContentText(Strings.get(R.string.notification_body))
                .setContentIntent(PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .addAction(Notification.Action.Builder(null, Strings.get(R.string.stop), stop).build())
                .setStyle(Notification.MediaStyle()
                    .setMediaSession(relaySession!!.sessionToken.token as android.media.session.MediaSession.Token)
                    .setShowActionsInCompactView(0))
                .setOngoing(true).build()
            startForeground(20, notification, if (sample) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            foregroundActive = true
            levels.clear(); captureActive = false
            if (!sample) {
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("code", Activity.RESULT_OK), consent!!)
                projection!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { if (active) { stopReason = Strings.get(R.string.projection_ended); permissionEnded = true; active = false; Diagnostics.log(stopReason) } }
                }, main)
            }
            val prefs = getSharedPreferences("settings", 0)
            hls = HlsStream(prefs.getInt("buffer", 6).coerceIn(6, 30))
            elapsedSeconds = 0; inputDbfs = -120.0; bufferedSeconds = 0.0
            targetDelay = hls.bufferSeconds; qualityBitrate = prefs.getInt("bitrate", 192000); sampleMode = sample
            active = true; running = true; ready = false; status = Strings.get(R.string.encoder_starting)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastRelay:live").apply { acquire() }
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), network)
            callbackRegistered = true
            main.post(tick)
            worker = Thread({ encode(prefs.getInt("bitrate", 192000)) }, "live-aac").apply { start() }
            Diagnostics.log("Live started source=${if (sample) "sample" else "capture"} bitrate=${prefs.getInt("bitrate", 192000)} buffer=${hls.bufferSeconds}")
        } catch (e: Exception) { status = Strings.get(R.string.start_failed,e.message ?: e.javaClass.simpleName); Diagnostics.log(status); running = false; stopSelf() }
        return START_NOT_STICKY
    }
    private fun refreshNetwork() {
        if (!active) return
        lastNetworkCheck=SystemClock.elapsedRealtime()
        val ip = wifiAddress(this)
        networkStatus = if (ip == null) Strings.get(R.string.no_wifi_ip) else "Wi-Fi $ip"
        if (ip == address && server?.healthy == true) return
        server?.close(); server = null; address = null; streamUrl = null; ready = false
        if (ip != null) {
            try {
                server = LocalStreamServer(ip, token, hls); address = ip
                streamUrl = "http://$ip:8088/$token/live.m3u8"
                CastController.streamChanged()
                Diagnostics.log("HTTP ready $streamUrl")
            } catch (e: Exception) { networkStatus = Strings.get(R.string.http_retry,e.javaClass.simpleName); Diagnostics.log(networkStatus) }
        }
    }
    private fun createCaptureRecord(uid:Int):AudioRecord {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw SecurityException(Strings.get(R.string.no_audio_permission))
        val config=AudioPlaybackCaptureConfiguration.Builder(projection!!).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        if(uid>=0)config.addMatchingUid(uid)
        val record=AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(32768,AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT)*2))
            .setAudioPlaybackCaptureConfig(config.build()).build()
        try {
            check(record.state==AudioRecord.STATE_INITIALIZED) { Strings.get(R.string.capture_init_failed) }
            record.startRecording();check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            return record
        } catch(e:Exception) { record.release();throw e }
    }
    private fun encode(bitrate: Int) {
        var codec: MediaCodec? = null
        var record: AudioRecord? = null
        var recordingUid=selectedUid
        try {
            if (!sample) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException(Strings.get(R.string.no_audio_permission))
                record = createCaptureRecord(selectedUid)
                captureActive = true
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            require(codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities?.bitrateRange?.contains(bitrate) == true) {
                Strings.get(R.string.bitrate_unsupported,bitrate/1000)
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            Diagnostics.log("AAC encoder ${codec.name}")
            val pcm = ByteArray(4096)
            var submittedFrames = 0L
            var segmentFrames = 0; var segmentPts = 0L
            var encoded = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val started = SystemClock.elapsedRealtimeNanos()
            var inputEos = false; var outputEos = false; var drainingSince = 0L
            var peak = 0; var energy = 0.0; var sampleCount = 0L; var lastStats = SystemClock.elapsedRealtime()
            var silentSince = 0L
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputEos) {
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        if (!active) {
                            codec.queueInputBuffer(index, 0, 0, submittedFrames * 1_000_000 / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true; drainingSince = SystemClock.elapsedRealtime()
                        } else {
                            val count: Int
                            if (sample) {
                                val due = started + submittedFrames * 1_000_000_000 / 48000
                                val wait = (due - SystemClock.elapsedRealtimeNanos()) / 1_000_000
                                if (wait > 0) Thread.sleep(min(wait, 100))
                                for (frame in 0 until 1024) {
                                    val t = (submittedFrames + frame) / 48000.0
                                    val amplitude = if ((t.toInt() % 20) == 19) 0 else (sin(2 * PI * 440 * t) * 7000 + sin(2 * PI * 660 * t) * 3500).toInt()
                                    for (channel in 0..1) { val pos = frame * 4 + channel * 2; pcm[pos] = amplitude.toByte(); pcm[pos+1] = (amplitude shr 8).toByte() }
                                }
                                count = pcm.size
                            } else {
                                if(recordingUid!=selectedUid) {
                                    val requested=selectedUid
                                    // Keep MediaProjection, encoder timestamps and HTTP stream alive.
                                    record?.stop();record?.release();record=null
                                    record=createCaptureRecord(requested);recordingUid=requested
                                    levels.clear()
                                    Diagnostics.log("Audio capture target updated uid=$requested")
                                }
                                count = record!!.read(pcm, 0, min(pcm.size, input.remaining()), AudioRecord.READ_BLOCKING)
                                check(count > 0 && count % 4 == 0) { Strings.get(R.string.capture_read_error,count) }
                            }
                            for (pos in 0 until count step 2) { val value = ((pcm[pos].toInt() and 255) or (pcm[pos+1].toInt() shl 8)).toShort().toInt(); peak = max(peak, abs(value)); val square = value.toDouble() * value; energy += square; sampleCount++ }
                            levels.addPcm(pcm,count)
                            input.put(pcm, 0, count)
                            codec.queueInputBuffer(index, 0, count, submittedFrames * 1_000_000 / 48000, 0)
                            submittedFrames += count / 4
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                }
                var index = codec.dequeueOutputBuffer(info, 10000)
                while (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val out = codec.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        val raw = ByteArray(info.size); out.get(raw)
                        if (segmentFrames == 0) segmentPts = info.presentationTimeUs
                        encoded.write(HlsStream.adts(raw)); segmentFrames++
                        if (segmentFrames >= HlsStream.SEGMENT_FRAMES) {
                            hls.publish(encoded.toByteArray(), segmentFrames, segmentPts)
                            encoded.reset(); segmentFrames = 0
                            Diagnostics.log("HLS sequence=${hls.latestSequence()} buffer=${"%.1f".format(java.util.Locale.US, hls.bufferDuration())} retained=${hls.retainedCount()}")
                        }
                    }
                    outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    lastProgress = SystemClock.elapsedRealtime()
                    index = codec.dequeueOutputBuffer(info, 0)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastStats >= 1000) {
                    val db = if (energy == 0.0 || sampleCount == 0L) -120.0 else 20 * log10(sqrt(energy / sampleCount) / 32768)
                    elapsedSeconds = submittedFrames / 48000; inputDbfs = db; bufferedSeconds = hls.bufferDuration()
                    if (peak == 0) { if (silentSince == 0L) silentSince = now } else silentSince = 0L
                    val warning = if (!sample && silentSince > 0 && now - silentSince > 10000) Strings.get(R.string.silence_warning) else ""
                    status = Strings.get(R.string.relay_details, if(sample) Strings.get(R.string.sample_kind) else Strings.get(R.string.capture_kind), submittedFrames/48000, bitrate/1000, db, peak, hls.bufferDuration(), hls.bufferSeconds, server?.requests?.get() ?: 0, warning)
                    lastStats = now; peak = 0; energy = 0.0; sampleCount = 0
                }
                check(!inputEos || now - drainingSince < 5000) { Strings.get(R.string.encoder_drain_timeout) }
                check(now - lastProgress < 5000) { Strings.get(R.string.encoder_stalled) }
            }
            if (segmentFrames > 0) hls.publish(encoded.toByteArray(), segmentFrames, segmentPts)
        } catch (e: Exception) {
            if (!permissionEnded) stopReason = Strings.get(R.string.relay_error,e.message ?: e.javaClass.simpleName)
            Diagnostics.log(stopReason)
        }
        finally {
            active = false; ready = false; captureActive = false; levels.clear()
            try { record?.stop() } catch (_: Exception) {}; record?.release()
            try { codec?.stop() } catch (_: Exception) {}; codec?.release()
            hls.finish(); status = stopReason; Diagnostics.log(stopReason)
            main.post { stopSelf() }
        }
    }
    override fun onDestroy() {
        active = false; running = false; ready = false; captureActive = false; foregroundActive = false; levels.clear(); main.removeCallbacks(tick)
        if (callbackRegistered) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        server?.close(); server = null; streamUrl = null
        projection?.stop(); if (wake?.isHeld == true) wake?.release()
        CastController.stopMedia()
        relaySession?.release(); relaySession = null
        Diagnostics.save()
        super.onDestroy()
    }
}
