package com.venbiasa.wailo.sdk.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet6Address
import java.net.InetAddress
import java.util.ArrayDeque

/** A desktop found advertising itself on the local network. */
data class WailoDesktop(
    /** The Bonjour instance name — the Mac's hostname, which is what a human picks out of a list. */
    val name: String,
    val host: String,
    val port: Int,
    /**
     * Studio's public-key fingerprint, from the `sid` TXT record. Empty for a desktop too old to
     * advertise one, which the resolver treats the same as unpaired: something has to be scanned or
     * typed first. Unauthenticated on its own — anyone can claim any string — but the handshake binds
     * it, since the key that signs has to hash back to this.
     */
    val studioId: String,
) {
    val address: String get() = "$host:$port"
}

/**
 * Finds desktops advertising `_wailo._tcp` through `NsdManager`, Android's own mDNS/DNS-SD client —
 * the counterpart to iOS's `NWBrowser` (ADR-0035), and the reason Android needs no third-party mDNS
 * library or a `MulticastLock`: the resolution happens in the system daemon, outside this process.
 *
 * Discovery only ever *finds* a desktop. Whether one is dialled is [WailoEndpointResolver]'s decision
 * and, per ADR-0040, it may only reconnect to an identity this device already trusts.
 *
 * Two `NsdManager` details shape this. Attributes (the `sid` TXT record) are not populated on
 * `onServiceFound` below API 34, so every service has to be resolved before it can be filtered —
 * harmless here because a resolve is an mDNS query and opens no connection to the desktop, unlike
 * iOS's, which has to dial a throwaway TCP connection to get an address at all. And `resolveService`
 * admits exactly one call at a time before API 34, answering a concurrent one with
 * `FAILURE_ALREADY_ACTIVE`, so resolves are queued rather than fired in parallel.
 */
internal class WailoDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val lock = Any()
    private val found = LinkedHashMap<String, WailoDesktop>()
    private val queued = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false
    private var listener: NsdManager.DiscoveryListener? = null

    /** Fires whenever the resolved set changes. */
    @Volatile
    var onChange: ((List<WailoDesktop>) -> Unit)? = null

    fun start() {
        val manager = nsd ?: return
        val listener = synchronized(lock) {
            if (started) return
            started = true
            discoveryListener().also { this.listener = it }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        val manager = nsd ?: return
        val listener = synchronized(lock) {
            if (!started) return
            started = false
            queued.clear()
            resolving = false
            found.clear()
            this.listener.also { this.listener = null }
        } ?: return
        runCatching { manager.stopServiceDiscovery(listener) }
        publish()
    }

    private fun discoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = markStopped()

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = markStopped()

        override fun onDiscoveryStarted(serviceType: String?) = Unit

        override fun onDiscoveryStopped(serviceType: String?) = markStopped()

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            val service = serviceInfo ?: return
            synchronized(lock) {
                if (!started || found.containsKey(service.serviceName)) return
                queued.addLast(service)
            }
            pump()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            val name = serviceInfo?.serviceName ?: return
            val changed = synchronized(lock) {
                queued.removeAll { it.serviceName == name }
                found.remove(name) != null
            }
            if (changed) publish()
        }
    }

    private fun markStopped() {
        synchronized(lock) {
            started = false
            listener = null
        }
    }

    // One resolve in flight, the rest waiting: `resolveService` refuses a concurrent call before API 34.
    private fun pump() {
        val manager = nsd ?: return
        val next = synchronized(lock) {
            if (resolving) return
            val service = queued.pollFirst() ?: return
            resolving = true
            service
        }
        @Suppress("DEPRECATION")
        runCatching { manager.resolveService(next, resolveListener()) }
            .onFailure {
                synchronized(lock) { resolving = false }
                pump()
            }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = finish(null)

        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) = finish(serviceInfo)
    }

    private fun finish(serviceInfo: NsdServiceInfo?) {
        val desktop = serviceInfo?.let(::toDesktop)
        val changed = synchronized(lock) {
            resolving = false
            if (desktop == null || !started) false else found.put(desktop.name, desktop) != desktop
        }
        if (changed) publish()
        pump()
    }

    @Suppress("DEPRECATION")
    private fun toDesktop(service: NsdServiceInfo): WailoDesktop? {
        val host = service.host?.let(::urlHost) ?: return null
        val port = service.port.takeIf { it in WailoAddress.PORT_RANGE } ?: return null
        val studioId = service.attributes?.get(STUDIO_ID_KEY)?.let { String(it) }.orEmpty()
        return WailoDesktop(name = service.serviceName.orEmpty(), host = host, port = port, studioId = studioId)
    }

    /**
     * A scoped IPv6 address prints a `%wlan0` suffix that is illegal in a URL authority, and a genuine
     * v6 literal has to be bracketed to be one at all. A v4-mapped address dials fine as a dotted quad
     * and keeps the address readable.
     */
    private fun urlHost(address: InetAddress): String? {
        val literal = address.hostAddress?.substringBefore('%')?.takeIf { it.isNotEmpty() } ?: return null
        return if (address is Inet6Address && !literal.contains('.')) "[$literal]" else literal
    }

    private fun publish() {
        val snapshot = synchronized(lock) { found.values.sortedBy { it.name.lowercase() } }
        onChange?.invoke(snapshot)
    }

    private companion object {
        // Android takes the bare type and appends the domain itself.
        const val SERVICE_TYPE = "_wailo._tcp"
        const val STUDIO_ID_KEY = "sid"
    }
}
