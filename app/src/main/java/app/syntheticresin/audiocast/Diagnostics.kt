package app.syntheticresin.audiocast

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {
    private const val MAX_LINES = 1500
    private const val MAX_LINE_LENGTH = 2000
    private const val FILE_NAME = "relay-diagnostics.txt"
    // Access is protected by the same monitor as log(), since this formatter is mutable.
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private var context: Context? = null
    @Synchronized fun init(app: Context) {
        if (context != null) return
        context = app.applicationContext
        val file = File(app.filesDir, FILE_NAME)
        if (file.exists()) runCatching { lines.addAll(file.readLines().takeLast(MAX_LINES)) }
        log("Diagnostics initialized version=${app.packageManager.getPackageInfo(app.packageName, 0).versionName} Android=${android.os.Build.VERSION.RELEASE}")
    }
    @Synchronized fun log(message: String) {
        val line = timestampFormat.format(Date()) + " " + message
        lines.addLast(line.take(MAX_LINE_LENGTH))
        while (lines.size > MAX_LINES) lines.removeFirst()
        if (context != null) Log.i("CastRelay", message)
    }
    fun error(message: String, error: Throwable) {
        log("$message: ${error.javaClass.simpleName}: ${error.message} " + error.stackTrace.take(8).joinToString(" <- "))
    }
    @Synchronized fun text() = lines.joinToString("\n")
    @Synchronized fun save() { context?.let { runCatching { File(it.filesDir, FILE_NAME).writeText(text()) } } }
}
