import Foundation
import WailoProtocol

/// The response a matched Map Local rule resolves to, fetched from the desktop on demand (ADR-0019).
/// Bodies are never cached on the device — this exists only for the life of the one request it answers.
struct WailoMappedResponse {
    let code: Int
    let headers: [Header]
    let body: Data
}

/// Resolves a matched rule to its response by asking the desktop (the authority) for the bytes. The
/// interceptor caches only match-metadata, so this round-trips the body per match. `completion` is
/// called with nil when the fetch can't complete (not connected, timeout, rule gone), and the
/// interceptor then falls open to the real network. `WailoClient` is the implementation.
protocol WailoBodyFetcher: AnyObject {
    func fetchBody(ruleId: String, url: String, method: String, completion: @escaping (WailoMappedResponse?) -> Void)
}

/// The desktop's decision for a request held at a breakpoint: proceed (with an edited request, or nil to
/// send the original unchanged / fail open) or abort the app's call.
enum WailoRequestDecision {
    case proceed(HttpRequest?)
    case abort
}

/// The desktop's decision for a response held at a breakpoint: proceed (with an edited response, or nil
/// to deliver the original unchanged / fail open) or abort the app's call.
enum WailoResponseDecision {
    case proceed(HttpResponse?)
    case abort
}

/// Pauses a matched request/response and awaits the desktop's decision (ADR-0027). The interceptor calls
/// these when a breakpoint rule matches; the completion is invoked exactly once, on the client's queue.
/// There is deliberately no timeout — a human is deciding — so a completion arrives only from a
/// `BreakpointDecision` or, if the link drops first, a fail-open (`.proceed(nil)`), which is why a
/// desktop that never answers can't hang the app's call forever. `WailoClient` is the implementation.
protocol WailoBreakpointGate: AnyObject {
    func pauseRequest(ruleId: String, request: HttpRequest, completion: @escaping (WailoRequestDecision) -> Void)
    func pauseResponse(
        ruleId: String,
        request: HttpRequest,
        response: HttpResponse,
        completion: @escaping (WailoResponseDecision) -> Void
    )
}

/// A `URLProtocol` that copies every URLSession HTTP(S) exchange into a `CaptureSink`. It normally
/// leaves the real request/response untouched, but first checks the Map Local match-metadata the
/// desktop has pushed: on a match it fetches the response from the desktop (never a cached body —
/// ADR-0019) and answers with it, flagging the exchange `edited`. If that fetch fails for any reason it
/// falls open to the real network, so a desktop hiccup never hangs or fails the request. On the
/// real-network path it also honors breakpoint rules (ADR-0027): a matching request can be paused before
/// it's sent and/or its response before the app sees it, so the desktop can edit, abort, or resume it;
/// a disconnect fails open. Map Local takes precedence and is never broken. The iOS analog of the OkHttp
/// `WailoInterceptor`.
///
/// URLProtocol instances are created by Foundation, so there is no constructor to hand a sink to —
/// the sink is process-global (`Wailo.start` sets it), mirroring `WailoRuntime` on Android.
public final class WailoURLProtocol: URLProtocol {

    /// Marks requests we've already taken so our own replay task isn't intercepted again.
    private static let handledKey = "com.venbiasa.wailo.handled"

    // Set once at startup by `Wailo.start`. Written before any capture begins and only read after,
    // so plain statics are safe here.
    nonisolated(unsafe) static var sink: CaptureSink?
    nonisolated(unsafe) static var bodyFetcher: WailoBodyFetcher?
    nonisolated(unsafe) static var breakpointGate: WailoBreakpointGate?
    // Optional cap on captured body bytes; nil (the default) captures the full body. Body capture is
    // gated per host by the CaptureAllowlist, so memory is bounded by *which* hosts are unlocked rather
    // than by a per-body ceiling. Larger-than-cap bodies (when a cap is set) are truncated, not dropped.
    nonisolated(unsafe) static var maxBodyBytes: Int? = nil

    private lazy var session = URLSession(configuration: .ephemeral)
    // Not named `task`: URLProtocol already declares a read-only `task` property.
    private var replayTask: URLSessionDataTask?
    private var startedAtEpochMs: Int64 = 0
    private var startNanos: UInt64 = 0
    // Set when Foundation cancels this load, so a body-fetch that resolves afterward doesn't deliver to
    // a torn-down protocol. Best-effort (read off the client queue), which is enough to avoid late work.
    private var stopped = false
    // Set in startLoading when a matched rule breaks on the response phase; consumed once in finish to
    // pause the reply before the app sees it. The request context shown alongside that paused response
    // (the request actually sent, edited if the request phase changed it).
    private var responseBreakpointRuleId: String?
    private var breakpointRequestContext: HttpRequest?

