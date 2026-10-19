package app.syntheticresin.audiocast

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil

/** RFC 8216 packed AAC: each segment starts with an ID3 transport timestamp. */
class HlsStream(bufferSeconds: Int = 15) {
    init { require(bufferSeconds in 15..30) }
    @Volatile var bufferSeconds: Int = bufferSeconds
        private set
    @Synchronized fun updateBufferSeconds(seconds:Int) {
        require(seconds in 15..30)
        bufferSeconds=seconds
    }
    data class Segment(val sequence: Long, val duration: Double, val data: ByteArray)
    private val segments = ArrayDeque<Segment>()
    private var next = 0L
    private val windowCount get() = ceil(bufferSeconds / 4.0).toInt() + 3
    private val retainCount get() = windowCount * 2 + 2
    @Volatile var ended = false
        private set
    @Synchronized fun publish(aac: ByteArray, frames: Int, ptsUs: Long) {
        require(frames > 0 && aac.isNotEmpty())
        segments.addLast(Segment(next++, frames * 1024.0 / 48000, timestamp(ptsUs) + aac))
        while (segments.size > retainCount) segments.removeFirst()
    }
    @Synchronized fun finish() { ended = true }
    @Synchronized fun ready() = segments.size >= ceil(bufferSeconds / 4.0).toInt()
    @Synchronized fun bufferDuration() = segments.takeLast(windowCount).sumOf { it.duration }
    @Synchronized fun retainedCount() = segments.size
    @Synchronized fun latestSequence() = next - 1
    @Synchronized fun segment(sequence: Long) = segments.firstOrNull { it.sequence == sequence }?.data
    @Synchronized fun playlist(): ByteArray? {
        if (!ready()) return null
        val visible = segments.takeLast(windowCount)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n")
            append("#EXT-X-MEDIA-SEQUENCE:${visible.first().sequence}\n")
            append("#EXT-X-START:TIME-OFFSET=-${bufferSeconds.toDouble()},PRECISE=NO\n")
            visible.forEach { append(String.format(Locale.US, "#EXTINF:%.6f,\nseg-%d.aac\n", it.duration, it.sequence)) }
            if (ended) append("#EXT-X-ENDLIST\n")
        }.toByteArray(Charsets.UTF_8)
    }
    companion object {
        fun adts(raw: ByteArray): ByteArray {
            val size = raw.size + 7
            require(size in 8..8191)
            return byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x4c, (0x80 or (size shr 11)).toByte(),
                (size shr 3).toByte(), (((size and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte()) + raw
        }
        private fun syncsafe(value: Int) = byteArrayOf(((value shr 21) and 127).toByte(),
            ((value shr 14) and 127).toByte(), ((value shr 7) and 127).toByte(), (value and 127).toByte())
        fun timestamp(ptsUs: Long): ByteArray {
            val payload = ByteArrayOutputStream()
            payload.write("com.apple.streaming.transportStreamTimestamp".toByteArray(Charsets.US_ASCII))
            payload.write(0)
            val ticks = (ptsUs * 9 / 100) and 0x1ffffffffL
            for (i in 7 downTo 0) payload.write(((ticks shr (i * 8)) and 255).toInt())
            val frame = "PRIV".toByteArray() + syncsafe(payload.size()) + byteArrayOf(0, 0) + payload.toByteArray()
            return byteArrayOf(73, 68, 51, 4, 0, 0) + syncsafe(frame.size) + frame
        }
    }
}
