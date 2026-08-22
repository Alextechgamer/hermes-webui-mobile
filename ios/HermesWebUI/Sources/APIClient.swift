import Foundation

/// Talks to hermes-webui (port 8787 typically). Cookies persist on this session.
final class APIClient {
    var baseURL: URL
    private var csrf: String = ""
    private let session: URLSession

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
        let (data, resp) = try await session.data(for: request("GET", path))
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 401 { throw URLError(.userAuthenticationRequired) }
        guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }

    func sendJSON(_ method: String, _ path: String, body: [String: Any]) async throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await session.data(for: request(method, path, json: json))
        guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 401 { throw URLError(.userAuthenticationRequired) }
        guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }

    func postJSON(_ path: String, body: [String: Any]) async throws -> Data {
        try await sendJSON("POST", path, body: body)
    }

    func authStatus() async throws -> AuthStatus {
        try JSONDecoder().decode(AuthStatus.self, from: try await getData("/api/auth/status"))
    }

    func login(password: String) async throws {
        _ = try await postJSON("/api/auth/login", body: ["password": password])
        try await refreshCSRF()
    }

    func refreshCSRF() async throws {
        let (data, _) = try await session.data(from: baseURL)
        guard let html = String(data: data, encoding: .utf8) else { return }
        let patterns = [
            "csrf_token\\\"\\\\s*:\\\\s*\\\"([^\\\"]+)\\\"",
            "__CSRF_TOKEN_JSON__\\\\s*=\\\\s*\\\"([^\\\"]+)\\\"",
            "name=\\\"csrf-token\\\" content=\\\"([^\\\"]+)\\\"",
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
        if let list = try? JSONDecoder().decode(SessionList.self, from: data), let items = list.items { return items }
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

    func loadSession(id: String, full: Bool = false) async throws -> SessionLoad {
        let qid = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        let extra = full ? "&full=1" : "&msg_limit=80"
        let data = try await getData("/api/session?session_id=\(qid)&messages=1&resolve_model=0\(extra)")
        let obj = (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let sess = obj["session"] as? [String: Any] ?? obj
        let rawMsgs = (sess["messages"] as? [[String: Any]]) ?? (obj["messages"] as? [[String: Any]]) ?? []
        let msgs: [ChatMessage] = rawMsgs.compactMap { m in
            var c = ChatMessage()
            c.role = m["role"] as? String ?? ""
            c.content = m["content"] as? String ?? ""
            c.tool_name = m["tool_name"] as? String ?? m["name"] as? String
            if let n = m["id"] as? Int64 { c.id = n }
            else if let n = m["id"] as? Int { c.id = Int64(n) }
            return c
        }
        let todoObj = (sess["todo_state"] as? [String: Any]) ?? (obj["todo_state"] as? [String: Any])
        let todos = ((todoObj?["todos"] as? [[String: Any]]) ?? []).map { t -> TodoItem in
            TodoItem(
                id: t["id"] as? String,
                content: t["content"] as? String,
                text: t["text"] as? String,
                title: t["title"] as? String,
                status: t["status"] as? String
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

    func startChat(sessionId: String, message: String, model: String?) async throws -> ChatStart {
        var body: [String: Any] = ["session_id": sessionId, "message": message]
        if let model, !model.isEmpty { body["model"] = model }
        return try JSONDecoder().decode(ChatStart.self, from: try await postJSON("/api/chat/start", body: body))
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
            detail: String((first(p, "description", "command", "detail", "preview") ?? "").prefix(400))
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
                    assignee: str(t, "assignee")
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

    func models() async -> [String] {
        guard let data = try? await getData("/api/models"),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        var names = [String]()
        var seen = Set<String>()
        func add(_ id: String) {
            let t = id.trimmingCharacters(in: .whitespaces)
            if t.isEmpty || !seen.insert(t).inserted { return }
            names.append(t)
        }
        func addItem(_ any: Any) {
            if let s = any as? String { add(s); return }
            if let d = any as? [String: Any] {
                if let id = d["id"] as? String { add(id) }
                else if let id = d["model"] as? String { add(id) }
            }
        }
        if let groups = root["groups"] as? [[String: Any]] {
            for g in groups {
                (g["models"] as? [Any])?.forEach(addItem)
                (g["extra_models"] as? [Any])?.forEach(addItem)
            }
        }
        (root["models"] as? [Any])?.forEach(addItem)
        (root["extra_models"] as? [Any])?.forEach(addItem)
        if names.isEmpty, let def = root["default_model"] as? String { add(def) }
        return names
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
            if let s = d[k] as? String, !s.isEmpty { return s }
            if let n = d[k] as? NSNumber { return n.stringValue }
        }
        return nil
    }
    private func bool(_ d: [String: Any], _ k: String, _ def: Bool) -> Bool {
        if let b = d[k] as? Bool { return b }
        if let s = d[k] as? String { return s == "true" || s == "1" }
        return def
    }
    private func int(_ d: [String: Any], _ k: String) -> Int {
        if let n = d[k] as? Int { return n }
        if let n = d[k] as? Double { return Int(n) }
        if let s = d[k] as? String { return Int(s) ?? 0 }
        return 0
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
    private func fmtSec(_ v: Any?) -> String {
        let n: Double
        if let d = v as? Double { n = d }
        else if let i = v as? Int { n = Double(i) }
        else { return "—" }
        let s = Int(n)
        return s >= 3600 ? "\(s/3600)h \((s%3600)/60)m" : "\(s/60)m \(s%60)s"
    }
    private func secretKey(_ k: String) -> Bool {
        let s = k.lowercased()
        return ["password", "secret", "token", "api_key", "apikey", "csrf", "cookie"].contains { s.contains($0) }
    }
}
