package app.carsync.music

import android.content.Context
import android.net.Uri
import java.io.File

object LocalDiagnostics {
    private fun prefs(context: Context) = context.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    private fun report(context: Context) = File(context.filesDir, "last-crash.txt")
    fun enabled(context: Context) = prefs(context).getBoolean("localDiagnostics", false)
    fun enabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("localDiagnostics", value).apply()
        if (!value) clear(context)
    }
    fun sanitize(error: Throwable): String = buildString {
        append("CarSync local diagnostics\nException: ").append(error.javaClass.name).append('\n')
        error.stackTrace.take(40).forEach { frame ->
            append(frame.className).append('.').append(frame.methodName).append(':').append(frame.lineNumber).append('\n')
        }
    }.take(16000)
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled(context)) runCatching { report(context).writeText(sanitize(error)) }
            if (previous != null) previous.uncaughtException(thread, error) else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    fun clear(context: Context) { report(context).delete() }
    fun export(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { it.write((if (report(context).exists()) report(context).readText() else "No local crash report recorded.").toByteArray()) }
            ?: error("Cannot open export destination")
    }
}
