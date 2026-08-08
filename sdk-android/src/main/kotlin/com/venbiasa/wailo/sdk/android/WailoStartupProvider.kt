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
 * host app. Auto-installs a default WebSocket sink, which resolves its own desktop: the address pinned
 * in the panel, then one discovered over mDNS, then `localhost` for `adb reverse`.
 *
 * The sink is restricted to debuggable builds, so release variants (which still carry the woven, no-op
 * runtime) do nothing. To pin a host/port or fan out to extra sinks (e.g. Logcat), call
 * [WailoRuntime.install] from `Application.onCreate`; it runs after this and cleanly replaces the
 * auto-installed sink.
 *
 * [Wailo.attach] is not gated the same way: it only hands the SDK a `Context`, and doing it here
 * unconditionally means a host that wires its own sink still gets persisted pairings and discovery
 * without having to know this call exists.
 */
class WailoStartupProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        Wailo.attach(context)
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
