package com.notesapp.offline

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Minimal on-device crash logger. Installed as the process's default
 * uncaught-exception handler in MainActivity.onCreate(). Writes the full
 * stack trace to a plain file under filesDir, then hands off to whatever
 * handler was previously installed (usually the system one, which shows
 * "App has stopped" and kills the process) so normal crash behavior is
 * unchanged — this only adds a readable copy you can pull up next launch.
 *
 * Exists specifically so a crash can be diagnosed from the phone alone,
 * with no adb/computer/logcat access required.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file(appContext).writeText(sw.toString())
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the last saved crash text, if any, without deleting it. */
    fun read(context: Context): String? {
        val f = file(context.applicationContext)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    /** Call once the user has seen/copied the report. */
    fun clear(context: Context) {
        runCatching { file(context.applicationContext).delete() }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
