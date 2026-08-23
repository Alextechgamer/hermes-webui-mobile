import SwiftUI

enum OfficialUrl {
    static func base(webui: String, override: String) -> String {
        let o = override.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !o.isEmpty { return o }
        var s = webui.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return "" }
        if !s.contains("://") { s = "http://\(s)" }
        guard let u = URL(string: s), let host = u.host else { return "" }
        let scheme = u.scheme ?? "http"
        return "\(scheme)://\(host):9119"
    }
}

@MainActor
final class OfficialDashStore: ObservableObject {
    @Published var tab = "chat"
    @Published var needsLogin = false
    @Published var error: String?
    @Published var statusLine = ""
    @Published var sessions: [(id: String, title: String, preview: String, count: Int)] = []
    @Published var bubbles: [(id: String, role: String, text: String)] = []
    @Published var live = ""
    @Published var draft = ""
    @Published var sid = ""
    @Published var columns: [KanbanColumn] = []
    @Published var boards: [KanbanBoardMeta] = []
    @Published var board = ""
    @Published var taskDraft = ""
    @Published var user: String
    @Published var password: String
    @Published var urlDraft: String
    @Published var wsState = "idle"

    private let settings: AppSettings
    private var session = URLSession.shared
    private var ws: URLSessionWebSocketTask?
    private var pending: String?

    init(settings: AppSettings) {
        self.settings = settings
        self.user = UserDefaults.standard.string(forKey: "officialUser") ?? ""
        self.password = WebUIKeychain.read(account: "official") ?? ""
        self.urlDraft = UserDefaults.standard.string(forKey: "officialUrl") ?? ""
    }

    var base: String { OfficialUrl.base(webui: settings.webuiURL, override: urlDraft) }

    func load() async {
        guard !base.isEmpty else {
            error = "Set the official Dashboard URL (same host as WebUI, port 9119)."
            return
        }
        error = nil
        do {
            let o = try await dict("/api/status")
            statusLine = "v\(o["version"] as? String ?? "") · \(o["gateway_state"] as? String ?? "") · \(o["active_sessions"] as? Int ?? 0) sessions"
            needsLogin = false
            await refresh()
        } catch {
            needsLogin = true
            if !user.isEmpty, !password.isEmpty { await login() }
        }
    }

    func login() async {
        UserDefaults.standard.set(user, forKey: "officialUser")
        UserDefaults.standard.set(urlDraft, forKey: "officialUrl")
        WebUIKeychain.write(password, account: "official")
        do {
            let providers = try? await dict("/api/auth/providers")
            let list = providers?["providers"] as? [[String: Any]] ?? []
            let prov = list.first(where: { ($0["supports_password"] as? Bool) == true })?["name"] as? String ?? "basic"
            _ = try await post("/auth/password-login", ["provider": prov, "username": user, "password": password, "next": "/chat"])
            needsLogin = false
            error = nil
            await load()
        } catch {
            needsLogin = true
            self.error = "Invalid dashboard username or password."
        }
    }

    func refresh() async {
        if tab == "kanban" { await loadKanban() } else { await loadSessions() }
    }

