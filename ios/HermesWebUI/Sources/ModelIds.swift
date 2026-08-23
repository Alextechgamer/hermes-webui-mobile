import Foundation

/// Provider model ids as the upstream API expects them.
///
/// Desktop always POSTs `model` + `model_provider` on `/api/chat/start`.
/// Without the provider, Hermes can treat a Grok id like Claude and
/// rewrite `grok-4.6` → `grok-4-6`, which xAI 404s.
enum ModelIds {
    static func forSend(_ id: String, provider: String = "") -> String {
        let raw = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty, isGrokFamily(raw, provider: provider) else { return raw }
        let slash = raw.lastIndex(of: "/")
        let prefix = slash.map { String(raw[...$0]) } ?? ""
        var bare = slash.map { String(raw[raw.index(after: $0)...]) } ?? raw
        let re = try! NSRegularExpression(pattern: #"(\d)-(\d)"#)
        for _ in 0..<4 {
            let range = NSRange(bare.startIndex..<bare.endIndex, in: bare)
            let next = re.stringByReplacingMatches(in: bare, range: range, withTemplate: "$1.$2")
            if next == bare { break }
            bare = next
        }
        return prefix + bare
    }

    static func isGrokFamily(_ id: String, provider: String = "") -> Bool {
        let p = provider.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if p.contains("xai") || p == "x-ai" { return true }
        let bare = id.split(separator: "/").last.map(String.init) ?? id
        return bare.lowercased().hasPrefix("grok")
    }
}