    override public class func canInit(with request: URLRequest) -> Bool {
        guard sink != nil else { return false }
        guard let scheme = request.url?.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            return false
        }
        // Our replay request carries this marker; ignoring it prevents an infinite intercept loop.
        return property(forKey: handledKey, in: request) == nil
    }

    override public class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override public func startLoading() {
        startedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1000)
        startNanos = DispatchTime.now().uptimeNanoseconds

        let method = request.httpMethod ?? "GET"
        let urlString = request.url?.absoluteString

        // Map Local: match locally (metadata only), then fetch the body from the desktop and serve it.
        // Any failure (no fetcher, not connected, timeout, rule gone) falls open to the real network. A
        // Map Local match takes precedence over breakpoints and is never broken (ADR-0027 precedence v1).
        if let urlString,
           let rule = WailoRuleStore.shared.match(url: urlString, method: method),
           let fetcher = WailoURLProtocol.bodyFetcher {
            fetcher.fetchBody(ruleId: rule.id, url: urlString, method: method) { [weak self] mapped in
                guard let self, !self.stopped else { return }
                if let mapped {
                    self.serveMapped(mapped)
                } else {
                    self.proceedToNetwork()
                }
            }
            return
        }

        // Breakpoints apply only on the real-network path. Arm the response phase (checked in `finish`)
        // and, if the rule breaks on the request, pause before anything is sent: the desktop can edit the
        // request, abort the call, or resume it unchanged; a disconnect fails open to the real network.
        if let urlString,
           let gate = WailoURLProtocol.breakpointGate,
           let match = WailoBreakpointStore.shared.match(url: urlString, method: method) {
            if match.onResponse { responseBreakpointRuleId = match.ruleId }
            if match.onRequest {
                gate.pauseRequest(ruleId: match.ruleId, request: captureRequest(bodiesAllowed: true)) { [weak self] decision in
                    guard let self, !self.stopped else { return }
                    switch decision {
                    case .abort:
                        self.abort()
                    case let .proceed(edited):
                        self.proceedToNetwork(edited: edited)
                    }
                }
                return
            }
        }

        proceedToNetwork()
    }

    override public func stopLoading() {
        stopped = true
        replayTask?.cancel()
        replayTask = nil
    }

    /// Runs the real request (the normal, unmapped path). Marks the replay so `canInit` skips it,
    /// avoiding an infinite intercept loop, and forwards the result back to the caller via `finish`. When
    /// a request-phase breakpoint edited the request, [edited] carries the new method/URL/headers/body to
    /// send instead of the original.
    private func proceedToNetwork(edited: HttpRequest? = nil) {
        guard let replay = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        if let edited {
            if let editedURL = URL(string: edited.url) { replay.url = editedURL }
            replay.httpMethod = edited.method
            replay.httpBody = edited.body.isEmpty ? nil : edited.body
            replay.allHTTPHeaderFields = Dictionary(
                edited.headers.map { ($0.name, $0.value) },
                uniquingKeysWith: { _, latest in latest }
            )
        }
        // Remember what actually went out, to show as context if the response is also broken.
        if responseBreakpointRuleId != nil {
            breakpointRequestContext = edited ?? captureRequest(bodiesAllowed: true)
        }
        WailoURLProtocol.setProperty(true, forKey: WailoURLProtocol.handledKey, in: replay)

        replayTask = session.dataTask(with: replay as URLRequest) { [weak self] data, response, error in
            self?.finish(data: data, response: response, error: error)
        }
        replayTask?.resume()
    }

    /// Fails the app's call, as a breakpoint Abort does. Flagged `edited` so the desktop's list marks the
    /// exchange as intercepted rather than a real network failure.
    private func abort() {
        let error = URLError(.cancelled)
        client?.urlProtocol(self, didFailWithError: error)
        emit(response: nil, body: nil, error: error, edited: true)
    }

    /// Answers the request from a Map Local rule instead of the network: hands the desktop-supplied
    /// status, headers, and body back to the caller, then mirrors the exchange into the sink flagged
    /// `edited`. No replay task is created, so no request ever leaves the device.
    private func serveMapped(_ mapped: WailoMappedResponse) {
        let body = mapped.body
        let status = mapped.code == 0 ? 200 : mapped.code
        var headerFields: [String: String] = [:]
        for header in mapped.headers { headerFields[header.name] = header.value }
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "about:blank")!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: headerFields
        )
        if let response {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
        emit(response: response, body: body, error: nil, edited: true)
    }

    /// Forwards the real result back to the caller, then mirrors a copy into the sink. If a response-phase
    /// breakpoint is armed and this is an HTTP response, it first pauses so the desktop can edit it, abort
    /// the call, or resume it unchanged (a disconnect fails open, delivering the original).
    private func finish(data: Data?, response: URLResponse?, error: Error?) {
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
            emit(response: nil, body: nil, error: error)
            return
        }
        if let ruleId = responseBreakpointRuleId,
           let gate = WailoURLProtocol.breakpointGate,
           let http = response as? HTTPURLResponse {
            responseBreakpointRuleId = nil
            let current = captureResponse(http, body: data, bodiesAllowed: true)
            let context = breakpointRequestContext ?? captureRequest(bodiesAllowed: true)
            gate.pauseResponse(ruleId: ruleId, request: context, response: current) { [weak self] decision in
                guard let self, !self.stopped else { return }
                switch decision {
                case .abort:
                    self.abort()
                case let .proceed(editedResponse):
                    if let editedResponse {
                        self.forwardEdited(editedResponse)
                    } else {
                        self.forwardOriginal(data: data, response: response)
                    }
                }
            }
            return
        }
        forwardOriginal(data: data, response: response)
    }

    /// Delivers the real (unedited) result to the caller and mirrors it into the sink.
    private func forwardOriginal(data: Data?, response: URLResponse?) {
        if let response {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        if let data {
            client?.urlProtocol(self, didLoad: data)
        }
        client?.urlProtocolDidFinishLoading(self)
        emit(response: response, body: data, error: nil)
    }

    /// Delivers a response-phase breakpoint's edited response: rebuilds an `HTTPURLResponse` from the
    /// desktop's status/headers and hands its body to the caller, flagging the exchange `edited`.
    private func forwardEdited(_ edited: HttpResponse) {
        let status = Int(edited.code) == 0 ? 200 : Int(edited.code)
        var headerFields: [String: String] = [:]
        for header in edited.headers { headerFields[header.name] = header.value }
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "about:blank")!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: headerFields
        )
        if let response {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        client?.urlProtocol(self, didLoad: edited.body)
        client?.urlProtocolDidFinishLoading(self)
        emit(response: response, body: edited.body, error: nil, edited: true)
    }

    private func emit(response: URLResponse?, body: Data?, error: Error?, edited: Bool = false) {
        guard let sink = WailoURLProtocol.sink else { return }
        let durationMs = Int64((DispatchTime.now().uptimeNanoseconds &- startNanos) / 1_000_000)
        // Bodies are captured only for hosts the desktop has unlocked (CaptureAllowlist); everything
        // else records metadata only, flagged bodies_omitted so the desktop can offer to unlock.
        let bodiesAllowed = WailoCaptureConfigStore.shared.isBodyAllowed(host: request.url?.host)
        let capturedRequest = captureRequest(bodiesAllowed: bodiesAllowed)
        let httpResponse = response as? HTTPURLResponse
        let capturedResponse = httpResponse.map { captureResponse($0, body: body, bodiesAllowed: bodiesAllowed) }

        let exchange = HttpExchange(
            id: UUID().uuidString,
            started_at_epoch_ms: startedAtEpochMs,
            duration_ms: durationMs,
            error: error.map { ($0 as NSError).localizedDescription } ?? "",
            edited: edited,
            bodies_omitted: !bodiesAllowed
        ) {
            $0.request = capturedRequest
            $0.response = capturedResponse
        }
        sink.onExchange(exchange)
    }

    private func captureRequest(bodiesAllowed: Bool) -> HttpRequest {
        // Bodies sent via httpBodyStream are not readable here (documented blind spot).
        let full = request.httpBody ?? Data()
        let (captured, truncated) = WailoURLProtocol.capturedBody(full, allowed: bodiesAllowed)
        let headers = (request.allHTTPHeaderFields ?? [:]).map { Header(name: $0.key, value: $0.value) }
        return HttpRequest(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "",
            body: captured,
            // Keep the declared size even when bodies are omitted, so the size column stays meaningful.
            body_size: Int64(full.count),
            body_truncated: truncated
        ) {
            $0.headers = headers
        }
    }

    private func captureResponse(_ response: HTTPURLResponse, body: Data?, bodiesAllowed: Bool) -> HttpResponse {
        let declaredSize = response.expectedContentLength // -1 when unknown
        let full = body ?? Data()
        let (captured, truncated) = WailoURLProtocol.capturedBody(full, allowed: bodiesAllowed)
        let headers = response.allHeaderFields.map { Header(name: "\($0.key)", value: "\($0.value)") }
        return HttpResponse(
            code: Int32(response.statusCode),
            // URLSession never exposes the HTTP status line's reason phrase
            message: "",
            body: captured,
            body_size: declaredSize,
            body_truncated: truncated
        ) {
            $0.headers = headers
        }
    }

    /// The body bytes to capture: nothing (metadata only) when the host isn't unlocked; otherwise the
    /// full body, truncated only if a `maxBodyBytes` cap is set (nil = unlimited, the default). Returns
    /// the captured bytes and whether they were truncated. Static + internal so the gating is unit
    /// testable without driving a full URLProtocol/network flow; [cap] defaults to the current cap.
    static func capturedBody(_ full: Data, allowed: Bool, cap: Int? = maxBodyBytes) -> (Data, Bool) {
        guard allowed else { return (Data(), false) }
        guard let cap, full.count > cap else { return (full, false) }
        return (Data(full.prefix(cap)), true)
    }
}