    func loadSessions() async {
        do {
            let o = try await dict("/api/sessions?limit=40&order=recent&exclude_sources=cron")
            let arr = o["sessions"] as? [[String: Any]] ?? o["items"] as? [[String: Any]] ?? []
            sessions = arr.compactMap { s in
                let id = (s["id"] as? String) ?? (s["session_id"] as? String) ?? ""
                if id.isEmpty { return nil }
                return (id, (s["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? String(id.prefix(8)), (s["preview"] as? String) ?? "", (s["message_count"] as? Int) ?? (s["messages"] as? Int) ?? 0)
            }
            if sid.isEmpty, let first = sessions.first { await open(first.id) }
        } catch { self.error = error.localizedDescription }
    }

    func open(_ id: String) async {
        sid = id
        do {
            let o = try await dict("/api/sessions/\(id)/messages?limit=200&order=latest")
            let arr = o["messages"] as? [[String: Any]] ?? []
            bubbles = arr.compactMap { m in
                let role = (m["role"] as? String) ?? "assistant"
                let text = (m["content"] as? String) ?? (m["text"] as? String) ?? ""
                if text.isEmpty { return nil }
                return (UUID().uuidString, role, text)
            }
            live = ""
            connect(resume: id)
        } catch {
            connect(resume: id)
        }
    }

    func newChat() {
        sid = ""; bubbles = []; live = ""
        connect(resume: "")
    }

    func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return }
        draft = ""
        bubbles.append((UUID().uuidString, "user", text))
        if sid.isEmpty {
            pending = text
            rpc("session.create", ["source": "cli"])
        } else {
            rpc("prompt.submit", ["session_id": sid, "text": text])
        }
    }

    func loadKanban() async {
        do {
            let b = try await dict("/api/plugins/kanban/boards")
            let current = (b["current"] as? String) ?? ""
            boards = (b["boards"] as? [[String: Any]] ?? []).compactMap { row in
                let slug = (row["slug"] as? String) ?? ""
                if slug.isEmpty { return nil }
                return KanbanBoardMeta(slug: slug, name: (row["name"] as? String) ?? slug, total: (row["total"] as? Int) ?? 0, current: slug == current)
            }
            if board.isEmpty { board = current }
            let qs = board.isEmpty ? "" : "?board=\(board)"
            let o = try await dict("/api/plugins/kanban/board\(qs)")
            columns = (o["columns"] as? [[String: Any]] ?? []).map { c in
                let name = (c["name"] as? String) ?? "column"
                let tasks = (c["tasks"] as? [[String: Any]] ?? []).compactMap { t -> KanbanTask? in
                    let id = (t["id"] as? String) ?? ""
                    if id.isEmpty { return nil }
                    return KanbanTask(id: id, title: (t["title"] as? String) ?? id, status: (t["status"] as? String) ?? name, assignee: (t["assignee"] as? String) ?? "", priority: "\(t["priority"] ?? "")", body: (t["body"] as? String) ?? (t["latest_summary"] as? String) ?? "", tenant: (t["tenant"] as? String) ?? "", comments: (t["comment_count"] as? Int) ?? 0)
                }
                return KanbanColumn(name: name, tasks: tasks)
            }
        } catch { self.error = error.localizedDescription }
    }

    func switchBoard(_ slug: String) async {
        board = slug
        _ = try? await post("/api/plugins/kanban/boards/\(slug)/switch", [:])
        await loadKanban()
    }

    func addTask() async {
        let t = taskDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return }
        taskDraft = ""
        let qs = board.isEmpty ? "" : "?board=\(board)"
        _ = try? await post("/api/plugins/kanban/tasks\(qs)", ["title": t])
        await loadKanban()
    }

    func move(_ id: String, _ status: String) async {
        let qs = board.isEmpty ? "" : "?board=\(board)"
        _ = try? await patch("/api/plugins/kanban/tasks/\(id)\(qs)", ["status": status])
        await loadKanban()
    }

    private func connect(resume: String) {
        ws?.cancel(with: .goingAway, reason: nil)
        wsState = "connecting"
        Task {
            let ticket = ((try? await dictPOST("/api/auth/ws-ticket", [:]))?["ticket"] as? String) ?? ""
            var wsBase = base.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://")
            var url = URL(string: wsBase + "/api/ws" + (ticket.isEmpty ? "" : "?ticket=\(ticket)"))!
            let task = URLSession.shared.webSocketTask(with: url)
            ws = task
            task.resume()
            wsState = "live"
            if resume.isEmpty { rpc("session.create", ["source": "cli"]) }
            else { rpc("session.resume", ["session_id": resume]) }
            listen()
        }
    }

