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

/// A `URLProtocol` that copies every URLSession HTTP(S) exchange into a `CaptureSink`. It normally
/// leaves the real request/response untouched, but first checks the Map Local match-metadata the
/// desktop has pushed: on a match it fetches the response from the desktop (never a cached body —
/// ADR-0019) and answers with it, flagging the exchange `edited`. If that fetch fails for any reason it
/// falls open to the real network, so a desktop hiccup never hangs or fails the request. The iOS analog
/// of the OkHttp `WailoInterceptor`.
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
    nonisolated(unsafe) static var maxBodyBytes = 256 * 1024

    private lazy var session = URLSession(configuration: .ephemeral)
    // Not named `task`: URLProtocol already declares a read-only `task` property.
    private var replayTask: URLSessionDataTask?
    private var startedAtEpochMs: Int64 = 0
    private var startNanos: UInt64 = 0
    // Set when Foundation cancels this load, so a body-fetch that resolves afterward doesn't deliver to
    // a torn-down protocol. Best-effort (read off the client queue), which is enough to avoid late work.
    private var stopped = false

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

        // Map Local: match locally (metadata only), then fetch the body from the desktop and serve it.
        // Any failure (no fetcher, not connected, timeout, rule gone) falls open to the real network.
        if let url = request.url?.absoluteString,
           let rule = WailoRuleStore.shared.match(url: url, method: request.httpMethod ?? "GET"),
           let fetcher = WailoURLProtocol.bodyFetcher {
            fetcher.fetchBody(ruleId: rule.id, url: url, method: request.httpMethod ?? "GET") { [weak self] mapped in
                guard let self, !self.stopped else { return }
                if let mapped {
                    self.serveMapped(mapped)
                } else {
                    self.proceedToNetwork()
                }
            }
            return
        }

        proceedToNetwork()
    }

    override public func stopLoading() {
        stopped = true
        replayTask?.cancel()
        replayTask = nil
    }

    /// Runs the real request (the normal, unmapped path). Marks the replay so `canInit` skips it,
    /// avoiding an infinite intercept loop, and forwards the result back to the caller via `finish`.
    private func proceedToNetwork() {
        guard let replay = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        WailoURLProtocol.setProperty(true, forKey: WailoURLProtocol.handledKey, in: replay)

        replayTask = session.dataTask(with: replay as URLRequest) { [weak self] data, response, error in
            self?.finish(data: data, response: response, error: error)
        }
        replayTask?.resume()
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

    /// Forwards the real result back to the caller, then mirrors a copy into the sink.
    private func finish(data: Data?, response: URLResponse?, error: Error?) {
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
            emit(response: nil, body: nil, error: error)
            return
        }
        if let response {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        if let data {
            client?.urlProtocol(self, didLoad: data)
        }
        client?.urlProtocolDidFinishLoading(self)
        emit(response: response, body: data, error: nil)
    }

    private func emit(response: URLResponse?, body: Data?, error: Error?, edited: Bool = false) {
        guard let sink = WailoURLProtocol.sink else { return }
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
        let cap = WailoURLProtocol.maxBodyBytes
        // Bodies sent via httpBodyStream are not readable here (documented blind spot).
        let full = request.httpBody ?? Data()
        let truncated = full.count > cap
        let captured = truncated ? full.prefix(cap) : full
        let headers = (request.allHTTPHeaderFields ?? [:]).map { Header(name: $0.key, value: $0.value) }
        return HttpRequest(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "",
            body: Data(captured),
            body_size: Int64(full.count),
            body_truncated: truncated
        ) {
            $0.headers = headers
        }
    }

    private func captureResponse(_ response: HTTPURLResponse, body: Data?) -> HttpResponse {
        let cap = WailoURLProtocol.maxBodyBytes
        let declaredSize = response.expectedContentLength // -1 when unknown
        let full = body ?? Data()
        let captured = full.count > cap ? full.prefix(cap) : full
        let truncated = declaredSize >= 0 ? declaredSize > Int64(cap) : full.count >= cap
        let headers = response.allHeaderFields.map { Header(name: "\($0.key)", value: "\($0.value)") }
        return HttpResponse(
            code: Int32(response.statusCode),
            // URLSession never exposes the HTTP status line's reason phrase
            message: "",
            body: Data(captured),
            body_size: declaredSize,
            body_truncated: truncated
        ) {
            $0.headers = headers
        }
    }
}
