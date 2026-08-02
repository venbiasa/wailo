import SwiftUI
import WailoSDK

/// Outcome of a request the sample fired. Wailo captures each of these under the hood and streams a
/// full `HttpExchange` to the desktop; this row shows only what the app itself sees, so the on-device
/// list visibly correlates with what appears in the desktop inspector.
struct FiredRequest: Identifiable {
    let id = UUID()
    let label: String
    let method: String
    let url: String
    let outcome: String
}

@MainActor
final class RequestRunner: ObservableObject {
    @Published var results: [FiredRequest] = []

    // A session a third-party library might build itself: captured via the URLSessionConfiguration
    // swizzle Wailo.start installed — no per-session wiring needed.
    private let customSession = URLSession(configuration: .default)

    func sendAll() {
        fire(URLSession.shared, get("https://jsonplaceholder.typicode.com/todos/1"), label: "shared")

        var post = get("https://jsonplaceholder.typicode.com/posts")
        post.httpMethod = "POST"
        post.setValue("application/json", forHTTPHeaderField: "Content-Type")
        post.httpBody = Data(#"{"title":"wailo","body":"hello","userId":1}"#.utf8)
        fire(customSession, post, label: "custom")
    }

    private func get(_ url: String) -> URLRequest { URLRequest(url: URL(string: url)!) }

    private func fire(_ session: URLSession, _ request: URLRequest, label: String) {
        session.dataTask(with: request) { [weak self] _, response, error in
            let outcome = (response as? HTTPURLResponse).map { "HTTP \($0.statusCode)" }
                ?? error?.localizedDescription ?? "?"
            let fired = FiredRequest(
                label: label,
                method: request.httpMethod ?? "GET",
                url: request.url?.absoluteString ?? "",
                outcome: outcome
            )
            Task { @MainActor [weak self] in self?.results.insert(fired, at: 0) }
        }.resume()
    }
}

struct ContentView: View {
    @StateObject private var runner = RequestRunner()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text("Wailo captures every URLSession request and streams it to the desktop. "
                    + "Tap Send, then watch the desktop app (and the Xcode console). "
                    + "Two-finger long-press the bottom half — Option-press in the Simulator — "
                    + "to pick a desktop.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding()

                if runner.results.isEmpty {
                    Spacer()
                    Image(systemName: "network")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("Tap Send requests to fire a GET and a POST.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                    Spacer()
                } else {
                    List(runner.results) { result in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(result.method) \(result.url)")
                                .font(.callout)
                                .lineLimit(1)
                            Text("[\(result.label)] \(result.outcome)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Wailo Sample")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Send requests") { runner.sendAll() }
                }
            }
        }
    }
}
