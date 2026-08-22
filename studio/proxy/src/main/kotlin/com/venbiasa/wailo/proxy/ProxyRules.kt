package com.venbiasa.wailo.proxy

import com.venbiasa.wailo.protocol.HttpRequest
import com.venbiasa.wailo.protocol.HttpResponse
import javax.net.ssl.SSLContext

/**
 * What the daemon wants from an exchange before any of its bytes move.
 *
 * Asked from the URL and method alone, because the answer decides whether the bodies can be streamed.
 * A held body has to be editable, so it must be whole and in memory when the hold is raised — the one
 * place this relay gives up its streaming guarantee, and only where a rule has already said it will
 * stop the exchange anyway.
 */
class Interception(
    val holdRequest: Boolean = false,
    val holdResponse: Boolean = false,
    val record: Boolean = true,
)

/**
 * Whether a `CONNECT` to [host] is decrypted, and with what identity.
 *
 * Null keeps the tunnel opaque, which is the default for every host (ADR-0071). Returning a context is
 * the daemon asserting two separate things at once — that a local root exists, and that this host is one
 * the user unlocked by name — because either alone must not be enough to read someone's traffic.
 */
fun interface ProxyTls {
    fun unlock(host: String): SSLContext?

    /**
     * The identity and trust used to dial an origin once a tunnel is decrypted. The JDK default, so
     * Wailo refuses a certificate a browser would have refused; overridden to reach an origin behind a
     * private root, which is the one case where the user's own trust store is the wrong answer.
     */
    fun upstream(): SSLContext = SSLContext.getDefault()

    companion object {
        val Locked: ProxyTls = ProxyTls { null }
    }
}

/** What the daemon decides about a request that has not yet left for its origin. */
sealed interface RequestVerdict {
    /** Send it on. [edited] replaces what the client wrote when a human changed it. */
    class Proceed(val edited: HttpRequest? = null) : RequestVerdict

    /** Answer here and never contact the origin — a Map Local rule, or a hold resolved with a response. */
    class Respond(val response: HttpResponse) : RequestVerdict

    /**
     * Fail the client's request.
     *
     * A proxy cannot fall open the way an SDK does: there is no second path to the origin, so an
     * abandoned exchange is a network error the client sees, not a request that quietly succeeds.
     */
    data object Abort : RequestVerdict
}

/** What the daemon decides about a response that has not yet reached the client. */
sealed interface ResponseVerdict {
    class Proceed(val edited: HttpResponse? = null) : ResponseVerdict

    data object Abort : ResponseVerdict
}

/**
 * The daemon's rules — Capture Filter, Map Local, breakpoints, and the seeds that answer their holds —
 * as the relay sees them (ADR-0067). Implemented in `daemon`, which is the only module allowed to know
 * that any of those exist; this interface is what keeps `proxy` off `engine` and `host`.
 *
 * Every method runs on the connection's own virtual thread and may block for as long as a human takes
 * to answer a breakpoint.
 */
interface ProxyRules {
    fun intercepts(method: String, url: String): Interception

    fun onRequest(client: String, request: HttpRequest): RequestVerdict

    fun onResponse(client: String, request: HttpRequest, response: HttpResponse): ResponseVerdict

    companion object {
        /** Relay everything untouched: what a `ProxyServer` does with no daemon behind it. */
        val None: ProxyRules = object : ProxyRules {
            override fun intercepts(method: String, url: String) = Interception()

            override fun onRequest(client: String, request: HttpRequest) = RequestVerdict.Proceed()

            override fun onResponse(
                client: String,
                request: HttpRequest,
                response: HttpResponse,
            ) = ResponseVerdict.Proceed()
        }
    }
}
