package com.venbiasa.wailo.sdk.android

import android.content.Context
import com.venbiasa.wailo.protocol.Hello
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor

/**
 * Install Wailo by adding [interceptor] to an OkHttpClient, and the entry point for everything about
 * *which* desktop the SDK talks to.
 *
 * The connection surface is static because its consumer is: the on-device panel ships as a separate
 * artifact (`sdk-android-panel`) with no handle on whatever the host app did with its client, and
 * auto-instrumentation gives the host app no construction site to hold one either. [status] and the
 * control methods therefore address the client [WailoClient.start] made active, of which there is one.
 */
object Wailo {
    /** Per-body capture cap; larger bodies are truncated, not dropped. */
    const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024L

    internal val mutableStatus = MutableStateFlow(WailoStatus())

    /** Connection, discovery and pairing state, for a panel to `collectAsState`. */
    val status: StateFlow<WailoStatus> = mutableStatus.asStateFlow()

    @Volatile
    internal var active: WailoClient? = null

    @Volatile
    internal var appContext: Context? = null

    fun interceptor(
        sink: CaptureSink = LogcatSink(),
        maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    ): Interceptor = WailoInterceptor(sink, maxBodyBytes)

    /**
     * Give the SDK a `Context` so pairings and the pinned address survive a restart and discovery can
     * run. [WailoStartupProvider] does this before the host `Application` exists, so it is normally
     * already done; call it only when wiring the SDK into a build that has stripped the provider.
     *
     * Without it everything still works, in memory: the device re-pairs on every launch and finds no
     * desktops. That is the deliberate fallback for JVM unit tests, where `android.*` throws.
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        WailoHostStore.storage = PrefsKeyValueStore(app, PREFS_SETTINGS)
        // The long-term pairing key is the one secret the device holds. In a plain preferences file it
        // would let anything able to read that file impersonate this device to the Studio it paired
        // with, so it goes through the Keystore — the counterpart to iOS's Keychain.
        WailoPairingStore.storage = SealedKeyValueStore(PrefsKeyValueStore(app, PREFS_PAIRINGS), KEY_ALIAS)
    }

    /**
     * Sink that streams to the desktop; call [WailoClient.start] before use.
     *
     * Leaving [host] null is the normal case: the endpoint is then resolved per connection attempt from
     * the panel's pinned address, discovery, and finally `localhost` — which is where `adb reverse`
     * puts the desktop. Passing one pins it for the client's whole life, above anything the panel says.
     */
    fun webSocketSink(
        appId: String,
        deviceName: String,
        host: String? = null,
        port: Int? = null,
    ): WailoClient = WailoClient(
        hello = Hello(device_name = deviceName, app_id = appId, platform = PLATFORM_ANDROID),
        host = host,
        port = port,
    )

    // MARK: - connection control (see the matching members on WailoClient)

    /** False when no client is running, or when the text names nothing diallable. */
    fun setHost(host: String?, port: Int? = null): Boolean = active?.setHost(host, port) ?: false

    fun pair(invite: WailoPairingInvite) {
        active?.pair(invite)
    }

    fun forget(studioId: String) {
        active?.forget(studioId)
    }

    fun forgetAllPairings() {
        active?.forgetAllPairings()
    }

    fun retryAfterRefusal() {
        active?.retryAfterRefusal()
    }

    fun acceptIdentityChange() {
        active?.acceptIdentityChange()
    }

    fun rejectIdentityChange() {
        active?.rejectIdentityChange()
    }

    private const val PLATFORM_ANDROID: String = "android"
    private const val PREFS_SETTINGS: String = "wailo_settings"
    private const val PREFS_PAIRINGS: String = "wailo_pairings"
    private const val KEY_ALIAS: String = "wailo_pairing_store"
}
