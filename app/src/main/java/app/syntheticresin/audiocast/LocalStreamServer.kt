package app.syntheticresin.audiocast

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Only an in-memory playlist and numbered AAC segments are served; no file access. */
class LocalStreamServer(address: String, private val token: String, private val hls: HlsStream, port: Int = 8088, private val receiverHtml: ByteArray? = null) : AutoCloseable {
    private val listener = ServerSocket().apply {
        try { reuseAddress = true; bind(java.net.InetSocketAddress(InetAddress.getByName(address), port), 8) }
        catch (e: Exception) { close(); throw e }
    }
    val port: Int get() = listener.localPort
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    val requests = AtomicLong()
    private val lastRequestLog = AtomicLong()
    @Volatile var healthy = true
        private set
    @Volatile private var closed = false
    init {
        Thread({
            try {
                while (!closed) {
                    val socket = listener.accept()
                    if (closed) { socket.close(); break }
                    clients.add(socket)
                    val deadline = try { deadlines.schedule({ Diagnostics.log("HTTP deadline peer=${socket.inetAddress.hostAddress} queue=${pool.queue.size}"); try { socket.close() } catch (_: Exception) {} }, 5, TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(socket); socket.close(); break }
                    try { pool.execute { try { serve(socket) } finally { deadline.cancel(false) } } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { Diagnostics.log("HTTP rejected queue_full peer=${socket.inetAddress.hostAddress}"); deadline.cancel(false); clients.remove(socket); socket.close() }
                }
            } catch (_: SocketException) { healthy = false }
            catch (e: Exception) { healthy = false; Diagnostics.log("HTTP accept: ${e.javaClass.simpleName}") }
        }, "hls-http").start()
    }
    private fun serve(socket: Socket) {
        val started = System.nanoTime()
        var resourceLog = "headers"
        try {
            socket.use {
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val request = StringBuilder()
                while (request.length < 8192) {
                    val value = input.read(); if (value < 0) return
                    request.append(value.toChar())
                    if (request.endsWith("\r\n\r\n")) break
                }
                if (!request.endsWith("\r\n\r\n")) return
                val first = request.toString().substringBefore("\r\n").split(' ')
                if (first.size != 3) return
                val method = first[0]; val path = first[1].substringBefore('?')
                val prefix = "/$token/"
                resourceLog = if (path == "/receiver/index.html") "receiver/index.html" else if (path.startsWith(prefix)) path.removePrefix(prefix).take(80) else "unrecognized_path"
                var code = "404 Not Found"; var mime = "text/plain"; var body = "Not found".toByteArray()
                if (method !in listOf("GET", "HEAD", "OPTIONS")) { code = "405 Method Not Allowed"; body = ByteArray(0) }
                else if (path == "/receiver/index.html" && receiverHtml != null) {
                    code = "200 OK"; mime = "text/html; charset=utf-8"; body = receiverHtml
                }
                else if (path.startsWith(prefix)) {
                    if (method == "OPTIONS") { code = "204 No Content"; body = ByteArray(0) }
                    else when (val resource = path.removePrefix(prefix)) {
                        "master.m3u8" -> {
                            code = "200 OK"; mime = "application/vnd.apple.mpegurl"
                            body = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS=\"mp4a.40.2\"\nlive.m3u8\n".toByteArray(Charsets.UTF_8)
                        }
                        "live.m3u8" -> {
                            val playlist = hls.playlist()
                            if (playlist == null) { code = "503 Service Unavailable"; body = "Buffering".toByteArray() }
                            else { code = "200 OK"; mime = "application/vnd.apple.mpegurl"; body = playlist }
                        }
                        else -> if (resource.matches(Regex("seg-[0-9]{1,12}\\.aac"))) {
                            val segment = hls.segment(resource.removePrefix("seg-").removeSuffix(".aac").toLong())
                            if (segment != null) { code = "200 OK"; mime = "audio/aac"; body = segment }
                        }
                    }
                }
                requests.incrementAndGet()
                val header = "HTTP/1.1 $code\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nRetry-After: 2\r\nX-Content-Type-Options: nosniff\r\n\r\n"
                socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); if (method != "HEAD") write(body); flush() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val now = System.currentTimeMillis()
                val previous = lastRequestLog.get()
                if (requests.get() <= 24 || code != "200 OK" || elapsedMs >= 1000 || (resourceLog == "live.m3u8" && now - previous >= 10000 && lastRequestLog.compareAndSet(previous, now))) {
                    Diagnostics.log("HTTP peer=${socket.inetAddress.hostAddress} $method $resourceLog status=$code bytes=${body.size} elapsedMs=$elapsedMs latest=${hls.latestSequence()} retained=${hls.retainedCount()} requests=${requests.get()}")
                }
            }
        } catch (e: java.io.IOException) { if (!closed) Diagnostics.error("HTTP peer=${socket.inetAddress.hostAddress} resource=$resourceLog elapsedMs=${(System.nanoTime() - started) / 1_000_000}", e) }
        finally { clients.remove(socket); try { socket.close() } catch (_: Exception) {} }
    }
    override fun close() {
        closed = true; healthy = false
        try { listener.close() } catch (_: java.io.IOException) {}
        clients.forEach { try { it.close() } catch (_: Exception) {} }; pool.shutdownNow(); deadlines.shutdownNow()
    }
}
