package app.syntheticresin.audiocast

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {
    private val lines = ArrayDeque<String>()
    private var context: Context? = null
    fun init(app: Context) { context = app.applicationContext }
    @Synchronized fun log(message: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + message
        lines.addLast(line.take(500)); while (lines.size > 250) lines.removeFirst()
        Log.i("CastRelay", message)
    }
    @Synchronized fun text() = lines.joinToString("\n")
    @Synchronized fun save() { context?.let { File(it.filesDir, "relay-diagnostics.txt").writeText(text()) } }
}