    private func listen() {
        ws?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure:
                Task { @MainActor in self.wsState = "error" }
            case .success(let msg):
                let text: String
                switch msg {
                case .string(let s): text = s
                case .data(let d): text = String(data: d, encoding: .utf8) ?? ""
                @unknown default: text = ""
                }
                Task { @MainActor in self.handle(text) }
                self.listen()
            }
        }
    }

    private func handle(_ raw: String) {
        raw.split(separator: "\n").forEach { line in
            guard let data = String(line).data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            let method = obj["method"] as? String ?? ""
            let params = obj["params"] as? [String: Any] ?? [:]
            let type = method == "event" ? (params["type"] as? String ?? "") : method
            let payload = (params["payload"] as? [String: Any]) ?? params
            if type.contains("session"), let id = payload["session_id"] as? String ?? payload["id"] as? String {
                sid = id
                if let p = pending {
                    pending = nil
                    rpc("prompt.submit", ["session_id": id, "text": p])
                }
            }
            if type.contains("delta") || type.contains("token") {
                live += (payload["text"] as? String) ?? (payload["content"] as? String) ?? ""
            }
            if type.contains("complete") || type == "message" {
                let t = live.isEmpty ? ((payload["text"] as? String) ?? "") : live
                if !t.isEmpty { bubbles.append((UUID().uuidString, "assistant", t)) }
                live = ""
            }
        }
    }

    private func rpc(_ method: String, _ params: [String: String]) {
        let p = params.map { "\"\($0.key)\":\"\($0.value.replacingOccurrences(of: "\"", with: "\\\""))\"" }.joined(separator: ",")
        let body = "{\"jsonrpc\":\"2.0\",\"id\":\"i\(Int.random(in: 1...99999))\",\"method\":\"\(method)\",\"params\":{\(p)}}"
        ws?.send(.string(body)) { _ in }
    }

    private func dict(_ path: String) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: base + path)!)
        req.httpMethod = "GET"
        let (data, resp) = try await URLSession.shared.data(for: req)
        if (resp as? HTTPURLResponse)?.statusCode == 401 { throw URLError(.userAuthenticationRequired) }
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func post(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        try await send("POST", path, body)
    }

    private func patch(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        try await send("PATCH", path, body)
    }

    private func dictPOST(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        try await send("POST", path, body)
    }

    private func send(_ method: String, _ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: base + path)!)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        if (resp as? HTTPURLResponse)?.statusCode == 401 { throw URLError(.userAuthenticationRequired) }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}

