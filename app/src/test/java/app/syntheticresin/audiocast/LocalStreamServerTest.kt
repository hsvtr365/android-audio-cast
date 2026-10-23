package app.syntheticresin.audiocast

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LocalStreamServerTest {
    @Test fun slowClientIsClosedAndPortCanBeReused() {
        val hls = HlsStream()
        val first = LocalStreamServer("127.0.0.1", "secret", hls, 0)
        val port = first.port
        try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 7000
                socket.getOutputStream().write("GET /secret/live.m3u8 HTTP/1.1\r\n".toByteArray())
                val started = System.nanoTime()
                assertEquals(-1, socket.getInputStream().read())
                assertTrue((System.nanoTime() - started) / 1_000_000 < 6500)
            }
        } finally { first.close() }
        LocalStreamServer("127.0.0.1", "replacement", hls, port).use { replacement -> assertTrue(replacement.healthy) }
    }
    @Test fun readinessAuthorizationAndHead() {
        val hls = HlsStream()
        LocalStreamServer("127.0.0.1", "secret", hls, 0).use { server ->
            fun request(path: String, method: String = "GET"): Pair<Int, ByteArray> {
                val connection = URL("http://127.0.0.1:${server.port}/$path").openConnection() as HttpURLConnection
                connection.requestMethod = method; connection.connectTimeout = 2000; connection.readTimeout = 2000
                try { val code = connection.responseCode; return code to ((if (code < 400) connection.inputStream else connection.errorStream)?.readBytes() ?: ByteArray(0)) }
                finally { connection.disconnect() }
            }
            assertEquals(404, request("other/live.m3u8").first)
            assertEquals(503, request("secret/live.m3u8").first)
            repeat(6) { hls.publish(HlsStream.adts(byteArrayOf(1,2,3)), 47, it * 1002667L) }
            val playlist = request("secret/live.m3u8")
            assertEquals(200, playlist.first); assertTrue(String(playlist.second).startsWith("#EXTM3U"))
            assertEquals(200, request("secret/seg-0.aac").first)
            assertEquals(0, request("secret/seg-0.aac", "HEAD").second.size)
            assertEquals(404, request("secret/../files/private.wav").first)
            assertEquals(405, request("secret/live.m3u8", "POST").first)
        }
    }
}
