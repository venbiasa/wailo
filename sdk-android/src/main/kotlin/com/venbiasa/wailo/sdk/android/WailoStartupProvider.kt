package com.venbiasa.wailo.sdk.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build

/**
 * Zero-config startup hook: its [onCreate] runs before the host `Application`, so applying the Wailo
 * Gradle plugin and depending on this SDK is enough to stream captured traffic — no startup code in the
 * host app. Auto-installs a default WebSocket sink to [WailoClient.DEFAULT_HOST]:[WailoClient.DEFAULT_PORT].
 *
 * Restricted to debuggable builds, so release variants (which still carry the woven, no-op runtime) do
 * nothing. To customise host/port or fan out to extra sinks (e.g. Logcat), call [WailoRuntime.install]
 * from `Application.onCreate`; it runs after this and cleanly replaces the auto-installed sink.
 */
class WailoStartupProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            WailoRuntime.install(
                Wailo.webSocketSink(appId = context.packageName, deviceName = Build.MODEL).also { it.start() },
            )
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
