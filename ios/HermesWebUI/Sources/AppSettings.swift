import SwiftUI
import Combine

/// User-entered WebUI URL. Empty on first launch — nothing is hardcoded.
final class AppSettings: ObservableObject {
    @AppStorage("webuiURL") var webuiURL: String = ""
    @AppStorage("dashboardURL") var dashboardURL: String = ""

    var isConfigured: Bool {
        let s = webuiURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return !s.isEmpty
    }

    var normalizedURL: URL? {
        var s = webuiURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if !s.contains("://") { s = "http://\(s)" }
        return URL(string: s)
    }
}
