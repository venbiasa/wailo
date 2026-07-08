import SwiftUI
import Shared

struct ContentView: View {
    @State private var lines: [String] = []
    // Same shared Kotlin API the Android app uses; it has no idea Wailo is capturing it.
    private let api = SampleApi()

    var body: some View {
        NavigationStack {
            Group {
                if lines.isEmpty {
                    Text("Tap Send - the shared Kotlin module fires the requests; Wailo captures them "
                        + "and streams to the desktop.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding()
                } else {
                    List(lines, id: \.self) { Text($0).font(.callout) }
                }
            }
            .navigationTitle("Wailo KMP Sample")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Send") { Task { await send() } }
                }
            }
        }
    }

    @MainActor
    private func send() async {
        do {
            lines.insert(try await api.fetchTodo(), at: 0)
            lines.insert(try await api.createPost(), at: 0)
        } catch {
            lines.insert("error: \(error.localizedDescription)", at: 0)
        }
    }
}
