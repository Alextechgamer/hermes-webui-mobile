import SwiftUI
import Combine

/// User-entered WebUI URL. Empty on first launch — nothing is hardcoded.
final class AppSettings: ObservableObject {
    @AppStorage("webuiURL") var webuiURL: String = ""
    @AppStorage("dashboardURL") var dashboardURL: String = ""
    @AppStorage("lastSid") var lastSid: String = ""
    @AppStorage("lastStreamId") var lastStreamId: String = ""
    @AppStorage("webuiPassword") private var storedPassword: String = ""
    @AppStorage("chatsExpanded") var chatsExpanded: Bool = false

    var password: String {
        get {
            if !storedPassword.isEmpty { return storedPassword }
            if let kc = WebUIKeychain.read(), !kc.isEmpty {
                storedPassword = kc
                return kc
            }
            return ""
        }
        set {
            storedPassword = newValue
            WebUIKeychain.write(newValue)
        }
    }

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
