package com.notesapp.offline.data

import android.appwidget.AppWidgetHost
import android.content.Context

/**
 * One shared [AppWidgetHost] for the whole app. Android widget hosting is
 * keyed by a (package, hostId) pair — reusing the same hostId across app
 * restarts is what lets a previously-bound widget id keep pointing at the
 * same live widget instance instead of orphaning it.
 */
object HsWidgetHost {
    private const val HOST_ID = 8421
    private var host: AppWidgetHost? = null

    fun get(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
}
