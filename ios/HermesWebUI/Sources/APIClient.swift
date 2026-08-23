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

    func sessions() async throws -> [SessionRow] {
        let data = try await getData("/api/sessions")
        let obj = try JSONSerialization.jsonObject(with: data)
        var raw: [Any] = []
        if let arr = obj as? [Any] { raw = arr }
        else if let d = obj as? [String: Any] {
            for k in ["sessions", "data", "items"] {
                if let arr = d[k] as? [Any] { raw = arr; break }
            }
        }
        return raw.compactMap { item in
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
            return row
        }
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

    func startChat(sessionId: String, message: String, model: String?, attachments: [String] = []) async throws -> ChatStart {
        var body: [String: Any] = ["session_id": sessionId, "message": message]
        if let model, !model.isEmpty { body["model"] = model }
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
            return CronJob(
                id: id,
                name: first(j, "name", "id") ?? "job",
                schedule: str(j, "schedule"),
                enabled: bool(j, "enabled", true),
                paused: bool(j, "paused", false),
                prompt: str(j, "prompt"),
                lastStatus: first(j, "last_status", "status", "last_error") ?? "",
                lastRun: stringify(j["last_run"] ?? j["last_run_at"] ?? ""),
                owner: first(j, "owner_profile", "profile") ?? "",
                readOnly: bool(j, "read_only", false)
            )
        }
    }

    func cronAction(id: String, action: String) async {
        _ = try? await postJSON("/api/crons/\(action)", body: ["job_id": id])
    }

    func cronOutput(id: String) async -> String {
        (try? String(data: try await getData("/api/crons/output?job_id=\(q(id))"), encoding: .utf8)) ?? ""
    }

    func kanban() async throws -> [KanbanColumn] {
        let obj = try await dict("/api/kanban/board")
        return (obj["columns"] as? [[String: Any]] ?? []).map { c in
            let name = first(c, "name", "id", "title") ?? "column"
            let tasks = (c["tasks"] as? [[String: Any]] ?? []).map { t in
                KanbanTask(
                    id: str(t, "id", "task_id"),
                    title: first(t, "title", "name", "id") ?? "task",
                    status: first(t, "status") ?? name,
                    assignee: str(t, "assignee"),
                    priority: stringify(t["priority"] ?? "")
                )
            }
            return KanbanColumn(name: name, tasks: tasks)
        }
    }

    func moveKanban(id: String, status: String) async {
        _ = try? await sendJSON("PATCH", "/api/kanban/tasks/\(q(id))", body: ["status": status])
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

    func insights() async throws -> Insights {
        let o = try await dict("/api/insights?days=30")
        let models = (o["models"] as? [[String: Any]] ?? []).map { m in
            InsightModel(model: str(m, "model"), sessions: int(m, "sessions"), tokens: int(m, "total_tokens"), cost: double(m, "cost"))
        }
        var hit: Double? = nil
        if let n = o["total_cache_hit_percent"] as? Double { hit = n }
        if let d = o["total_cache_hit_percent"] as? [String: Any] { hit = double(d, "value") }
        return Insights(
            days: int(o, "period_days") == 0 ? 30 : int(o, "period_days"),
            sessions: int(o, "total_sessions"),
            messages: int(o, "total_messages"),
            tokens: int(o, "total_tokens"),
            cost: double(o, "total_cost"),
            cacheHit: hit,
            models: models
        )
    }

    func logs(file: String) async throws -> [String] {
        let o = try await dict("/api/logs?file=\(q(file))&tail=250")
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

    func startTerminal(sid: String) async throws {
        let o = try await dictPost("/api/terminal/start", ["session_id": sid, "rows": 24, "cols": 80])
        if let err = o["error"] as? String, !err.isEmpty { throw URLError(.cannotParseResponse) }
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
