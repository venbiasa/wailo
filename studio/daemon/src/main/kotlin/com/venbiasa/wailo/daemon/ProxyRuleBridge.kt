package com.venbiasa.wailo.daemon

import com.venbiasa.wailo.host.HeadlessHost
import com.venbiasa.wailo.host.HostBreakpointRule
import com.venbiasa.wailo.host.captureFilterAdmits
import com.venbiasa.wailo.host.urlPatternMatches
import com.venbiasa.wailo.protocol.BreakpointAction
import com.venbiasa.wailo.protocol.BreakpointDecision
import com.venbiasa.wailo.protocol.BreakpointPhase
import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import com.venbiasa.wailo.proxy.Interception
import com.venbiasa.wailo.proxy.ProxyRules
import com.venbiasa.wailo.proxy.RequestVerdict
import com.venbiasa.wailo.proxy.ResponseVerdict
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString

/**
 * The daemon's rules as the bundled proxy sees them: one evaluator, the same rule sets, whichever path
 * the exchange arrived on (ADR-0067).
 *
 * Precedence is ADR-0033's, unchanged — the capture filter decides what is *kept*; with no breakpoint a
 * Map Local rule short-circuits to its fixture; with one, the breakpoint owns the exchange and Map Local
 * merely supplies the response it shows. Reproducing it here rather than sharing code with the device is
 * deliberate: the SDK's copy runs inside a third-party app and cannot depend on anything here.
 */
internal class HostProxyRules(private val host: HeadlessHost) : ProxyRules {
    // One slot per hold rather than a rendezvous: a decision must never depend on the relay thread having
    // already reached its wait, and delivering it must never block the frontend that made it.
    private val waiting = ConcurrentHashMap<String, ArrayBlockingQueue<BreakpointDecision>>()

    override fun intercepts(method: String, url: String): Interception {
        val rule = matchingBreakpoint(url, method)
        return Interception(
            holdRequest = rule?.onRequest == true,
            holdResponse = rule?.onResponse == true,
            record = captureFilterAdmits(host.engine.captureFilter.value, hostOf(url)),
        )
    }

    override fun onRequest(client: String, request: HttpRequest): RequestVerdict {
        val rule = matchingBreakpoint(request.url, request.method)
            ?: return mapLocal(request)?.let { RequestVerdict.Respond(it) } ?: RequestVerdict.Proceed()

        var outgoing = request
        // A body too large to hold was never offered for editing, so pausing on it would show a human a
        // request they cannot faithfully approve.
        if (rule.onRequest && !request.body_truncated) {
            val decision = await(client, BreakpointPhase.BREAKPOINT_PHASE_REQUEST, request, null)
                ?: return RequestVerdict.Abort
            if (decision.action == BreakpointAction.BREAKPOINT_ACTION_ABORT) return RequestVerdict.Abort
            decision.edited_request?.let { outgoing = it }
        }

        val mapped = mapLocal(outgoing) ?: return RequestVerdict.Proceed(outgoing.takeIf { it !== request })
        if (!rule.onResponse) return RequestVerdict.Respond(mapped)

        // The fixture becomes the breakpoint's response rather than bypassing it (ADR-0033), so the
        // response phase runs here — the relay is answering locally and will never call onResponse.
        val decision = await(client, BreakpointPhase.BREAKPOINT_PHASE_RESPONSE, outgoing, mapped)
            ?: return RequestVerdict.Abort
        if (decision.action == BreakpointAction.BREAKPOINT_ACTION_ABORT) return RequestVerdict.Abort
        return RequestVerdict.Respond(decision.edited_response ?: mapped)
    }

    override fun onResponse(client: String, request: HttpRequest, response: HttpResponse): ResponseVerdict {
        val decision = await(client, BreakpointPhase.BREAKPOINT_PHASE_RESPONSE, request, response)
            ?: return ResponseVerdict.Proceed()
        if (decision.action == BreakpointAction.BREAKPOINT_ACTION_ABORT) return ResponseVerdict.Abort
        return ResponseVerdict.Proceed(decision.edited_response)
    }

    /** Release every waiting relay thread, so stopping the proxy does not strand a client on a hold. */
    fun releaseAll() {
        waiting.keys.toList().forEach { id ->
            host.engine.releaseExternalHold(id)
            waiting.remove(id)?.offer(PROCEED)
        }
    }

    /**
     * Park this connection's thread until a frontend, the CLI, or a seed decides — the proxy's half of a
     * hold. Returns null only if the wait was interrupted, which a caller treats as an abort.
     */
    private fun await(
        client: String,
        phase: BreakpointPhase,
        request: HttpRequest,
        response: HttpResponse?,
    ): BreakpointDecision? {
        val id = UUID.randomUUID().toString()
        val mailbox = ArrayBlockingQueue<BreakpointDecision>(1)
        waiting[id] = mailbox
        host.engine.holdExternal(id, client, phase, request, response, mailbox::offer)
        return try {
            mailbox.take()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            host.engine.releaseExternalHold(id)
            null
        } finally {
            waiting.remove(id)
        }
    }

    private fun matchingBreakpoint(url: String, method: String): HostBreakpointRule? {
        if (!host.areBreakpointsEnabled()) return null
        return host.breakpointRules.value.firstOrNull { rule ->
            rule.enabled && urlPatternMatches(rule.urlPattern, url) &&
                (rule.methods.isEmpty() || rule.methods.any { it.equals(method, ignoreCase = true) })
        }
    }

    /**
     * Resolve a matching fixture through the engine's own body provider rather than the rule object: it
     * is the same seam a device fetches through, so the master switch and a missing file behave here
     * exactly as they do for the SDK.
     */
    private fun mapLocal(request: HttpRequest): HttpResponse? {
        if (!host.isMapLocalEnabled()) return null
        val rule = host.mapLocalRules.value.firstOrNull { candidate ->
            candidate.enabled && urlPatternMatches(candidate.urlPattern, request.url) &&
                (candidate.methods.isEmpty() || candidate.methods.any { it.equals(request.method, ignoreCase = true) })
        } ?: return null
        val provider = host.engine.bodyProvider ?: return null
        val served = runBlocking { provider.serve(rule.id, request.url, request.method) } ?: return null
        return HttpResponse(
            code = served.code,
            headers = served.headers,
            body = served.body.toByteString(),
            body_size = served.body.size.toLong(),
        )
    }

    private companion object {
        val PROCEED = BreakpointDecision(action = BreakpointAction.BREAKPOINT_ACTION_PROCEED)
    }
}

/**
 * The Capture Filter matches on host, and a proxied row's URL is the only place one exists — there is no
 * device to have parsed it already.
 */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
