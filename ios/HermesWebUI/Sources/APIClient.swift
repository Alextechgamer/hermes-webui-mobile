import Foundation

/// Talks to hermes-webui (port 8787 typically). Cookies persist in HTTPCookieStorage.
final class APIClient {
    var baseURL: URL
    private var csrf: String = ""
    private let session: URLSession

    init(baseURL: URL) {
        self.baseURL = baseURL
        let cfg = URLSessionConfiguration.default
        cfg.httpCookieAcceptPolicy = .always
        cfg.httpShouldSetCookies = true
        cfg.httpCookieStorage = .shared
        self.session = URLSession(configuration: cfg)
    }

    private func url(_ path: String) -> URL {
        URL(string: path, relativeTo: baseURL.appendingPathComponent("/"))!
            .absoluteURL
    }

    private func request(_ method: String, _ path: String, json: Data? = nil) -> URLRequest {
        var r = URLRequest(url: url(path))
        r.httpMethod = method
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let json {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = json
        }
        if !csrf.isEmpty {
            r.setValue(csrf, forHTTPHeaderField: "X-Hermes-CSRF-Token")
        }
        return r
    }

    func getData(_ path: String) async throws -> Data {
        let (data, resp) = try await session.data(for: request("GET", path))
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 401 { throw URLError(.userAuthenticationRequired) }
        guard (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return data
    }

    func postJSON(_ path: String, body: [String: Any]) async throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await session.data(for: request("POST", path, json: json))
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 401 { throw URLError(.userAuthenticationRequired) }
        guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }

    func authStatus() async throws -> AuthStatus {
        let data = try await getData("/api/auth/status")
        return try JSONDecoder().decode(AuthStatus.self, from: data)
    }

    func login(password: String) async throws {
        _ = try await postJSON("/api/auth/login", body: ["password": password])
        try await refreshCSRF()
    }

    func refreshCSRF() async throws {
        let (data, _) = try await session.data(from: baseURL)
        guard let html = String(data: data, encoding: .utf8) else { return }
        // hermes-webui injects the token into the page for the session cookie.
        let patterns = [
            "csrf_token\"\\s*:\\s*\"([^\"]+)\"",
            "__CSRF_TOKEN_JSON__\\s*=\\s*\"([^\"]+)\"",
            "name=\"csrf-token\" content=\"([^\"]+)\"",
            "X-Hermes-CSRF-Token\"\\s*:\\s*\"([^\"]+)\"",
        ]
        for p in patterns {
            if let r = try? NSRegularExpression(pattern: p),
               let m = r.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
               let range = Range(m.range(at: 1), in: html) {
                csrf = String(html[range])
                return
            }
        }
    }

    func sessions() async throws -> [SessionRow] {
        let data = try await getData("/api/sessions")
        if let list = try? JSONDecoder().decode(SessionList.self, from: data),
           let items = list.items { return items }
        if let arr = try? JSONDecoder().decode([SessionRow].self, from: data) { return arr }
        if let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for k in ["sessions", "data", "items"] {
                if let raw = obj[k],
                   let d = try? JSONSerialization.data(withJSONObject: raw),
                   let rows = try? JSONDecoder().decode([SessionRow].self, from: d) {
                    return rows
                }
            }
        }
        return []
    }

    func sessionMessages(id: String) async throws -> (title: String, messages: [ChatMessage]) {
        let q = "/api/session?session_id=\(id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id)"
        let data = try await getData(q)
        let dec = JSONDecoder()
        if let p = try? dec.decode(SessionPayload.self, from: data) {
            let msgs = p.messages ?? p.session?.messages ?? []
            let title = p.title ?? p.session?.title ?? ""
            return (title, msgs)
        }
        return ("", [])
    }

    func newSession() async throws -> String {
        let data = try await postJSON("/api/session/new", body: [:])
        if let p = try? JSONDecoder().decode(SessionPayload.self, from: data) {
            return p.session_id ?? p.session?.session_id ?? ""
        }
        if let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let s = obj["session_id"] as? String { return s }
            if let sess = obj["session"] as? [String: Any], let s = sess["session_id"] as? String { return s }
        }
        return ""
    }

    func startChat(sessionId: String, message: String) async throws -> ChatStart {
        let data = try await postJSON("/api/chat/start", body: [
            "session_id": sessionId,
            "message": message,
        ])
        return try JSONDecoder().decode(ChatStart.self, from: data)
    }

    func settingsJSON() async throws -> [String: Any] {
        let data = try await getData("/api/settings")
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    func saveSettings(_ body: [String: Any]) async throws {
        _ = try await postJSON("/api/settings", body: body)
    }
}
