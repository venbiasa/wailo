import Foundation
import WailoProtocol

/// A `URLProtocol` that copies every URLSession HTTP(S) exchange into a `CaptureSink` without
/// altering the real request/response. The iOS analog of the OkHttp `WailoInterceptor`.
///
/// URLProtocol instances are created by Foundation, so there is no constructor to hand a sink to —
/// the sink is process-global (`Wailo.start` sets it), mirroring `WailoRuntime` on Android.
public final class WailoURLProtocol: URLProtocol {

    /// Marks requests we've already taken so our own replay task isn't intercepted again.
    private static let handledKey = "com.venbiasa.wailo.handled"

    // Set once at startup by `Wailo.start`. Written before any capture begins and only read after,
    // so plain statics are safe here.
    nonisolated(unsafe) static var sink: CaptureSink?
    nonisolated(unsafe) static var maxBodyBytes = 256 * 1024

    private lazy var session = URLSession(configuration: .ephemeral)
    // Not named `task`: URLProtocol already declares a read-only `task` property.
    private var replayTask: URLSessionDataTask?
    private var startedAtEpochMs: Int64 = 0
    private var startNanos: UInt64 = 0

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

    override public func stopLoading() {
        replayTask?.cancel()
        replayTask = nil
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

    private func emit(response: URLResponse?, body: Data?, error: Error?) {
        guard let sink = WailoURLProtocol.sink else { return }
        let durationMs = Int64((DispatchTime.now().uptimeNanoseconds &- startNanos) / 1_000_000)
        let capturedRequest = captureRequest()
        let httpResponse = response as? HTTPURLResponse
        let capturedResponse = httpResponse.map { captureResponse($0, body: body) }

        let exchange = HttpExchange(
            id: UUID().uuidString,
            started_at_epoch_ms: startedAtEpochMs,
            duration_ms: durationMs,
            error: error.map { ($0 as NSError).localizedDescription } ?? ""
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
            message: HTTPURLResponse.localizedString(forStatusCode: response.statusCode),
            body: Data(captured),
            body_size: declaredSize,
            body_truncated: truncated
        ) {
            $0.headers = headers
        }
    }
}
