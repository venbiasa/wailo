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

protocol WailoScriptTransformer: AnyObject {
    func transform(
        phase: ScriptPhase,
        request: HttpRequest,
        response: HttpResponse?,
        requestBodyReplayable: Bool,
        completion: @escaping (ScriptTransformResult?) -> Void
    )
}

/// A `URLProtocol` that copies each URLSession HTTP(S) exchange into a `CaptureSink` — subject to the
/// desktop's CaptureFilter, which decides per host whether the whole exchange is captured at all
/// (ADR-0029); a filtered host is dropped at the source. It normally leaves the real request/response
/// untouched. If no breakpoint matches, it checks the Map Local match-metadata the desktop has pushed
/// (ADR-0019): on a match it fetches the response from the desktop (never a cached body) and serves it,
/// flagging the exchange `edited`, and falls open to the real network if that fetch fails for any reason,
/// so a desktop hiccup never hangs or fails the request. If a breakpoint (ADR-0027) matches, the breakpoint
/// *owns* the exchange (ADR-0033, superseding ADR-0027/0032): the request phase can pause before the request
/// is sent (edit/abort/resume); the response is then sourced from Map Local when a rule matches the outgoing
/// request — the mocked value *becomes* the response — otherwise from the network; and the response phase
/// can pause on it before the app sees it. So Map Local supplies the breakpoint's response rather than
/// bypassing it; only with no breakpoint does it short-circuit. A disconnect fails open. The iOS analog of
/// the OkHttp `WailoInterceptor`.
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
    nonisolated(unsafe) static var scriptTransformer: WailoScriptTransformer?
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
    private var outgoingRequest: HttpRequest?
    private var requestBodyReplaced = false
    private var responseScriptDelayMs: Int32 = 0
    // True once the exchange diverges from the real network one — a request-phase edit or a Map Local-sourced
    // response (ADR-0033) — so a response resumed unchanged is still mirrored as `edited`. A response-phase
    // edit or an abort flags the row on its own path.
    private var baseEdited = false

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
        let urlString = request.url?.absoluteString ?? ""
        let breakpointMatch = WailoBreakpointStore.shared.match(url: urlString, method: method)
        let shouldCapture = WailoCaptureFilterStore.shared.shouldCapture(host: request.url?.host)
        let requestScript = WailoScriptStore.shared.matches(
            url: urlString, method: method, phase: .SCRIPT_PHASE_REQUEST
        )
        let responseScript = WailoScriptStore.shared.matches(
            url: urlString, method: method, phase: .SCRIPT_PHASE_RESPONSE
        )
        let declared = declaredRequestBodySize()
        let unknownStream = request.httpBody == nil
            && request.httpBodyStream != nil
            && request.value(forHTTPHeaderField: "Content-Length") == nil
        let scriptBodyCanBeBuffered = !unknownStream
            && declared >= 0
            && declared <= Int64(Self.maxScriptBodyBytes)
        if shouldCapture || breakpointMatch != nil || ((requestScript || responseScript) && scriptBodyCanBeBuffered) {
            requestBody = WailoURLProtocol.readBody(from: request)
        }

        guard requestScript, let transformer = WailoURLProtocol.scriptTransformer else {
            continueAfterRequestScripts()
            return
        }
        let input = scriptRequest()
        transformer.transform(
            phase: .SCRIPT_PHASE_REQUEST,
            request: input.request,
            response: nil,
            requestBodyReplayable: input.replayable
        ) { [weak self] result in
            guard let self, !self.stopped else { return }
            if let result, let transformed = result.request, transformed != input.request {
                self.outgoingRequest = transformed
                self.requestBodyReplaced = result.request_body_replaced
                self.baseEdited = true
            }
            self.continueAfterRequestScripts()
        }
    }

    private func continueAfterRequestScripts() {
        let current = outgoingRequest ?? captureRequest()
        let match = WailoBreakpointStore.shared.match(url: current.url, method: current.method)
        guard let match else {
            sourceResponse(edited: outgoingRequest)
            return
        }
        if match.onResponse { responseBreakpointRuleId = match.ruleId }
        if match.onRequest, let gate = WailoURLProtocol.breakpointGate {
            gate.pauseRequest(ruleId: match.ruleId, request: current) { [weak self] decision in
                guard let self, !self.stopped else { return }
                switch decision {
                case .abort:
                    self.abort()
                case let .proceed(edited):
                    if let edited {
                        self.outgoingRequest = edited
                        self.requestBodyReplaced = true
                        self.baseEdited = true
                    }
                    self.sourceResponse(edited: self.outgoingRequest)
                }
            }
            return
        }
        sourceResponse(edited: outgoingRequest)
    }

    override public func stopLoading() {
        stopped = true
        replayTask?.cancel()
        replayTask = nil
    }

    /// Sources the breakpoint's response (ADR-0033): from Map Local when a rule matches the (possibly edited)
    /// request — its value *becomes* the response the breakpoint shows — otherwise from the real network. A
    /// Map Local fetch miss falls open to the network. Either way the bytes flow through `finish`, so an armed
    /// response-phase breakpoint pauses on them uniformly. Matching the (possibly edited) request also subsumes
    /// the old edited-request re-check (ADR-0032).
    private func sourceResponse(edited: HttpRequest?) {
        if edited != nil { baseEdited = true }
        let url = edited?.url ?? request.url?.absoluteString
        let method = edited?.method ?? request.httpMethod ?? "GET"
        if let url,
           let rule = WailoRuleStore.shared.match(url: url, method: method),
           let fetcher = WailoURLProtocol.bodyFetcher {
            fetcher.fetchBody(ruleId: rule.id, url: url, method: method) { [weak self] mapped in
                guard let self, !self.stopped else { return }
                if let mapped {
                    self.finishMapped(mapped, edited: edited)
                } else {
                    self.sendToNetwork(edited: edited)
                }
            }
            return
        }
        sendToNetwork(edited: edited)
    }

    /// Sources the breakpoint's response from a Map Local rule: builds a synthetic HTTP response from the
    /// desktop's bytes and routes it through `finish`, so an armed response-phase breakpoint pauses on the
    /// mapped value exactly as it would on a network response (ADR-0033). No request ever leaves the device.
    private func finishMapped(_ mapped: WailoMappedResponse, edited: HttpRequest?) {
        baseEdited = true
        if responseBreakpointRuleId != nil {
            breakpointRequestContext = edited ?? captureRequest()
        }
        finish(data: mapped.body, response: makeResponse(code: mapped.code, headers: mapped.headers), error: nil)
    }

    /// Runs the real request (the normal, unmapped path). Marks the replay so `canInit` skips it,
    /// avoiding an infinite intercept loop, and forwards the result back to the caller via `finish`. When
    /// a request-phase breakpoint edited the request, [edited] carries the new method/URL/headers/body to
    /// send instead of the original.
    private func sendToNetwork(edited: HttpRequest? = nil) {
        guard let replay = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        if let edited {
            if let editedURL = URL(string: edited.url) { replay.url = editedURL }
            replay.httpMethod = edited.method
            if requestBodyReplaced {
                replay.httpBody = edited.body.isEmpty ? nil : edited.body
                replay.httpBodyStream = nil
            } else if let requestBody {
                replay.httpBody = requestBody
                replay.httpBodyStream = nil
            }
            replay.allHTTPHeaderFields = Dictionary(
                (requestBodyReplaced ? headersWithBodyLength(edited.headers, edited.body.count) : edited.headers)
                    .map { ($0.name, $0.value) },
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

    /// Answers the request from a Map Local rule instead of the network (the no-breakpoint path): hands the
    /// desktop-supplied status, headers, and body back to the caller, then mirrors the exchange into the sink
    /// flagged `edited`. No replay task is created, so no request ever leaves the device. When a breakpoint
    /// owns the exchange the mapped response instead flows through `finishMapped`/`finish` (ADR-0033).
    private func serveMapped(_ mapped: WailoMappedResponse) {
        let response = makeResponse(code: mapped.code, headers: mapped.headers)
        if let response {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        client?.urlProtocol(self, didLoad: mapped.body)
        client?.urlProtocolDidFinishLoading(self)
        emit(response: response, body: mapped.body, error: nil, edited: true)
    }

    /// Builds an `HTTPURLResponse` from desktop-supplied status + headers (a Map Local body or an edited
    /// response). Code 0 -> 200 so a value that omits the status still yields a valid response.
    private func makeResponse(code: Int, headers: [Header]) -> HTTPURLResponse? {
        let status = code == 0 ? 200 : code
        var headerFields: [String: String] = [:]
        for header in headers { headerFields[header.name] = header.value }
        return HTTPURLResponse(
            url: outgoingRequest.flatMap { URL(string: $0.url) }
                ?? request.url
                ?? URL(string: "about:blank")!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: headerFields
        )
    }

    /// Forwards the real result back to the caller, then mirrors a copy into the sink. If a response-phase
    /// breakpoint is armed and this is an HTTP response, it first pauses so the desktop can edit it, abort
    /// the call, or resume it unchanged (a disconnect fails open, delivering the original).
    private func finish(data: Data?, response: URLResponse?, error: Error?) {
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
            emit(response: nil, body: nil, error: error, edited: baseEdited)
            return
        }
        guard let http = response as? HTTPURLResponse else {
            finishAfterResponseScripts(data: data, response: response)
            return
        }
        let context = outgoingRequest ?? captureRequest()
        guard
            WailoScriptStore.shared.matches(
                url: context.url,
                method: context.method,
                phase: .SCRIPT_PHASE_RESPONSE
            ),
            let transformer = WailoURLProtocol.scriptTransformer
        else {
            finishAfterResponseScripts(data: data, response: response)
            return
        }
        let current = scriptResponse(http, body: data)
        transformer.transform(
            phase: .SCRIPT_PHASE_RESPONSE,
            request: context,
            response: current,
            requestBodyReplayable: scriptRequest().replayable
        ) { [weak self] result in
            guard let self, !self.stopped else { return }
            guard let result, let transformed = result.response else {
                self.finishAfterResponseScripts(data: data, response: response)
                return
            }
            if transformed == current {
                self.responseScriptDelayMs = result.delay_ms
                if result.delay_ms != 0 { self.baseEdited = true }
                self.finishAfterResponseScripts(data: data, response: response)
                return
            }
            if result.response_body_replaced && (data?.count ?? 0) > Self.maxScriptBodyBytes {
                self.finishAfterResponseScripts(data: data, response: response)
                return
            }
            let transformedData = result.response_body_replaced ? transformed.body : data
            let transformedResponse = self.makeResponse(
                code: Int(transformed.code),
                headers: result.response_body_replaced
                    ? self.headersWithBodyLength(transformed.headers, transformed.body.count)
                    : transformed.headers
            )
            self.responseScriptDelayMs = result.delay_ms
            if transformed != current || result.delay_ms != 0 { self.baseEdited = true }
            self.finishAfterResponseScripts(
                data: transformedData,
                response: transformedResponse ?? response
            )
        }
    }

    private func finishAfterResponseScripts(data: Data?, response: URLResponse?) {
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

    /// Delivers the result to the caller and mirrors it into the sink. Unedited on the network path, but the
    /// exchange is still flagged `edited` when it diverged upstream — a request-phase edit or a Map Local-
    /// sourced response resumed unchanged (ADR-0033) — via `baseEdited`.
    private func forwardOriginal(data: Data?, response: URLResponse?) {
        afterScriptDelay { [weak self] in
            guard let self, !self.stopped else { return }
            if let response {
                self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            }
            if let data {
                self.client?.urlProtocol(self, didLoad: data)
            }
            self.client?.urlProtocolDidFinishLoading(self)
            self.emit(response: response, body: data, error: nil, edited: self.baseEdited)
        }
    }

    /// Delivers a response-phase breakpoint's edited response: rebuilds an `HTTPURLResponse` from the
    /// desktop's status/headers and hands its body to the caller, flagging the exchange `edited`.
    private func forwardEdited(_ edited: HttpResponse) {
        let response = makeResponse(code: Int(edited.code), headers: edited.headers)
        afterScriptDelay { [weak self] in
            guard let self, !self.stopped else { return }
            if let response {
                self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            }
            self.client?.urlProtocol(self, didLoad: edited.body)
            self.client?.urlProtocolDidFinishLoading(self)
            self.emit(response: response, body: edited.body, error: nil, edited: true)
        }
    }

    private func afterScriptDelay(_ action: @escaping () -> Void) {
        let delay = max(0, Int(responseScriptDelayMs))
        guard delay > 0 else {
            action()
            return
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(delay), execute: action)
    }

    private func headersWithBodyLength(_ headers: [Header], _ size: Int) -> [Header] {
        headers.filter {
            $0.name.caseInsensitiveCompare("Content-Length") != .orderedSame
                && $0.name.caseInsensitiveCompare("Transfer-Encoding") != .orderedSame
                && $0.name.caseInsensitiveCompare("Content-Encoding") != .orderedSame
        } + [Header(name: "Content-Length", value: "\(size)")]
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

    private struct ScriptRequestInput {
        let request: HttpRequest
        let replayable: Bool
    }

    private func scriptRequest() -> ScriptRequestInput {
        let full = requestBodyReplaced ? outgoingRequest?.body : (requestBody ?? request.httpBody)
        let hasBody = full != nil
            || request.httpBodyStream != nil
            || declaredRequestBodySize() > 0
        let replayable = !hasBody || (full?.count ?? Int.max) <= Self.maxScriptBodyBytes
        let headers = (request.allHTTPHeaderFields ?? [:]).map { Header(name: $0.key, value: $0.value) }
        return ScriptRequestInput(
            request: HttpRequest(
                method: outgoingRequest?.method ?? request.httpMethod ?? "GET",
                url: outgoingRequest?.url ?? request.url?.absoluteString ?? "",
                body: replayable ? (full ?? Data()) : Data(),
                body_size: full.map { Int64($0.count) } ?? declaredRequestBodySize(),
                body_truncated: hasBody && !replayable
            ) {
                $0.headers = outgoingRequest?.headers ?? headers
            },
            replayable: replayable
        )
    }

    private func captureRequest() -> HttpRequest {
        if var outgoingRequest {
            if !requestBodyReplaced && requestBody == nil { return outgoingRequest }
            let full = requestBodyReplaced ? outgoingRequest.body : (requestBody ?? outgoingRequest.body)
            let (captured, truncated) = WailoURLProtocol.capturedBody(full)
            outgoingRequest.body = captured
            outgoingRequest.body_size = Int64(full.count)
            outgoingRequest.body_truncated = truncated
            return outgoingRequest
        }
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

    private func scriptResponse(_ response: HTTPURLResponse, body: Data?) -> HttpResponse {
        let full = body ?? Data()
        let available = full.count <= Self.maxScriptBodyBytes
        let headers = response.allHeaderFields.map { Header(name: "\($0.key)", value: "\($0.value)") }
        return HttpResponse(
            code: Int32(response.statusCode),
            message: "",
            body: available ? full : Data(),
            body_size: Int64(full.count),
            body_truncated: !available
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

    private static let maxScriptBodyBytes = 8 * 1024 * 1024
}
