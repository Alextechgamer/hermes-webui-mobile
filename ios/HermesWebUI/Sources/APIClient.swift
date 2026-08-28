import Foundation
import UniformTypeIdentifiers

/// Talks to hermes-webui (port 8787 typically). Cookies persist on this session.
final class APIClient {
    var baseURL: URL
    private var csrf: String = ""
    private let session: URLSession

    var passwordProvider: () -> String = { "" }
    var onAuthLost: (() -> Void)?

    init(baseURL: URL) {
        self.baseURL = baseURL
        let cfg = URLSessionConfiguration.default
        cfg.httpCookieAcceptPolicy = .always
        cfg.httpShouldSetCookies = true
        cfg.httpCookieStorage = HTTPCookieStorage.shared
        self.session = URLSession(configuration: cfg)
    }

    private func url(_ path: String) -> URL {
        URL(string: path, relativeTo: baseURL.appendingPathComponent("/"))!.absoluteURL
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
        try await execute(request("GET", path), retryAuth: true)
    }

    func sendJSON(_ method: String, _ path: String, body: [String: Any], retryAuth: Bool = true) async throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: body)
        return try await execute(request(method, path, json: json), retryAuth: retryAuth)
    }

    func postJSON(_ path: String, body: [String: Any]) async throws -> Data {
        try await sendJSON("POST", path, body: body)
    }

    private func execute(_ req: URLRequest, retryAuth: Bool) async throws -> Data {
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 401 {
            if retryAuth {
                let pw = passwordProvider()
                if !pw.isEmpty {
                    do {
                        try await login(password: pw)
                        // login() refreshed the CSRF token; the original request still
                        // carries the stale header, so rewrite it before retrying.
                        var retryReq = req
                        if !csrf.isEmpty {
                            retryReq.setValue(csrf, forHTTPHeaderField: "X-Hermes-CSRF-Token")
                        }
                        return try await execute(retryReq, retryAuth: false)
                    } catch {
                        onAuthLost?()
                        throw URLError(.userAuthenticationRequired)
                    }
                }
            }
            onAuthLost?()
            throw URLError(.userAuthenticationRequired)
        }
        guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }

    func authStatus() async throws -> AuthStatus {
        try JSONDecoder().decode(AuthStatus.self, from: try await getData("/api/auth/status"))
    }

    func login(password: String) async throws {
        _ = try await sendJSON("POST", "/api/auth/login", body: ["password": password], retryAuth: false)
        try await refreshCSRF()
    }

    func refreshCSRF() async throws {
        let (data, _) = try await session.data(from: baseURL)
        guard let html = String(data: data, encoding: .utf8) else { return }
        let patterns = [
            "csrf_token\"\\s*:\\s*\"([^\"]+)\"",
            "__CSRF_TOKEN_JSON__\\s*=\\s*\"([^\"]+)\"",
            "name=\"csrf-token\" content=\"([^\"]+)\"",
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

    func sessions(source: String = "", includeArchived: Bool = false) async throws -> SessionsResult {
        var parts: [String] = []
        if source == "cli" { parts.append("source=cli") }
        if includeArchived { parts.append("include_archived=1") }
        let qs = parts.isEmpty ? "" : "?" + parts.joined(separator: "&")
        let data = try await getData("/api/sessions\(qs)")
        let obj = try JSONSerialization.jsonObject(with: data)
        var raw: [Any] = []
        var webuiCount = 0
        var cliCount = 0
        if let arr = obj as? [Any] { raw = arr }
        else if let d = obj as? [String: Any] {
            for k in ["sessions", "data", "items"] {
                if let arr = d[k] as? [Any] { raw = arr; break }
            }
            webuiCount = (d["webui_session_count"] as? Int) ?? 0
            cliCount = (d["cli_session_count"] as? Int) ?? 0
        }
        let rows = raw.compactMap { item -> SessionRow? in
            guard let d = item as? [String: Any] else { return nil }
            let sid = str(d, "session_id", "id")
            if sid.isEmpty { return nil }
            var row = SessionRow()
            row.session_id = sid
            row.raw_id = str(d, "id")
            row.title = first(d, "title")
            row.preview = first(d, "preview", "snippet", "last_message")
            row.messages = intOpt(d, "messages")
            row.message_count = intOpt(d, "message_count")
            row.source = first(d, "source")
            row.updated_at = first(d, "updated_at")
            row.model = first(d, "model")
            if let b = d["pinned"] as? Bool { row.pinned = b }
            row.archived = (d["archived"] as? Bool) ?? false
            row.projectId = (d["project_id"] as? String) ?? ""
            return row
        }
        return SessionsResult(rows: rows, webuiCount: webuiCount, cliCount: cliCount)
    }

    func loadSession(id: String, full: Bool = false) async throws -> SessionLoad {
        let qid = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        let extra = full ? "&full=1" : "&msg_limit=80"
        let data = try await getData("/api/session?session_id=\(qid)&messages=1&resolve_model=0\(extra)")
        let obj = (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let sess = obj["session"] as? [String: Any] ?? obj
        let rawMsgs = (sess["messages"] as? [Any]) ?? (obj["messages"] as? [Any]) ?? []
        let msgs = rawMsgs.enumerated().flatMap { i, item -> [ChatMessage] in
            guard let m = item as? [String: Any] else { return [] }
            return parseChatRows(m, fallbackId: "\(i)")
        }
        let todoObj = (sess["todo_state"] as? [String: Any]) ?? (obj["todo_state"] as? [String: Any])
        let todos = ((todoObj?["todos"] as? [[String: Any]]) ?? []).enumerated().map { i, t in
            TodoItem(
                id: (t["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "\(i)",
                content: first(t, "content", "text", "title") ?? "",
                status: (t["status"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "pending"
            )
        }
        return SessionLoad(
            title: (sess["title"] as? String) ?? (obj["title"] as? String) ?? "",
            model: (sess["model"] as? String) ?? (obj["model"] as? String) ?? "",
            messages: msgs,
            todos: todos,
            truncated: (sess["_messages_truncated"] as? Bool) ?? (obj["_messages_truncated"] as? Bool) ?? false,
            activeStreamId: (sess["active_stream_id"] as? String) ?? (obj["active_stream_id"] as? String) ?? ""
        )
    }

    private func parseChatRows(_ m: [String: Any], fallbackId: String = "0") -> [ChatMessage] {
        let role = (m["role"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "assistant"
        let content = textValue(m["content"]) ?? first(m, "content", "text") ?? ""
        let tool = first(m, "tool_name", "name", "tool") ?? ""
        let baseId: String
        if let n = m["id"] as? Int64 { baseId = "\(n)" }
        else if let n = m["id"] as? Int { baseId = "\(n)" }
        else if let s = m["id"] as? String, !s.isEmpty { baseId = s }
        else { baseId = "\(role)-\(fallbackId)" }
        var out: [ChatMessage] = []
        if let calls = m["tool_calls"] as? [[String: Any]] {
            for (i, call) in calls.enumerated() {
                let fn = call["function"] as? [String: Any]
                let name = first(call, "name", "tool") ?? first(fn ?? [:], "name") ?? "tool"
                let args = first(call, "arguments") ?? first(fn ?? [:], "arguments") ?? first(call, "args", "input") ?? ""
                let preview = first(call, "preview", "snippet", "result") ?? String(args.prefix(180))
                out.append(ChatMessage(id: "\(baseId)-tc-\(i)", role: "tool", content: args, tool: name, preview: preview))
            }
        }
        switch role {
        case "thinking", "reasoning":
            if !content.isEmpty { out.append(ChatMessage(id: baseId, role: "thinking", content: content)) }
        case "tool":
            out.append(ChatMessage(id: baseId, role: "tool", content: content, tool: tool.isEmpty ? "tool" : tool, preview: String(content.prefix(240))))
        case "user":
            out.append(ChatMessage(id: baseId, role: "user", content: content))
        case "system":
            if !content.isEmpty { out.append(ChatMessage(id: baseId, role: "system", content: content)) }
        default:
            if !content.isEmpty {
                out.append(ChatMessage(id: baseId, role: "assistant", content: content))
            } else if out.isEmpty && !tool.isEmpty {
                out.append(ChatMessage(id: baseId, role: "tool", content: "", tool: tool))
            }
        }
        return out
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

    func deleteSession(id: String) async {
        _ = try? await postJSON("/api/session/delete", body: ["session_id": id])
    }

    func pinSession(id: String, pinned: Bool) async {
        _ = try? await postJSON("/api/session/pin", body: ["session_id": id, "pinned": pinned])
    }

    func archiveSession(id: String, archived: Bool) async {
        _ = try? await postJSON("/api/session/archive", body: ["session_id": id, "archived": archived])
    }

    func renameSession(id: String, title: String) async {
        _ = try? await postJSON("/api/session/rename", body: ["session_id": id, "title": title])
    }

    func duplicateSession(id: String) async -> String {
        guard let data = try? await postJSON("/api/session/duplicate", body: ["session_id": id]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if let s = obj["session"] as? [String: Any] { return (s["session_id"] as? String) ?? "" }
        return (obj["session_id"] as? String) ?? ""
    }

    func moveSession(id: String, projectId: String?) async {
        var body: [String: Any] = ["session_id": id]
        body["project_id"] = projectId as Any? ?? NSNull()
        _ = try? await postJSON("/api/session/move", body: body)
    }

    func clearSession(id: String) async {
        _ = try? await postJSON("/api/session/clear", body: ["session_id": id])
    }

    func shareCreate(id: String) async -> String {
        guard let data = try? await postJSON("/api/share/create", body: ["session_id": id]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if let share = obj["share"] as? [String: Any] { return (share["url"] as? String) ?? "" }
        return (obj["url"] as? String) ?? ""
    }

    func branchSession(id: String, title: String = "") async -> String {
        var body: [String: Any] = ["session_id": id]
        if !title.isEmpty { body["title"] = title }
        guard let data = try? await postJSON("/api/session/branch", body: body),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if let s = obj["session"] as? [String: Any] { return (s["session_id"] as? String) ?? "" }
        return (obj["session_id"] as? String) ?? ""
    }

    func importSession(jsonText: String) async -> String {
        guard let raw = jsonText.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
              let data = try? await postJSON("/api/session/import", body: obj),
              let res = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if let s = res["session"] as? [String: Any] { return (s["session_id"] as? String) ?? "" }
        return (res["session_id"] as? String) ?? ""
    }

    func searchSessions(q: String) async -> [SessionRow] {
        guard !q.isEmpty, let o = try? await dict("/api/sessions/search?q=\(self.q(q))") else { return [] }
        return (o["sessions"] as? [[String: Any]] ?? []).compactMap { d in
            var row = SessionRow()
            row.session_id = (d["session_id"] as? String) ?? (d["id"] as? String)
            row.title = d["title"] as? String
            row.preview = (d["preview"] as? String) ?? (d["snippet"] as? String)
            row.messages = d["messages"] as? Int
            row.message_count = d["message_count"] as? Int
            row.source = d["source"] as? String
            row.pinned = d["pinned"] as? Bool
            row.archived = (d["archived"] as? Bool) ?? false
            row.projectId = (d["project_id"] as? String) ?? ""
            return row.sid.isEmpty ? nil : row
        }
    }

    func downloadExport(id: String, format: String) async throws -> Data {
        let qs = format == "html" ? "?session_id=\(q(id))&format=html" : "?session_id=\(q(id))"
        return try await getData("/api/session/export\(qs)")
    }

    func mcpServers() async -> [McpServer] {
        guard let o = try? await dict("/api/mcp/servers") else { return [] }
        return (o["servers"] as? [[String: Any]] ?? []).compactMap { s in
            let name = (s["name"] as? String) ?? (s["id"] as? String) ?? ""
            guard !name.isEmpty else { return nil }
            let enabled = ((s["enabled"] as? Bool) ?? true) && !((s["disabled"] as? Bool) ?? false)
            return McpServer(name: name, enabled: enabled, description: (s["description"] as? String) ?? "")
        }
    }

    func personalities() async -> [PersonalityRow] {
        guard let o = try? await dict("/api/personalities") else { return [] }
        return (o["personalities"] as? [[String: Any]] ?? []).compactMap { p in
            let name = (p["name"] as? String) ?? ""
            guard !name.isEmpty else { return nil }
            return PersonalityRow(name: name, description: (p["description"] as? String) ?? "")
        }
    }

    func setPersonality(sid: String, name: String) async {
        _ = try? await postJSON("/api/personality/set", body: ["session_id": sid, "name": name])
    }

    func setDefaultModel(provider: String, model: String) async {
        _ = try? await postJSON("/api/model/set", body: ["scope": "main", "task": "", "provider": provider, "model": model, "advanced": [String: Any]()])
    }

    func auxModels() async -> [AuxModelRow] {
        guard let o = try? await dict("/api/model/auxiliary") else { return [] }
        return (o["tasks"] as? [[String: Any]] ?? []).compactMap { t in
            let task = (t["task"] as? String) ?? ""
            guard !task.isEmpty else { return nil }
            return AuxModelRow(
                task: task,
                label: ((t["label"] as? String) ?? "").isEmpty ? task : (t["label"] as? String)!,
                provider: (t["provider"] as? String) ?? "",
                model: (t["model"] as? String) ?? "",
                description: (t["description"] as? String) ?? ""
            )
        }
    }

    func refreshModels(provider: String) async -> String {
        guard let data = try? await postJSON("/api/models/refresh", body: ["provider": provider]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "refresh failed" }
        if (obj["ok"] as? Bool) == true { return "ok" }
        return (obj["error"] as? String) ?? "refresh failed"
    }

    func deleteProviderKey(provider: String) async {
        _ = try? await postJSON("/api/providers/delete", body: ["provider": provider])
    }

    func extensionsRegistry() async -> [RegistryEntry] {
        guard let o = try? await dict("/api/extensions/registry") else { return [] }
        return (o["entries"] as? [[String: Any]] ?? []).compactMap { e in
            let id = (e["id"] as? String) ?? ""
            guard !id.isEmpty else { return nil }
            return RegistryEntry(
                id: id,
                name: ((e["name"] as? String) ?? "").isEmpty ? id : (e["name"] as? String)!,
                description: (e["description"] as? String) ?? "",
                version: (e["version"] as? String) ?? "",
                author: (e["author"] as? String) ?? "",
                downloadUrl: (e["download_url"] as? String) ?? (e["download"] as? String) ?? "",
                sha256: (e["sha256"] as? String) ?? ""
            )
        }
    }

    func extensionToggle(id: String, enabled: Bool) async {
        _ = try? await postJSON("/api/extensions/toggle", body: ["id": id, "enabled": enabled])
    }

    func extensionInstall(_ entry: RegistryEntry) async {
        var body: [String: Any] = ["id": entry.id]
        if !entry.downloadUrl.isEmpty { body["download_url"] = entry.downloadUrl }
        if !entry.sha256.isEmpty { body["sha256"] = entry.sha256 }
        _ = try? await postJSON("/api/extensions/install", body: body)
    }

    func extensionUninstall(id: String) async {
        _ = try? await postJSON("/api/extensions/uninstall", body: ["id": id])
    }

    func compressStart(sid: String) async -> String {
        guard let data = try? await postJSON("/api/session/compress/start", body: ["session_id": sid]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "started" }
        return (obj["status"] as? String) ?? "started"
    }

    func compressStatus(sid: String) async -> (String, String) {
        guard let o = try? await dict("/api/session/compress/status?session_id=\(q(sid))") else { return ("error", "status failed") }
        return ((o["status"] as? String) ?? "", (o["error"] as? String) ?? "")
    }

    func workspacesSuggest(prefix: String) async -> [String] {
        guard let o = try? await dict("/api/workspaces/suggest?prefix=\(q(prefix))") else { return [] }
        return (o["suggestions"] as? [String]) ?? []
    }

    func workspacesReorder(paths: [String]) async {
        _ = try? await postJSON("/api/workspaces/reorder", body: ["paths": paths])
    }

    func health() async -> HealthInfo {
        guard let o = try? await dict("/api/system/health") else { return HealthInfo() }
        let agent = try? await dict("/api/health/agent")
        let gw = try? await dict("/api/gateway/status")
        let runtime = o["webui_runtime"] as? [String: Any]
        func pct(_ key: String) -> Double {
            ((o[key] as? [String: Any])?["percent"] as? Double) ?? Double((o[key] as? [String: Any])?["percent"] as? Int ?? 0)
        }
        return HealthInfo(
            status: (o["status"] as? String) ?? "",
            cpuPct: pct("cpu"),
            memPct: pct("memory"),
            diskPct: pct("disk"),
            agentAlive: (agent?["alive"] as? Bool) ?? false,
            gatewayRunning: (gw?["running"] as? Bool) ?? false,
            residentSessions: ((runtime?["sessions"] as? [String: Any])?["resident"] as? Int) ?? 0,
            activeStreams: ((runtime?["streams"] as? [String: Any])?["active"] as? Int) ?? 0
        )
    }

    func cronsRunning() async -> Set<String> {
        guard let o = try? await dict("/api/crons/status"),
              let running = o["running"] as? [String: Any] else { return [] }
        return Set(running.keys)
    }

    func projects() async -> [ProjectRow] {
        guard let o = try? await dict("/api/projects") else { return [] }
        return (o["projects"] as? [[String: Any]] ?? []).compactMap { p in
            let id = (p["project_id"] as? String) ?? (p["id"] as? String) ?? ""
            guard !id.isEmpty else { return nil }
            return ProjectRow(id: id, name: (p["name"] as? String) ?? "project", color: (p["color"] as? String) ?? "")
        }
    }

    func createProject(name: String) async {
        _ = try? await postJSON("/api/projects/create", body: ["name": name])
    }

    func deleteProject(id: String) async {
        _ = try? await postJSON("/api/projects/delete", body: ["project_id": id])
    }

    func startChat(sessionId: String, message: String, model: String?, attachments: [String] = [], modelProvider: String? = nil) async throws -> ChatStart {
        var body: [String: Any] = ["session_id": sessionId, "message": message]
        if let model, !model.isEmpty {
            let send = ModelIds.forSend(model, provider: modelProvider ?? "")
            body["model"] = send
            body["explicit_model_pick"] = true
        }
        if let modelProvider, !modelProvider.isEmpty { body["model_provider"] = modelProvider }
        if !attachments.isEmpty { body["attachments"] = attachments }
        return try JSONDecoder().decode(ChatStart.self, from: try await postJSON("/api/chat/start", body: body))
    }

    func steer(sessionId: String, text: String) async -> Bool {
        guard !sessionId.isEmpty, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        do {
            let data = try await postJSON("/api/chat/steer", body: ["session_id": sessionId, "text": text])
            let obj = (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
            return (obj["accepted"] as? Bool) ?? false
        } catch {
            return false
        }
    }

    func upload(sid: String, fileURL: URL, filename: String, mime: String) async throws -> PendingAttach {
        var req = URLRequest(url: url("/api/upload"))
        req.httpMethod = "POST"
        let boundary = "Boundary-\(UUID().uuidString)"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        if !csrf.isEmpty { req.setValue(csrf, forHTTPHeaderField: "X-Hermes-CSRF-Token") }
        var data = Data()
        data.append("--\(boundary)\r\n".data(using: .utf8)!)
        data.append("Content-Disposition: form-data; name=\"session_id\"\r\n\r\n".data(using: .utf8)!)
        data.append("\(sid)\r\n".data(using: .utf8)!)
        let safeName = filename.replacingOccurrences(of: "\"", with: "_")
        data.append("--\(boundary)\r\n".data(using: .utf8)!)
        data.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(safeName)\"\r\n".data(using: .utf8)!)
        data.append("Content-Type: \(mime)\r\n\r\n".data(using: .utf8)!)
        data.append(try Data(contentsOf: fileURL))
        data.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        req.httpBody = data
        let (resp, http) = try await session.data(for: req)
        guard let r = http as? HTTPURLResponse, (200..<300).contains(r.statusCode) else { throw URLError(.badServerResponse) }
        let obj = (try JSONSerialization.jsonObject(with: resp) as? [String: Any]) ?? [:]
        if let err = obj["error"] as? String, !err.isEmpty { throw URLError(.cannotDecodeContentData) }
        let path = str(obj, "path")
        return PendingAttach(
            name: first(obj, "filename") ?? filename,
            path: path,
            mime: first(obj, "mime") ?? mime,
            isImage: bool(obj, "is_image", mime.hasPrefix("image/"))
        )
    }

    func providers() async throws -> [ProviderRow] {
        let o = try await dict("/api/providers")
        let arr = o["providers"] as? [[String: Any]] ?? o["items"] as? [[String: Any]] ?? []
        return arr.compactMap { p in
            let id = first(p, "id", "provider", "name") ?? ""
            guard !id.isEmpty else { return nil }
            return ProviderRow(
                id: id,
                displayName: first(p, "display_name", "label", "name") ?? id,
                hasKey: bool(p, "has_key", false) || bool(p, "configured", false) || bool(p, "logged_in", false),
                configurable: bool(p, "configurable", true),
                keySource: first(p, "key_source", "source") ?? ""
            )
        }
    }

    func setProviderKey(id: String, key: String) async {
        _ = try? await postJSON("/api/providers", body: ["provider": id, "api_key": key])
    }

    func plugins() async throws -> [PluginRow] {
        let o = try await dict("/api/plugins")
        return (o["plugins"] as? [[String: Any]] ?? []).compactMap { p in
            let name = first(p, "name", "id", "title") ?? ""
            guard !name.isEmpty else { return nil }
            let desc = String((first(p, "description", "summary", "hooks") ?? "").prefix(240))
            return PluginRow(name: name, description: desc, enabled: bool(p, "enabled", true))
        }
    }

    func extensions() async throws -> [ExtensionRow] {
        let o = try await dict("/api/extensions/status")
        let arr = o["extensions"] as? [[String: Any]] ?? o["installed"] as? [[String: Any]] ?? o["items"] as? [[String: Any]] ?? []
        return arr.compactMap { p in
            let id = first(p, "id", "name") ?? ""
            guard !id.isEmpty else { return nil }
            return ExtensionRow(
                id: id,
                name: first(p, "name", "title") ?? id,
                enabled: bool(p, "enabled", false) || bool(p, "active", false),
                description: String((first(p, "description", "summary") ?? "").prefix(240))
            )
        }
    }

    func prompts() async throws -> [SavedPrompt] {
        let o = try await dict("/api/prompts")
        return (o["prompts"] as? [[String: Any]] ?? []).compactMap { p in
            let text = first(p, "text", "content", "prompt") ?? ""
            guard !text.isEmpty else { return nil }
            let id = first(p, "id") ?? "\(text.hashValue)"
            return SavedPrompt(id: id, label: first(p, "label", "title") ?? String(text.prefix(48)), text: text)
        }
    }

    func cancelChat(sessionId: String) async {
        _ = try? await postJSON("/api/chat/cancel", body: ["session_id": sessionId])
    }

    func streamTokens(id: String, replay: Bool = false, afterSeq: Int64 = 0, afterEventId: String = "", onEvent: @escaping (String, String, String) -> Void) async -> Bool {
        let u = baseURL.appendingPathComponent("api/chat/stream")
        var comp = URLComponents(url: u, resolvingAgainstBaseURL: false)!
        var items = [URLQueryItem(name: "stream_id", value: id)]
        if replay { items.append(URLQueryItem(name: "replay", value: "1")) }
        if afterSeq > 0 { items.append(URLQueryItem(name: "after_seq", value: String(afterSeq))) }
        if !afterEventId.isEmpty { items.append(URLQueryItem(name: "after_event_id", value: afterEventId)) }
        comp.queryItems = items
        guard let url = comp.url else { return false }
        var req = URLRequest(url: url)
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 60 * 60
        do {
            let (bytes, _) = try await session.bytes(for: req)
            var event = ""
            var eid = ""
            for try await line in bytes.lines {
                if line.hasPrefix("id:") {
                    eid = String(line.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                } else if line.hasPrefix("event:") {
                    event = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                } else if line.hasPrefix("data:") {
                    onEvent(event, String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces), eid)
                } else if line.isEmpty {
                    event = ""; eid = ""
                }
            }
            return true
        } catch {
            return false
        }
    }

    func streamStatus(id: String) async -> Bool {
        ((try? await dict("/api/chat/stream/status?stream_id=\(q(id))"))?["active"] as? Bool) ?? false
    }

    func sessionStatus(sid: String) async -> (String, Int) {
        let o = (try? await dict("/api/session/status?session_id=\(q(sid))")) ?? [:]
        return ((o["active_stream_id"] as? String) ?? "", (o["message_count"] as? Int) ?? 0)
    }

    func streamSession(sid: String, knownCount: Int, onEvent: @escaping (String, String) -> Void) async {
        await streamPath("/api/session/stream?session_id=\(q(sid))&known_count=\(knownCount)", onEvent: onEvent)
    }

    func streamSessionList(onEvent: @escaping (String, String) -> Void) async {
        await streamPath("/api/sessions/events", onEvent: onEvent)
    }

    private func streamPath(_ path: String, onEvent: @escaping (String, String) -> Void) async {
        guard let url = URL(string: path, relativeTo: baseURL.appendingPathComponent("/"))?.absoluteURL else { return }
        var req = URLRequest(url: url)
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 60 * 60
        do {
            let (bytes, _) = try await session.bytes(for: req)
            var event = ""
            for try await line in bytes.lines {
                if line.hasPrefix("event:") {
                    event = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                } else if line.hasPrefix("data:") {
                    onEvent(event, String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces))
                } else if line.isEmpty {
                    event = ""
                }
            }
        } catch {}
    }

    func dict(_ path: String) async throws -> [String: Any] {
        let data = try await getData(path)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    func approval(sid: String) async -> Approval? {
        guard let obj = try? await dict("/api/approval/pending?session_id=\(q(sid))"),
              let p = obj["pending"] as? [String: Any] else { return nil }
        return Approval(
            approvalId: str(p, "approval_id", "id"),
            tool: first(p, "tool", "name", "function", "title") ?? "tool",
            detail: String((first(p, "description", "command", "detail", "preview") ?? "").prefix(400)),
            count: int(obj, "pending_count")
        )
    }

    func respondApproval(sid: String, id: String, choice: String) async {
        _ = try? await postJSON("/api/approval/respond", body: ["session_id": sid, "approval_id": id, "choice": choice])
    }

    func clarify(sid: String) async -> Clarify? {
        guard let obj = try? await dict("/api/clarify/pending?session_id=\(q(sid))"),
              let p = obj["pending"] as? [String: Any] else { return nil }
        let choices = (p["choices"] as? [Any] ?? p["choices_offered"] as? [Any] ?? []).compactMap { item -> String? in
            if let s = item as? String { return s }
            if let d = item as? [String: Any] { return d["label"] as? String ?? d["text"] as? String }
            return nil
        }
        return Clarify(clarifyId: str(p, "clarify_id", "id"), question: str(p, "question", "prompt", "text"), choices: choices)
    }

    func respondClarify(sid: String, id: String, response: String) async {
        _ = try? await postJSON("/api/clarify/respond", body: ["session_id": sid, "clarify_id": id, "response": response])
    }

    func crons() async throws -> [CronJob] {
        let obj = try await dict("/api/crons")
        return (obj["jobs"] as? [[String: Any]] ?? []).compactMap { j in
            let id = str(j, "id", "job_id")
            guard !id.isEmpty else { return nil }
            var schedule = first(j, "schedule_display") ?? ""
            if schedule.isEmpty, let s = j["schedule"] as? [String: Any] {
                schedule = (s["display"] as? String) ?? (s["expr"] as? String) ?? ""
            }
            if schedule.isEmpty, j["schedule"] is String { schedule = str(j, "schedule") }
            let paused = bool(j, "paused", false) || (j["state"] as? String)?.lowercased() == "paused" || j["paused_at"] != nil
            return CronJob(
                id: id,
                name: first(j, "name", "id") ?? "job",
                schedule: schedule,
                enabled: bool(j, "enabled", true),
                paused: paused,
                prompt: str(j, "prompt"),
                lastStatus: first(j, "last_status", "status", "last_error") ?? "",
                lastRun: stringify(j["last_run"] ?? j["last_run_at"] ?? ""),
                nextRun: stringify(j["next_run_at"] ?? j["next_run"] ?? ""),
                owner: first(j, "owner_profile", "profile") ?? "",
                readOnly: bool(j, "read_only", false),
                deliver: first(j, "deliver") ?? "local"
            )
        }
    }

    func createCron(name: String, schedule: String, prompt: String) async throws {
        var body: [String: Any] = ["schedule": schedule, "prompt": prompt]
        if !name.isEmpty { body["name"] = name }
        _ = try await postJSON("/api/crons/create", body: body)
    }

    func deleteCron(id: String) async {
        _ = try? await postJSON("/api/crons/delete", body: ["job_id": id])
    }

    func cronAction(id: String, action: String) async {
        _ = try? await postJSON("/api/crons/\(action)", body: ["job_id": id])
    }

    func cronOutput(id: String) async -> String {
        (try? String(data: try await getData("/api/crons/output?job_id=\(q(id))"), encoding: .utf8)) ?? ""
    }

    func cronHistory(id: String) async -> [CronRun] {
        guard let o = try? await dict("/api/crons/history?job_id=\(q(id))&limit=50") else { return [] }
        return (o["runs"] as? [[String: Any]] ?? []).compactMap { r in
            let name = (r["filename"] as? String) ?? (r["name"] as? String) ?? ""
            guard !name.isEmpty else { return nil }
            return CronRun(filename: name, size: (r["size"] as? Int) ?? 0, modified: "\(r["modified"] ?? "")")
        }
    }

    func updateCron(id: String, name: String, schedule: String, prompt: String, deliver: String) async {
        var body: [String: Any] = ["job_id": id, "schedule": schedule]
        if !name.isEmpty { body["name"] = name }
        if !prompt.isEmpty { body["prompt"] = prompt }
        if !deliver.isEmpty { body["deliver"] = deliver }
        _ = try? await postJSON("/api/crons/update", body: body)
    }

    func kanban(board: String = "", assignee: String = "", tenant: String = "", includeArchived: Bool = false, onlyMine: Bool = false) async throws -> [KanbanColumn] {
        let obj = try await dict("/api/kanban/board" + kanbanQs(board: board, assignee: assignee, tenant: tenant, includeArchived: includeArchived, onlyMine: onlyMine))
        return (obj["columns"] as? [[String: Any]] ?? []).map { c in
            let name = first(c, "name", "id", "title") ?? "column"
            let tasks = (c["tasks"] as? [[String: Any]] ?? []).compactMap { t -> KanbanTask? in
                let id = str(t, "id", "task_id")
                if id.isEmpty { return nil }
                return KanbanTask(
                    id: id,
                    title: first(t, "title", "name", "summary", "id") ?? "task",
                    status: first(t, "status") ?? name,
                    assignee: str(t, "assignee"),
                    priority: stringify(t["priority"] ?? ""),
                    body: str(t, "body", "description", "prompt"),
                    tenant: str(t, "tenant"),
                    comments: int(t, "comment_count")
                )
            }
            return KanbanColumn(name: name, tasks: tasks)
        }
    }

    func kanbanBoards() async -> (String, [KanbanBoardMeta]) {
        guard let obj = try? await dict("/api/kanban/boards") else { return ("", []) }
        let current = first(obj, "current", "active") ?? ""
        let list = (obj["boards"] as? [[String: Any]] ?? []).compactMap { b -> KanbanBoardMeta? in
            let slug = str(b, "slug", "id", "name")
            if slug.isEmpty { return nil }
            return KanbanBoardMeta(
                slug: slug,
                name: first(b, "name", "title") ?? slug,
                total: int(b, "total"),
                current: bool(b, "is_current", false) || slug == current
            )
        }
        let cur = list.first(where: { $0.current })?.slug ?? current
        return (cur, list)
    }

    func kanbanStats(board: String) async -> KanbanStats {
        guard let obj = try? await dict("/api/kanban/stats" + kanbanQs(board: board)) else { return KanbanStats() }
        var by: [String: Int] = [:]
        if let m = obj["by_status"] as? [String: Any] {
            for (k, v) in m { if let n = v as? Int { by[k] = n } else if let n = v as? NSNumber { by[k] = n.intValue } }
        }
        return KanbanStats(byStatus: by)
    }

    func kanbanAssignees(board: String) async -> [String] {
        guard let obj = try? await dict("/api/kanban/assignees" + kanbanQs(board: board)) else { return [] }
        return (obj["assignees"] as? [String]) ?? []
    }

    func switchKanbanBoard(_ slug: String) async {
        _ = try? await sendJSON("POST", "/api/kanban/boards/\(q(slug))/switch", body: [:])
    }

    func createKanbanBoard(name: String, slug: String) async {
        _ = try? await sendJSON("POST", "/api/kanban/boards", body: [
            "slug": slug, "name": name, "description": "", "icon": "", "color": "", "switch": true,
        ])
    }

    func bulkKanban(ids: [String], status: String, board: String) async {
        guard !ids.isEmpty else { return }
        _ = try? await sendJSON("POST", "/api/kanban/tasks/bulk" + kanbanQs(board: board), body: ["ids": ids, "status": status])
    }

    func createKanbanTask(title: String, board: String) async {
        _ = try? await sendJSON("POST", "/api/kanban/tasks" + kanbanQs(board: board), body: ["title": title])
    }

    func dispatchKanban(board: String, dryRun: Bool) async {
        var qs = kanbanQs(board: board)
        if dryRun { qs += qs.isEmpty ? "?dry_run=1" : "&dry_run=1" }
        _ = try? await sendJSON("POST", "/api/kanban/dispatch" + qs, body: [:])
    }

    func moveKanban(id: String, status: String, board: String = "") async {
        _ = try? await sendJSON("PATCH", "/api/kanban/tasks/\(q(id))" + kanbanQs(board: board), body: ["status": status])
    }

    private func kanbanQs(board: String = "", assignee: String = "", tenant: String = "", includeArchived: Bool = false, onlyMine: Bool = false) -> String {
        var parts: [String] = []
        if !board.isEmpty { parts.append("board=\(q(board))") }
        if !assignee.isEmpty { parts.append("assignee=\(q(assignee))") }
        if !tenant.isEmpty { parts.append("tenant=\(q(tenant))") }
        if includeArchived { parts.append("include_archived=1") }
        if onlyMine { parts.append("only_mine=1") }
        return parts.isEmpty ? "" : "?" + parts.joined(separator: "&")
    }

    func skills() async throws -> [SkillRow] {
        let obj = try await dict("/api/skills")
        return (obj["skills"] as? [[String: Any]] ?? []).compactMap { s in
            let name = str(s, "name")
            guard !name.isEmpty else { return nil }
            return SkillRow(name: name, description: str(s, "description"), category: str(s, "category"), disabled: bool(s, "disabled", false))
        }
    }

    func skillContent(name: String) async -> String {
        ((try? await dict("/api/skills/content?name=\(q(name))"))?["content"] as? String) ?? ""
    }

    func toggleSkill(name: String, enabled: Bool) async {
        _ = try? await postJSON("/api/skills/toggle", body: ["name": name, "enabled": enabled])
    }

    func memory() async throws -> MemoryDoc {
        let o = try await dict("/api/memory")
        return MemoryDoc(
            memory: o["memory"] as? String ?? "",
            user: o["user"] as? String ?? "",
            soul: o["soul"] as? String ?? "",
            project: o["project_context"] as? String ?? "",
            projectName: o["project_context_name"] as? String ?? ""
        )
    }

    func writeMemory(section: String, content: String) async {
        _ = try? await postJSON("/api/memory/write", body: ["section": section, "content": content])
    }

    func spaces() async throws -> [SpaceRow] {
        let o = try await dict("/api/workspaces")
        let last = o["last"] as? String ?? ""
        return (o["workspaces"] as? [Any] ?? []).compactMap { item in
            if let s = item as? String {
                return SpaceRow(name: (s as NSString).lastPathComponent, path: s, last: s == last)
            }
            if let d = item as? [String: Any] {
                let path = first(d, "path", "cwd", "dir") ?? ""
                return SpaceRow(name: first(d, "name", "label") ?? (path as NSString).lastPathComponent, path: path, last: path == last || bool(d, "last", false))
            }
            return nil
        }
    }

    func profiles() async throws -> (String, [ProfileRow]) {
        let o = try await dict("/api/profiles")
        let active = o["active"] as? String ?? ""
        let rows = (o["profiles"] as? [Any] ?? []).compactMap { item -> ProfileRow? in
            if let s = item as? String { return ProfileRow(name: s, model: "", active: s == active) }
            if let d = item as? [String: Any] {
                let name = first(d, "name", "id") ?? ""
                return ProfileRow(name: name, model: first(d, "model", "default_model") ?? "", active: name == active || bool(d, "active", false))
            }
            return nil
        }.filter { !$0.name.isEmpty }
        return (active, rows)
    }

    func switchProfile(name: String) async {
        _ = try? await postJSON("/api/profile/switch", body: ["name": name])
    }

    func createProfile(name: String) async throws {
        _ = try await postJSON("/api/profile/create", body: ["name": name])
    }

    func deleteProfile(name: String) async {
        _ = try? await postJSON("/api/profile/delete", body: ["name": name])
    }

    func addWorkspace(path: String) async throws {
        _ = try await postJSON("/api/workspaces/add", body: ["path": path])
    }

    func removeWorkspace(path: String) async {
        _ = try? await postJSON("/api/workspaces/remove", body: ["path": path])
    }

    func saveSkill(name: String, category: String, content: String) async throws {
        var body: [String: Any] = ["name": name, "content": content]
        if !category.isEmpty { body["category"] = category }
        _ = try await postJSON("/api/skills/save", body: body)
    }

    func deleteSkill(name: String) async {
        _ = try? await postJSON("/api/skills/delete", body: ["name": name])
    }

    func insights(days: Int = 30) async throws -> Insights {
        let o = try await dict("/api/insights?days=\(days)")
        let models = (o["models"] as? [[String: Any]] ?? []).map { m in
            InsightModel(
                model: str(m, "model"),
                sessions: int(m, "sessions"),
                tokens: int(m, "total_tokens"),
                cost: double(m, "cost"),
                cacheHitPct: m["cache_hit_percent"] as? Double,
                costShare: m["cost_share"] as? Double
            )
        }
        var skills: [SkillUsage] = []
        if let su = try? await dict("/api/skills/usage?days=\(days)"),
           let usage = su["usage"] as? [String: [String: Any]] {
            skills = usage.map { name, u in
                SkillUsage(name: name, uses: int(u, "use_count"), views: int(u, "view_count"), patches: int(u, "patch_count"))
            }.sorted { $0.uses > $1.uses }
            if skills.count > 20 { skills = Array(skills.prefix(20)) }
        }
        var hit: Double? = nil
        if let n = o["total_cache_hit_percent"] as? Double { hit = n }
        if let d = o["total_cache_hit_percent"] as? [String: Any] { hit = double(d, "value") }
        return Insights(
            days: int(o, "period_days") == 0 ? days : int(o, "period_days"),
            sessions: int(o, "total_sessions"),
            messages: int(o, "total_messages"),
            tokens: int(o, "total_tokens"),
            cost: double(o, "total_cost"),
            cacheHit: hit,
            models: models,
            skills: skills
        )
    }

    func logs(file: String, tail: Int = 200) async throws -> [String] {
        let o = try await dict("/api/logs?file=\(q(file))&tail=\(tail)")
        return o["lines"] as? [String] ?? []
    }

    func consoleUsage(dashBase: String) async throws -> ConsoleUsage {
        var s = dashBase.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasSuffix("/") { s.removeLast() }
        if !s.contains("://") { s = "http://\(s)" }
        guard let u = URL(string: s + "/api/usage") else { throw URLError(.badURL) }
        var r = URLRequest(url: u)
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: r)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(ConsoleUsage.self, from: data)
    }

    func costConfig(dashBase: String) async throws -> CostConfig {
        var s = dashBase.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasSuffix("/") { s.removeLast() }
        if !s.contains("://") { s = "http://\(s)" }
        guard let u = URL(string: s + "/api/cost-config") else { throw URLError(.badURL) }
        var r = URLRequest(url: u)
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: r)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(CostConfig.self, from: data)
    }

    func saveCostConfig(dashBase: String, body: [String: Any]) async throws {
        var s = dashBase.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasSuffix("/") { s.removeLast() }
        if !s.contains("://") { s = "http://\(s)" }
        guard let u = URL(string: s + "/api/cost-config") else { throw URLError(.badURL) }
        var r = URLRequest(url: u)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (_, resp) = try await session.data(for: r)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
    }

    func models() async -> (String, [ModelOption]) {
        guard let data = try? await getData("/api/models"),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return ("", []) }
        let defaultModel = first(root, "default_model", "model") ?? ""
        var out: [String: ModelOption] = [:]
        func add(provider: String, item: Any) {
            guard let d = item as? [String: Any] else { return }
            let id = first(d, "id", "model") ?? first(d, "name") ?? ""
            guard !id.isEmpty else { return }
            let label = first(d, "label", "name", "display") ?? id
            let prov = provider.isEmpty ? (id.split(separator: "/").first.map(String.init) ?? "") : provider
            if out[id] == nil { out[id] = ModelOption(id: id, label: label, provider: prov) }
        }
        if let groups = root["groups"] as? [[String: Any]] {
            for g in groups {
                let provider = first(g, "provider", "provider_id", "id", "name") ?? ""
                (g["models"] as? [Any])?.forEach { add(provider: provider, item: $0) }
                (g["extra_models"] as? [Any])?.forEach { add(provider: provider, item: $0) }
            }
        }
        let active = first(root, "active_provider") ?? ""
        (root["models"] as? [Any])?.forEach { add(provider: active, item: $0) }
        (root["extra_models"] as? [Any])?.forEach { add(provider: "extra", item: $0) }
        return (defaultModel, Array(out.values))
    }

    func reasoning(model: String, provider: String) async -> ReasoningStatus {
        var parts: [String] = []
        if !model.isEmpty { parts.append("model=\(model.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? model)") }
        if !provider.isEmpty { parts.append("provider=\(provider.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? provider)") }
        let qs = parts.isEmpty ? "/api/reasoning" : "/api/reasoning?" + parts.joined(separator: "&")
        guard let data = try? await getData(qs),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return ReasoningStatus()
        }
        return parseReasoning(obj)
    }

    func setReasoning(effort: String, model: String, provider: String) async -> ReasoningStatus {
        var body: [String: Any] = ["effort": effort]
        if !model.isEmpty { body["model"] = model }
        if !provider.isEmpty { body["provider"] = provider }
        guard let data = try? await postJSON("/api/reasoning", body: body),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return ReasoningStatus(effort: effort)
        }
        return parseReasoning(obj)
    }

    private func parseReasoning(_ obj: [String: Any]) -> ReasoningStatus {
        let efforts = (obj["supported_efforts"] as? [Any])?.compactMap { $0 as? String } ?? []
        let toggle = (obj["supports_thinking_toggle"] as? Bool) ?? true
        return ReasoningStatus(
            effort: first(obj, "reasoning_effort", "effort") ?? "",
            supported: efforts,
            showToggle: toggle || !efforts.isEmpty
        )
    }

    func settings() async throws -> [SettingItem] {
        let data = try await getData("/api/settings")
        let root = (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let obj = (root["settings"] as? [String: Any]) ?? root
        return obj.keys.sorted().compactMap { k in
            if secretKey(k) { return nil }
            let v = obj[k]
            if let b = v as? Bool { return SettingItem(key: k, type: "bool", value: b ? "true" : "false") }
            if let n = v as? NSNumber { return SettingItem(key: k, type: "number", value: n.stringValue) }
            if let s = v as? String { return SettingItem(key: k, type: "string", value: s) }
            return SettingItem(key: k, type: "json", value: String(describing: v ?? ""))
        }
    }

    func saveSettings(_ body: [String: Any]) async {
        _ = try? await postJSON("/api/settings", body: body)
    }

    func listDir(sid: String, path: String) async throws -> (String, [FsEntry]) {
        let o = try await dict("/api/list?session_id=\(q(sid))&path=\(q(path))")
        let entries = (o["entries"] as? [[String: Any]] ?? []).compactMap { e -> FsEntry? in
            let name = str(e, "name")
            guard !name.isEmpty else { return nil }
            let isDir = bool(e, "is_dir", false) || (e["type"] as? String) == "dir"
            return FsEntry(name: name, path: first(e, "path") ?? name, isDir: isDir, size: int(e, "size"))
        }.sorted { a, b in
            if a.isDir != b.isDir { return a.isDir }
            return a.name.lowercased() < b.name.lowercased()
        }
        return (o["workspace"] as? String ?? "", entries)
    }

    func readFile(sid: String, path: String) async throws -> FileDoc {
        let o = try await dict("/api/file?session_id=\(q(sid))&path=\(q(path))")
        return FileDoc(path: first(o, "path") ?? path, content: o["content"] as? String ?? "", lines: int(o, "lines"))
    }

    func saveFile(sid: String, path: String, content: String) async {
        _ = try? await postJSON("/api/file/save", body: ["session_id": sid, "path": path, "content": content])
    }

    func createFile(sid: String, path: String) async {
        _ = try? await postJSON("/api/file/create", body: ["session_id": sid, "path": path, "content": ""])
    }

    func createDir(sid: String, path: String) async {
        _ = try? await postJSON("/api/file/create-dir", body: ["session_id": sid, "path": path])
    }

    func deleteFile(sid: String, path: String) async {
        _ = try? await postJSON("/api/file/delete", body: ["session_id": sid, "path": path, "recursive": true])
    }

    func renameFile(sid: String, path: String, newName: String) async {
        _ = try? await postJSON("/api/file/rename", body: ["session_id": sid, "path": path, "new_name": newName])
    }

    func moveFile(sid: String, path: String, destDir: String) async {
        _ = try? await postJSON("/api/file/move", body: ["session_id": sid, "path": path, "dest_dir": destDir])
    }

    func retrySession(id: String) async {
        _ = try? await postJSON("/api/session/retry", body: ["session_id": id])
    }

    func undoSession(id: String) async {
        _ = try? await postJSON("/api/session/undo", body: ["session_id": id])
    }

    func regenerateTitle(id: String) async -> String {
        guard let data = try? await postJSON("/api/session/title/regenerate", body: ["session_id": id]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if let t = obj["title"] as? String, !t.isEmpty { return t }
        if let s = obj["session"] as? [String: Any] { return (s["title"] as? String) ?? "" }
        return ""
    }

    func yoloStatus(id: String) async -> Bool {
        guard let o = try? await dict("/api/session/yolo?session_id=\(q(id))") else { return false }
        return (o["yolo_enabled"] as? Bool) ?? false
    }

    func setYolo(id: String, enabled: Bool) async -> Bool {
        guard let data = try? await postJSON("/api/session/yolo", body: ["session_id": id, "enabled": enabled]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return enabled }
        return (obj["yolo_enabled"] as? Bool) ?? enabled
    }

    func commands() async -> [SlashCommand] {
        guard let o = try? await dict("/api/commands") else { return [] }
        return (o["commands"] as? [[String: Any]] ?? []).compactMap { c in
            let name = (c["name"] as? String) ?? ""
            guard !name.isEmpty else { return nil }
            return SlashCommand(
                name: name,
                description: (c["description"] as? String) ?? "",
                category: (c["category"] as? String) ?? "",
                argsHint: (c["args_hint"] as? String) ?? "",
                cliOnly: (c["cli_only"] as? Bool) ?? false
            )
        }
    }

    func execCommand(_ command: String) async -> String {
        guard let data = try? await postJSON("/api/commands/exec", body: ["command": command]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        return (obj["output"] as? String) ?? (obj["error"] as? String) ?? (obj["message"] as? String) ?? ""
    }

    func logout() async {
        _ = try? await postJSON("/api/auth/logout", body: [:])
    }

    func updatesCheck() async -> UpdatesStatus {
        guard let data = try? await postJSON("/api/updates/check", body: ["force": true]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return UpdatesStatus() }
        func target(_ key: String) -> UpdateTarget {
            let t = obj[key] as? [String: Any] ?? [:]
            return UpdateTarget(
                name: (t["name"] as? String) ?? key,
                behind: (t["behind"] as? Int) ?? 0,
                current: (t["current_version"] as? String) ?? (t["current_sha"] as? String) ?? "",
                latest: (t["latest_version"] as? String) ?? (t["latest_sha"] as? String) ?? "",
                dirty: (t["dirty"] as? Bool) ?? false
            )
        }
        return UpdatesStatus(webui: target("webui"), agent: target("agent"), checkedAt: "\(obj["checked_at"] ?? "")")
    }

    func updatesApply(target: String) async -> String {
        guard let data = try? await postJSON("/api/updates/apply", body: ["target": target]),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "apply failed" }
        if (obj["ok"] as? Bool) == true { return "ok" }
        return (obj["error"] as? String) ?? (obj["message"] as? String) ?? "apply failed"
    }

    func startTerminal(sid: String, rows: Int = 24, cols: Int = 80) async throws {
        let o = try await dictPost("/api/terminal/start", ["session_id": sid, "rows": rows, "cols": cols])
        if let err = o["error"] as? String, !err.isEmpty { throw URLError(.cannotParseResponse) }
    }

    func resizeTerminal(sid: String, rows: Int, cols: Int) async {
        _ = try? await postJSON("/api/terminal/resize", body: ["session_id": sid, "rows": rows, "cols": cols])
    }

    func terminalInput(sid: String, data: String) async {
        _ = try? await postJSON("/api/terminal/input", body: ["session_id": sid, "data": data])
    }

    func closeTerminal(sid: String) async {
        _ = try? await postJSON("/api/terminal/close", body: ["session_id": sid])
    }

    func streamTerminal(sid: String, onEvent: @escaping (String, String) -> Void) async {
        let u = baseURL.appendingPathComponent("api/terminal/output")
        var comp = URLComponents(url: u, resolvingAgainstBaseURL: false)!
        comp.queryItems = [URLQueryItem(name: "session_id", value: sid)]
        guard let url = comp.url else { return }
        var req = URLRequest(url: url)
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        do {
            let (bytes, _) = try await session.bytes(for: req)
            var event = ""
            for try await line in bytes.lines {
                if line.hasPrefix("event:") {
                    event = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                } else if line.hasPrefix("data:") {
                    onEvent(event, String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces))
                } else if line.isEmpty {
                    event = ""
                }
            }
        } catch {}
    }

    func transcribe(fileURL: URL) async throws -> String {
        var req = URLRequest(url: url("/api/transcribe"))
        req.httpMethod = "POST"
        let boundary = "Boundary-\(UUID().uuidString)"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        if !csrf.isEmpty { req.setValue(csrf, forHTTPHeaderField: "X-Hermes-CSRF-Token") }
        var data = Data()
        data.append("--\(boundary)\r\n".data(using: .utf8)!)
        data.append("Content-Disposition: form-data; name=\"file\"; filename=\"dictation.m4a\"\r\n".data(using: .utf8)!)
        data.append("Content-Type: audio/mp4\r\n\r\n".data(using: .utf8)!)
        data.append(try Data(contentsOf: fileURL))
        data.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        req.httpBody = data
        let (resp, http) = try await session.data(for: req)
        guard let r = http as? HTTPURLResponse, (200..<300).contains(r.statusCode) else { throw URLError(.badServerResponse) }
        let obj = (try JSONSerialization.jsonObject(with: resp) as? [String: Any]) ?? [:]
        if let err = obj["error"] as? String, !err.isEmpty { throw URLError(.cannotDecodeContentData) }
        return (obj["transcript"] as? String) ?? (obj["text"] as? String) ?? ""
    }

    func tts(text: String) async throws -> Data {
        try await postJSON("/api/tts", body: ["text": String(text.prefix(4000)), "engine": "edge"])
    }

    func transcribeAvailable() async -> Bool {
        ((try? await dict("/api/transcribe/capability"))?["available"] as? Bool) ?? false
    }

    private func dictPost(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        let data = try await postJSON(path, body: body)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func q(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s }
    private func str(_ d: [String: Any], _ keys: String...) -> String { first(d, keys) ?? "" }
    private func first(_ d: [String: Any], _ keys: String...) -> String? { first(d, keys) }
    private func first(_ d: [String: Any], _ keys: [String]) -> String? {
        for k in keys {
            if let s = textValue(d[k]), !s.isEmpty { return s }
        }
        return nil
    }
    private func textValue(_ any: Any?) -> String? {
        guard let any else { return nil }
        if let s = any as? String { return s.isEmpty ? nil : s }
        if let n = any as? NSNumber { return n.stringValue }
        if let arr = any as? [Any] {
            let parts = arr.compactMap { item -> String? in
                if let s = item as? String { return s }
                if let d = item as? [String: Any] { return first(d, ["text", "content", "value"]) }
                return nil
            }
            let joined = parts.joined(separator: "\n")
            return joined.isEmpty ? nil : joined
        }
        if let d = any as? [String: Any] {
            if let t = first(d, ["text", "content", "value"]) { return t }
        }
        return nil
    }
    private func bool(_ d: [String: Any], _ k: String, _ def: Bool) -> Bool {
        if let b = d[k] as? Bool { return b }
        if let s = d[k] as? String { return s == "true" || s == "1" }
        return def
    }
    private func int(_ d: [String: Any], _ k: String) -> Int {
        intOpt(d, k) ?? 0
    }
    private func intOpt(_ d: [String: Any], _ k: String) -> Int? {
        guard let v = d[k], !(v is NSNull) else { return nil }
        if let n = v as? Int { return n }
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) }
        return nil
    }
    private func double(_ d: [String: Any], _ k: String) -> Double {
        if let n = d[k] as? Double { return n }
        if let n = d[k] as? Int { return Double(n) }
        if let s = d[k] as? String { return Double(s) ?? 0 }
        return 0
    }
    private func stringify(_ v: Any) -> String {
        if v is NSNull { return "" }
        if let s = v as? String { return s }
        return String(describing: v)
    }
    private func secretKey(_ k: String) -> Bool {
        let s = k.lowercased()
        return ["password", "secret", "token", "api_key", "apikey", "csrf", "cookie"].contains { s.contains($0) }
    }
}

func mimeType(for url: URL) -> String {
    if let t = UTType(filenameExtension: url.pathExtension), let m = t.preferredMIMEType { return m }
    return "application/octet-stream"
}
