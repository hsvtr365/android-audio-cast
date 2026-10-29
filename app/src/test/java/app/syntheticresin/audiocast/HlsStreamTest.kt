package app.syntheticresin.audiocast

import org.junit.Assert.*
import org.junit.Test

class HlsStreamTest {
    @Test fun customReceiverCanFetchAtThreeSecondsWithoutChangingTarget() {
        val hls = HlsStream(6, fastStartup = true)
        repeat(2) { hls.publish(byteArrayOf(1), 47, it * 1002667L) }
        assertFalse(hls.ready()); assertNull(hls.playlist())
        hls.publish(byteArrayOf(1), 47, 2 * 1002667L)
        assertTrue(hls.ready())
        assertTrue(String(hls.playlist()!!).contains("TIME-OFFSET=-6.0"))
        hls.updateBufferSeconds(15)
        assertFalse(hls.ready())
    }
    @Test fun shorterBufferStartsAfterSixSegments() {
        val hls = HlsStream(6)
        repeat(5) { hls.publish(byteArrayOf(1),47,it*1002667L) }
        assertFalse(hls.ready())
        hls.publish(byteArrayOf(1),47,5*1002667L)
        assertTrue(hls.ready())
        assertTrue(hls.startPositionMs() in 0L..20L)
        val playlist=String(hls.playlist()!!)
        assertTrue(playlist.contains("TIME-OFFSET=-6.0"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:1"))
        assertTrue(playlist.contains("#EXT-X-VERSION:6"))
        // RFC 8216 v6 rounds EXTINF for TARGETDURATION. Cast keeps 3 targets behind live.
        assertTrue(kotlin.math.round(HlsStream.SEGMENT_SECONDS) <= 1.0)
        assertTrue(6 * HlsStream.SEGMENT_SECONDS - 3.0 >= 3.0)
    }
    @Test fun longerBufferHasBoundedRetentionAndStartup() {
        val hls = HlsStream(30)
        repeat(29) { hls.publish(byteArrayOf(1), 47, it * 1002667L) }
        assertFalse(hls.ready())
        hls.publish(byteArrayOf(1), 47, 29 * 1002667L)
        assertTrue(hls.ready())
        repeat(100) { hls.publish(byteArrayOf(1), 47, (it + 30) * 1002667L) }
        assertEquals(68, hls.retainedCount())
        assertEquals(33, Regex("#EXTINF:").findAll(String(hls.playlist()!!)).count())
        assertTrue(hls.bufferDuration() < 45)
    }
    @Test fun bufferChangesUpdateLivePlaylistAndReadiness() {
        val hls=HlsStream(6)
        repeat(6) { hls.publish(byteArrayOf(1),47,it*1002667L) }
        assertTrue(hls.ready())
        hls.updateBufferSeconds(30)
        assertFalse(hls.ready()); assertNull(hls.playlist())
        repeat(24) { hls.publish(byteArrayOf(1),47,(it+6)*1002667L) }
        assertTrue(hls.ready())
        assertTrue(String(hls.playlist()!!).contains("TIME-OFFSET=-30.0"))
        hls.updateBufferSeconds(20)
        assertEquals(20,hls.bufferSeconds)
        assertTrue(String(hls.playlist()!!).contains("TIME-OFFSET=-20.0"))
        assertEquals(23,Regex("#EXTINF:").findAll(String(hls.playlist()!!)).count())
    }
    @Test fun shortDelayKeepsHistoryWithoutIncreasingStartupOrTarget() {
        val hls = HlsStream(6)
        repeat(90) { hls.publish(byteArrayOf(1), 47, it * 1002667L) }
        assertTrue(hls.startPositionMs() in 12000L..12100L)
        assertEquals(60, hls.retainedCount())
        assertNotNull(hls.segment(30))
        assertNull(hls.segment(29))
        val playlist = String(hls.playlist()!!)
        assertTrue(playlist.contains("TIME-OFFSET=-6.0"))
        assertTrue(playlist.contains("MEDIA-SEQUENCE:72"))
        assertEquals(18, Regex("#EXTINF:").findAll(playlist).count())
    }
    @Test fun adtsLengthAndCodec() {
        val frame = HlsStream.adts(ByteArray(500))
        assertEquals(507, frame.size)
        assertEquals(507, ((frame[3].toInt() and 3) shl 11) or ((frame[4].toInt() and 255) shl 3) or ((frame[5].toInt() and 224) shr 5))
        assertEquals(1, (frame[2].toInt() shr 6) and 3) // AAC-LC profile minus one
        assertEquals(3, (frame[2].toInt() shr 2) and 15) // 48 kHz
    }
    @Test fun timestampAndSlidingWindow() {
        val hls = HlsStream(6)
        assertNull(hls.playlist())
        repeat(6) { hls.publish(byteArrayOf(1), 47, it * 1002667L) }
        assertTrue(hls.ready())
        val first = hls.segment(0)!!
        assertEquals("ID3", first.toString(Charsets.ISO_8859_1).take(3))
        val timestamp = HlsStream.timestamp(1_000_000)
        var ticks = 0L
        timestamp.takeLast(8).forEach { ticks = (ticks shl 8) or (it.toLong() and 255) }
        assertEquals(90000L, ticks)
        repeat(50) { hls.publish(byteArrayOf(2), 47, (it + 6) * 1002667L) }
        assertEquals(56, hls.retainedCount())
        val playlist = String(hls.playlist()!!)
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:38"))
        assertEquals(18, Regex("#EXTINF:").findAll(playlist).count())
        assertNotNull(hls.segment(0))
        assertNotNull(hls.segment(46)) // recently removed from playlist, still downloadable
        assertFalse(playlist.contains("ENDLIST"))
        hls.finish(); assertTrue(String(hls.playlist()!!).contains("ENDLIST"))
    }
}
