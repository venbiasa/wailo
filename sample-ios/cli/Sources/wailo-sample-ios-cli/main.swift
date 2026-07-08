import Foundation
import WailoSDK

// Start Wailo once, as early as possible — the same call the SwiftUI app makes in its App init.
// localhost:8899 reaches the desktop engine directly from the Simulator (no adb-reverse needed);
// for a physical device pass the Mac's LAN IP as `host`. Until the desktop is up the client just
// buffers and retries, and ConsoleSink still prints each captured exchange locally.
Wailo.start(
    appId: "com.venbiasa.wailo.sample-ios",
    deviceName: "wailo-sample-ios-cli"
)

// Captured via URLProtocol.registerClass (covers URLSession.shared).
let sharedSession = URLSession.shared
// A session a third-party library might build itself: captured via the URLSessionConfiguration
// swizzle installed by Wailo.start. No per-session wiring needed.
let customSession = URLSession(configuration: .default)

let group = DispatchGroup()

func fire(_ session: URLSession, _ request: URLRequest, label: String) {
    group.enter()
    session.dataTask(with: request) { _, response, error in
        defer { group.leave() }
        let outcome = (response as? HTTPURLResponse).map { "HTTP \($0.statusCode)" }
            ?? error?.localizedDescription ?? "?"
        print("[\(label)] \(request.httpMethod ?? "GET") \(request.url?.absoluteString ?? "") -> \(outcome)")
    }.resume()
}

fire(sharedSession, URLRequest(url: URL(string: "https://jsonplaceholder.typicode.com/todos/1")!), label: "shared")

var post = URLRequest(url: URL(string: "https://jsonplaceholder.typicode.com/posts")!)
post.httpMethod = "POST"
post.setValue("application/json", forHTTPHeaderField: "Content-Type")
post.httpBody = Data(#"{"title":"wailo","body":"hello","userId":1}"#.utf8)
fire(customSession, post, label: "custom")

group.wait()
// Let the WebSocket flush buffered exchanges to the desktop before exiting.
Thread.sleep(forTimeInterval: 2)
Wailo.stop()
