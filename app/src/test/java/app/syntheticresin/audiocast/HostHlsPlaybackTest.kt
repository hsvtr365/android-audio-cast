package app.syntheticresin.audiocast

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Optional real FFmpeg decoder test against the production playlist/HTTP classes. */
class HostHlsPlaybackTest {
    @Test fun decoderFollowsLivePlaylist() {
        val fixture = System.getProperty("relay.fixture", "") ?: ""
        assumeTrue("Set -PrelayFixture=<ADTS AAC fixture> for the decoder test", fixture.isNotEmpty())
        val source = File(fixture).readBytes()
        var end = 0; var frames = 0
        while (frames < 188 && end + 7 < source.size) {
            require(source[end].toInt() and 255 == 255 && source[end + 1].toInt() and 240 == 240)
            val length = ((source[end+3].toInt() and 3) shl 11) or ((source[end+4].toInt() and 255) shl 3) or ((source[end+5].toInt() and 224) shr 5)
            require(length >= 7 && end + length <= source.size)
            end += length; frames++
        }
        assertEquals(188, frames)
        val segment = source.copyOfRange(0, end)
        val hls = HlsStream()
        repeat(4) { hls.publish(segment, 188, it * 188L * 1024 * 1_000_000 / 48000) }
        val active = AtomicBoolean(true)
        val producer = Thread {
            var sequence = 4L
            try {
                while (active.get()) {
                    Thread.sleep(4011)
                    if (active.get()) hls.publish(segment, 188, sequence++ * 188 * 1024 * 1_000_000 / 48000)
                }
            } catch (_: InterruptedException) {}
        }
        val log = File(fixture + ".decode.log")
        LocalStreamServer("127.0.0.1", "host-probe", hls, 0).use { server ->
            producer.start()
            val process = ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "info", "-rw_timeout", "5000000",
                "-i", "http://127.0.0.1:${server.port}/host-probe/live.m3u8", "-t", "20", "-vn", "-f", "null", "-")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue("Decoder timed out: $log", process.waitFor(55, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("Decoder failed: ${log.readText()}", 0, process.exitValue())
                val result = log.readText()
                assertTrue(result.contains("48000 Hz") && result.contains("stereo"))
                assertTrue(result.contains("time=00:00:20"))
                assertTrue(server.requests.get() >= 8)
                assertTrue(hls.latestSequence() >= 5)
            } finally { process.destroyForcibly(); active.set(false); producer.interrupt(); producer.join(2000) }
        }
    }
}
