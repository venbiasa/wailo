import WailoProtocol

/// Where captured exchanges are delivered; keeps the interceptor from hard-coding a transport.
/// The Swift analog of `core.CaptureSink` on Android — same role, same protobuf payload.
public protocol CaptureSink: Sendable {
    func onExchange(_ exchange: HttpExchange)
}

/// Fan-out to several sinks, e.g. stream to the desktop while also logging locally.
public struct CompositeSink: CaptureSink {
    private let sinks: [CaptureSink]

    public init(_ sinks: [CaptureSink]) {
        self.sinks = sinks
    }

    public func onExchange(_ exchange: HttpExchange) {
        for sink in sinks {
            sink.onExchange(exchange)
        }
    }
}

/// Mirrors Android's `CaptureSink.plus`: `stream + ConsoleSink()`.
public func + (lhs: CaptureSink, rhs: CaptureSink) -> CaptureSink {
    CompositeSink([lhs, rhs])
}