struct OfficialDashView: View {
    @StateObject private var dash: OfficialDashStore
    init(settings: AppSettings) { _dash = StateObject(wrappedValue: OfficialDashStore(settings: settings)) }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading) {
                    Text("HERMES DASHBOARD").font(.caption.bold()).foregroundColor(Palette.accent)
                    Text(dash.base.isEmpty ? "same host :9119" : dash.base).font(.caption2).foregroundColor(Palette.muted)
                }
                Spacer()
                Text(dash.wsState).font(.caption2).foregroundColor(Palette.muted)
            }.padding(12).background(Palette.sidebar)
            if let e = dash.error { Text(e).font(.footnote).foregroundColor(.red).padding(.horizontal) }
            if dash.needsLogin {
                login
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        tab("chat", "Chat"); tab("sessions", "Sessions"); tab("kanban", "Kanban"); tab("status", "Status")
                    }.padding(10)
                }
                switch dash.tab {
                case "kanban": kanban
                case "sessions": sessions
                case "status": status
                default: chat
                }
            }
        }
        .background(Palette.bg)
        .task { await dash.load() }
    }

    private func tab(_ id: String, _ label: String) -> some View {
        Text(label).font(.caption)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(dash.tab == id ? Palette.accent : Palette.surface)
            .foregroundColor(dash.tab == id ? Palette.bg : Palette.text)
            .cornerRadius(8)
            .onTapGesture {
                dash.tab = id
                Task { await dash.refresh() }
            }
    }

    private var login: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Official dashboard sign-in").font(.title3.bold()).foregroundColor(Palette.text)
            Text("This is hermes dashboard :9119 — not WebUI and not Console :8790.").font(.footnote).foregroundColor(Palette.muted)
            TextField("http://host:9119", text: $dash.urlDraft).textFieldStyle(.roundedBorder)
            TextField("Username", text: $dash.user).textFieldStyle(.roundedBorder)
            SecureField("Password", text: $dash.password).textFieldStyle(.roundedBorder)
            Button("Sign in") { Task { await dash.login() } }.foregroundColor(Palette.accent)
        }.padding(20)
    }

    private var chat: some View {
        VStack {
            HStack {
                Button("New chat") { dash.newChat() }.foregroundColor(Palette.accent)
                Spacer()
            }.padding(.horizontal)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading) {
                        ForEach(dash.bubbles, id: \.id) { m in
                            VStack(alignment: m.role == "user" ? .trailing : .leading) {
                                Text(m.role == "user" ? "You" : "Hermes").font(.caption2).foregroundColor(Palette.muted)
                                Text(m.text).foregroundColor(Palette.text).padding(8).background(Palette.surface).cornerRadius(8)
                            }.frame(maxWidth: .infinity, alignment: m.role == "user" ? .trailing : .leading)
                        }
                        if !dash.live.isEmpty { Text(dash.live).foregroundColor(Palette.text).id("live") }
                    }.padding(12)
                }
            }
            HStack {
                TextField("Message", text: $dash.draft).textFieldStyle(.roundedBorder)
                Button("Send") { dash.send() }.foregroundColor(Palette.accent)
            }.padding(12)
        }
    }

    private var sessions: some View {
        List {
            ForEach(dash.sessions, id: \.id) { s in
                Button {
                    dash.tab = "chat"
                    Task { await dash.open(s.id) }
                } label: {
                    VStack(alignment: .leading) {
                        Text(s.title).foregroundColor(Palette.text)
                        Text("\(s.count) messages").font(.caption).foregroundColor(Palette.muted)
                    }
                }.listRowBackground(Palette.surface)
            }
        }.scrollContentBackground(.hidden)
    }

    private var status: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(dash.statusLine.isEmpty ? "No status yet." : dash.statusLine).foregroundColor(Palette.text)
            Text("Browser /chat is an xterm TUI over /api/pty. This app uses REST + /api/ws JSON-RPC like Hermes Desktop.").font(.footnote).foregroundColor(Palette.muted)
        }.padding(16)
    }

    private var kanban: some View {
        ScrollView {
            VStack(alignment: .leading) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(dash.boards) { b in
                            Text(b.name).padding(8).background(b.slug == dash.board ? Palette.accent : Palette.surface)
                                .foregroundColor(b.slug == dash.board ? Palette.bg : Palette.text)
                                .cornerRadius(8)
                                .onTapGesture { Task { await dash.switchBoard(b.slug) } }
                        }
                    }
                }.padding(.horizontal)
                HStack {
                    TextField("New task", text: $dash.taskDraft).textFieldStyle(.roundedBorder)
                    Button("Add") { Task { await dash.addTask() } }.foregroundColor(Palette.accent)
                }.padding(.horizontal)
                ForEach(officialLanes(dash.columns), id: \.0) { lane, cols in
                    Text(lane == "__unassigned__" ? "unassigned" : lane).font(.caption).foregroundColor(Palette.muted).padding(.horizontal)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top) {
                            ForEach(cols) { col in
                                VStack(alignment: .leading) {
                                    Text("\(col.name) · \(col.tasks.count)").bold().foregroundColor(Palette.text)
                                    ForEach(col.tasks) { t in
                                        VStack(alignment: .leading) {
                                            Text(t.id).font(.caption2).foregroundColor(Palette.muted)
                                            Text(t.title).foregroundColor(Palette.text)
                                            Text(t.assignee.isEmpty ? "unassigned" : "@\(t.assignee)").font(.caption).foregroundColor(Palette.muted)
                                            HStack {
                                                Button("complete") { Task { await dash.move(t.id, "done") } }
                                                Button("archive") { Task { await dash.move(t.id, "archived") } }
                                            }.font(.caption).foregroundColor(Palette.accent)
                                        }.padding(8).frame(width: 220, alignment: .leading).background(Palette.bg).cornerRadius(8)
                                    }
                                }.padding(8).frame(width: 240, alignment: .top).background(Palette.surface).cornerRadius(12)
                            }
                        }.padding(.horizontal)
                    }
                }
            }.padding(.vertical, 8)
        }
    }
}

private func officialLanes(_ columns: [KanbanColumn]) -> [(String, [KanbanColumn])] {
    var names = Set<String>()
    columns.forEach { $0.tasks.forEach { names.insert($0.assignee.isEmpty ? "__unassigned__" : $0.assignee) } }
    let assigned = names.filter { $0 != "__unassigned__" }.sorted()
    let order = assigned + (names.contains("__unassigned__") ? ["__unassigned__"] : [])
    if order.isEmpty { return columns.isEmpty ? [] : [("default", columns)] }
    return order.map { lane in
        (lane, columns.map { col in
            KanbanColumn(name: col.name, tasks: col.tasks.filter { ($0.assignee.isEmpty ? "__unassigned__" : $0.assignee) == lane })
        })
    }
}
