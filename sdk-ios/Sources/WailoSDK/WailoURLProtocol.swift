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

/// A `URLProtocol` that copies each URLSession HTTP(S) exchange into a `CaptureSink` — subject to the
/// desktop's CaptureFilter, which decides per host whether the whole exchange is captured at all
/// (ADR-0029); a filtered host is dropped at the source. It normally
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
    // Optional cap on captured body bytes; nil (the default) captures the full body. The CaptureFilter
    // decides whole-exchange whether a host is captured at all (ADR-0029), so memory is bounded by
    // *which* hosts pass the filter rather than by a per-body ceiling. Larger-than-cap bodies (when a cap
    // is set) are truncated, not dropped.
    nonisolated(unsafe) static var maxBodyBytes: Int? = nil

    private lazy var session = URLSession(configuration: .ephemeral)
    // Not named `task`: URLProtocol already declares a read-only `task` property.
    private var replayTask: URLSessionDataTask?
    private var startedAtEpochMs: Int64 = 0
    private var startNanos: UInt64 = 0
    // The request body, buffered in startLoading only when it will be used (an unlocked host or a
    // breakpoint). URLSession moves a request's httpBody into httpBodyStream before we're called, so
    // httpBody is nil for essentially every request that has a body; we drain the stream once (it reads
    // only once) and reuse this buffer for both capture and the replay. nil means no body — or a locked
    // host whose bytes we deliberately don't buffer, letting the stream pass straight through to the
    // replay so memory stays bounded to the few unlocked hosts.
    private var requestBody: Data?
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

        // Drain the request body up front, but only when we'll actually use the bytes: a host the
        // CaptureFilter admits (whose whole exchange we capture) or any breakpoint match (which shows the
        // body to the desktop regardless of the filter). URLSession moves httpBody into httpBodyStream
        // before we run, and a stream can be read only once, so we buffer it here and reuse it for capture
        // and the replay (proceedToNetwork restores it, since reading now consumes the stream the replay
        // would send). For a filtered host with no breakpoint we skip this, leaving the stream to pass
        // through untouched so memory stays bounded to the hosts that pass the filter.
        let breakpointMatch = urlString.flatMap { WailoBreakpointStore.shared.match(url: $0, method: method) }
        let shouldCapture = WailoCaptureFilterStore.shared.shouldCapture(host: request.url?.host)
        if shouldCapture || breakpointMatch != nil {
            requestBody = WailoURLProtocol.readBody(from: request)
        }

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
        if let breakpointMatch, let gate = WailoURLProtocol.breakpointGate {
            if breakpointMatch.onResponse { responseBreakpointRuleId = breakpointMatch.ruleId }
            if breakpointMatch.onRequest {
                gate.pauseRequest(ruleId: breakpointMatch.ruleId, request: captureRequest()) { [weak self] decision in
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
        } else if let requestBody, !requestBody.isEmpty {
            // We drained the original httpBodyStream to capture it, which consumes it; set the buffered
            // bytes so the replay still sends the body instead of an empty (already-read) stream.
            replay.httpBody = requestBody
        }
        // Remember what actually went out, to show as context if the response is also broken.
        if responseBreakpointRuleId != nil {
            breakpointRequestContext = edited ?? captureRequest()
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
            let current = captureResponse(http, body: data)
            let context = breakpointRequestContext ?? captureRequest()
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
        // Whole-exchange gate (ADR-0029): the CaptureFilter decides per host whether this exchange is
        // captured at all. A filtered host is dropped here, at the source — never streamed and never
        // logged — so there is no partial/metadata-only capture.
        guard WailoCaptureFilterStore.shared.shouldCapture(host: request.url?.host) else { return }
        let durationMs = Int64((DispatchTime.now().uptimeNanoseconds &- startNanos) / 1_000_000)
        let capturedRequest = captureRequest()
        let httpResponse = response as? HTTPURLResponse
        let capturedResponse = httpResponse.map { captureResponse($0, body: body) }

        let exchange = HttpExchange(
            id: UUID().uuidString,
            started_at_epoch_ms: startedAtEpochMs,
            duration_ms: durationMs,
            error: error.map { ($0 as NSError).localizedDescription } ?? "",
            edited: edited
        ) {
            $0.request = capturedRequest
            $0.response = capturedResponse
        }
        sink.onExchange(exchange)
    }

    private func captureRequest() -> HttpRequest {
        // Read from the buffer drained in startLoading, not request.httpBody: URLSession moves the body
        // into httpBodyStream before we run, leaving request.httpBody nil for most bodies.
        let full = requestBody ?? Data()
        let (captured, truncated) = WailoURLProtocol.capturedBody(full)
        let headers = (request.allHTTPHeaderFields ?? [:]).map { Header(name: $0.key, value: $0.value) }
        return HttpRequest(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "",
            body: captured,
            // Fall back to the declared size when we didn't buffer the body (e.g. a breakpoint context
            // built before the body was drained), so the size column stays meaningful.
            body_size: requestBody.map { Int64($0.count) } ?? declaredRequestBodySize(),
            body_truncated: truncated
        ) {
            $0.headers = headers
        }
    }

    /// The request body's declared size without draining the stream: the httpBody length when present,
    /// else the Content-Length header. Used for the size column when `requestBody` wasn't buffered.
    private func declaredRequestBodySize() -> Int64 {
        if let body = request.httpBody { return Int64(body.count) }
        if let value = request.value(forHTTPHeaderField: "Content-Length"), let size = Int64(value) {
            return size
        }
        return 0
    }

    private func captureResponse(_ response: HTTPURLResponse, body: Data?) -> HttpResponse {
        let declaredSize = response.expectedContentLength // -1 when unknown
        let full = body ?? Data()
        let (captured, truncated) = WailoURLProtocol.capturedBody(full)
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

    /// The request's full body bytes, or nil when there is none. Prefers `httpBody`, but URLSession moves
    /// a request's body into `httpBodyStream` before `startLoading` runs, so for most bodies we must drain
    /// the stream instead (it can be read only once — the caller reuses the result for capture and replay).
    /// Static + internal so it's unit testable without driving a full URLProtocol/network flow.
    static func readBody(from request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 65_536
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            // A negative count is a stream error; return what we have rather than looping forever.
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    /// The body bytes to capture: the full body, truncated only if a `maxBodyBytes` cap is set (nil =
    /// unlimited, the default). Returns the captured bytes and whether they were truncated. Whether the
    /// exchange is captured at all is decided per host by the CaptureFilter in `emit` (ADR-0029); by the
    /// time we get here the host has passed, so the only question left is the size cap. Static + internal
    /// so it's unit testable without driving a full URLProtocol/network flow; [cap] defaults to the cap.
    static func capturedBody(_ full: Data, cap: Int? = maxBodyBytes) -> (Data, Bool) {
        guard let cap, full.count > cap else { return (full, false) }
        return (Data(full.prefix(cap)), true)
    }
}
