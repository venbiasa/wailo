package com.venbiasa.wailo.sdk.android.panel

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.database.Cursor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build

/**
 * Puts Wailo in the long-press menu on the host app's launcher icon, the way LeakCanary does.
 *
 * The panel's own launcher icon is the primary way in, but it is one more icon in an app drawer and some
 * launchers and managed home screens do not show secondary entries at all. A dynamic shortcut costs
 * nothing and lands where a debug tool is reached for. Registered from a provider for the same reason
 * `WailoStartupProvider` is one: it has to happen with no host code.
 */
internal class WailoPanelShortcutProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        // Off the startup path. This is two binder round-trips to the system server for an affordance
        // nobody needs in the first frame, and it is the host app's start-up time being spent.
        Thread({ register(context) }, "wailo-panel-shortcut").start()
        return true
    }

    private fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val host = hostLauncherClass(context.launcherClasses(), WailoPanelActivity::class.java.name) ?: return
        val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel(SHORT_LABEL)
            .setLongLabel(LONG_LABEL)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_wailo_panel))
            .setActivity(ComponentName(context.packageName, host))
            .setIntent(
                // Launched from the launcher's process, not ours: without an action it is rejected
                // outright, and without its own task it opens inside whatever the host had going.
                Intent(context, WailoPanelActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            .build()
        // Additive, never setDynamicShortcuts — the host's own shortcuts are not ours to drop. This
        // throws once the per-activity cap is full, which is a host that already uses the menu; losing
        // that race is not a reason to take someone's app down on launch.
        runCatching { manager.addDynamicShortcuts(listOf(shortcut)) }
    }

    private fun Context.launcherClasses(): List<String> {
        val launchable = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        return packageManager.queryIntentActivities(launchable, 0).map { it.activityInfo.name }
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

    private companion object {
        const val SHORTCUT_ID = "wailo-panel"
        const val SHORT_LABEL = "Wailo"
        const val LONG_LABEL = "Wailo Panel"
    }
}

/**
 * The launcher entry the shortcut hangs off: the host's own, never the panel's.
 *
 * A shortcut is shown against one activity's icon, and the panel declares a launcher entry too — so the
 * obvious `first()` attaches Wailo to Wailo, a menu reachable only from the icon it duplicates.
 */
internal fun hostLauncherClass(launcherClasses: List<String>, panelClass: String): String? =
    launcherClasses.firstOrNull { it != panelClass }
