package app.syntheticresin.audiocast

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.*
import android.os.*
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

class CaptureService : Service() {
    companion object {
        @Volatile var status = "대기 중"
        @Volatile var running = false
    }
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { running = false; if (worker == null) stopSelf(); return START_NOT_STICKY }
        if (worker != null) return START_NOT_STICKY
        try {
            val consent = intent?.getParcelableExtra<Intent>("consent") ?: run { stopSelf(); return START_NOT_STICKY }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("capture", "오디오 캡처", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_relay_notification)
                .setContentTitle("미디어 오디오 캡처 중").setContentText("최대 12분 · 누르면 앱 열기")
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .addAction(Notification.Action.Builder(null, "중지", stop).build()).setOngoing(true).build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("code", Activity.RESULT_OK), consent)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running = false; Log.i("CastPoc", "MediaProjection revoked/stopped") }
            }, Handler(Looper.getMainLooper()))
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastPoc:capture").apply { acquire(13 * 60 * 1000L) }
            running = true
            worker = Thread { capture() }.apply { start() }
        } catch (e: Exception) { status = "시작 오류: ${e.message}"; Log.e("CastPoc", status, e); stopSelf() }
        return START_NOT_STICKY
    }
    private fun capture() {
        var recorder: AudioRecord? = null
        var output: RandomAccessFile? = null
        var bytes = 0L
        val file = File(filesDir, "capture-${System.currentTimeMillis()}.wav")
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("오디오 캡처 권한이 없습니다")
            }
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val size = max(19200, AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))
            recorder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(size * 2).setAudioPlaybackCaptureConfig(config).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }
            output = RandomAccessFile(file, "rw"); output.write(ByteArray(44))
            val samples = ShortArray(size / 2); val encoded = ByteArray(size)
            var last = SystemClock.elapsedRealtime(); val started = last
            var total = 0L; var nonzero = 0L; var peak = 0; var energy = 0.0; var window = 0L
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            while (running && SystemClock.elapsedRealtime() - started < 12 * 60 * 1000L) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                check(count > 0) { "AudioRecord read=$count" }
                for (i in 0 until count) {
                    val value = samples[i].toInt(); if (value != 0) nonzero++
                    peak = max(peak, abs(value)); energy += value.toDouble() * value; window++
                    encoded[i * 2] = value.toByte(); encoded[i * 2 + 1] = (value shr 8).toByte()
                }
                output.write(encoded, 0, count * 2); bytes += count * 2; total += count
                val now = SystemClock.elapsedRealtime()
                if (now - last >= 1000) {
                    val db = if (energy == 0.0) -120.0 else 20 * log10(sqrt(energy / window) / 32768)
                    status = "캡처 중 ${(now-started)/1000}초\nRMS %.1f dBFS · peak %d\n비영점 샘플 %d / %d\n%s".format(db, peak, nonzero, total, file.name)
                    Log.i("CastPoc", status.replace('\n', ' ')); last = now; peak = 0; energy = 0.0; window = 0
                }
            }
            status = "저장 완료: ${file.name}\n${bytes / 192000.0}초 · $bytes bytes\n비영점 샘플 $nonzero / $total\n무음만으로 원본 앱 차단을 단정할 수 없습니다."
        } catch (e: Exception) { status = "캡처 오류: ${e.message}"; Log.e("CastPoc", status, e) }
        finally {
            running = false
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            try { output?.let { writeHeader(it, bytes) } } finally { output?.close() }
            Log.i("CastPoc", status.replace('\n', ' '))
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }
    private fun writeHeader(out: RandomAccessFile, bytes: Long) {
        fun le(value: Long, size: Int) { repeat(size) { out.write((value shr (8 * it)).toInt() and 255) } }
        out.seek(0); out.writeBytes("RIFF"); le(bytes + 36, 4); out.writeBytes("WAVEfmt "); le(16, 4); le(1, 2); le(2, 2)
        le(48000, 4); le(192000, 4); le(4, 2); le(16, 2); out.writeBytes("data"); le(bytes, 4)
    }
    override fun onDestroy() {
        running = false; projection?.stop(); if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
