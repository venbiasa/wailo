import WailoProtocol

/// Prints a one-line summary of each exchange. The iOS analog of Android's `LogcatSink`, used for
/// M1-style local verification before the desktop is wired up.
public struct ConsoleSink: CaptureSink {
    public init() {}

    public func onExchange(_ exchange: HttpExchange) {
        let method = exchange.request?.method ?? "?"
        let url = exchange.request?.url ?? "?"
        let outcome: String
        if let response = exchange.response {
            outcome = "HTTP \(response.code)"
        } else if !exchange.error.isEmpty {
            outcome = exchange.error
        } else {
            outcome = "?"
        }
        print("Wailo: \(method) \(url) -> \(outcome) (\(exchange.duration_ms)ms)")
    }
}
