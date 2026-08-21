import Combine
import Foundation

@MainActor
final class AppStore: ObservableObject {
    @Published var sessions: [SessionRow] = []
    @Published var sessionQuery = ""
    @Published var messages: [ChatMessage] = []
    @Published var liveText: String = ""
    @Published var title: String = "Hermes"
    @Published var error: String?
    @Published var busy = false
    @Published var needsLogin = false
    @Published var loggedIn = false
    @Published var authEnabled = false
    @Published var panel: Panel = .chat
    @Published var truncated = false
    @Published var models: [String] = []
    @Published var selectedModel = ""
    @Published var jobs: [CronJob] = []
    @Published var columns: [KanbanColumn] = []
    @Published var skills: [SkillRow] = []
    @Published var skillBody = ""
    @Published var memory = MemoryDoc()
    @Published var spaces: [SpaceRow] = []
    @Published var profiles: [ProfileRow] = []
    @Published var activeProfile = ""
    @Published var todos: [TodoItem] = []
    @Published var insights = Insights()
    @Published var logLines: [String] = []
    @Published var logFile = "agent"
    @Published var dash: [DashCard] = []
    @Published var settingsItems: [SettingItem] = []
    @Published var settingEdits: [String: String] = [:]
    @Published var approval: Approval?
    @Published var clarify: Clarify?
    @Published var jobOutput = ""
    var currentSid: String = ""
    var client: APIClient?
    private var pollTask: Task<Void, Never>?

    func attach(url: URL) {
        client = APIClient(baseURL: url)
    }

    func bootstrap() async {
        guard let c = client else { return }
        error = nil
        do {
            let st = try await c.authStatus()
            authEnabled = st.auth_enabled
            loggedIn = st.logged_in || !st.auth_enabled
            needsLogin = st.auth_enabled && !st.logged_in
            if loggedIn {
                try await c.refreshCSRF()
                await loadSessions()
                models = await c.models()
                startPoll()
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func login(_ password: String) async {
        guard let c = client else { return }
        error = nil
        do {
            try await c.login(password: password)
            needsLogin = false
            loggedIn = true
            await loadSessions()
            models = await c.models()
            startPoll()
        } catch {
            self.error = "Login failed"
        }
    }

    func loadSessions() async {
        guard let c = client else { return }
        do { sessions = try await c.sessions() } catch { self.error = error.localizedDescription }
    }

    func go(_ p: Panel) async {
        panel = p
        title = p == .chat ? title : p.label
        await loadPanel()
    }

    func loadPanel() async {
        guard let c = client else { return }
        error = nil
        do {
            switch panel {
            case .chat: await loadSessions()
            case .tasks: jobs = try await c.crons()
            case .kanban: columns = try await c.kanban()
            case .skills: skills = try await c.skills()
            case .memory: memory = try await c.memory()
            case .spaces: spaces = try await c.spaces()
            case .profiles:
                let r = try await c.profiles()
                activeProfile = r.0
                profiles = r.1
            case .todos:
                if !currentSid.isEmpty { await openSid(currentSid, keepPanel: true) }
            case .insights: insights = try await c.insights()
            case .logs: logLines = try await c.logs(file: logFile)
            case .dashboard: dash = await c.dashboard()
            case .settings:
                settingsItems = try await c.settings()
                settingEdits = [:]
                models = await c.models()
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func open(_ row: SessionRow) async { await openSid(row.sid) }

    func openSid(_ id: String, keepPanel: Bool = false) async {
        guard let c = client else { return }
        currentSid = id
        liveText = ""
        if !keepPanel { panel = .chat }
        do {
            let load = try await c.loadSession(id: id)
            apply(load)
        } catch { self.error = error.localizedDescription }
    }

    func loadFullHistory() async {
        guard let c = client, !currentSid.isEmpty else { return }
        do { apply(try await c.loadSession(id: currentSid, full: true)) } catch { self.error = error.localizedDescription }
    }

    private func apply(_ load: SessionLoad) {
        if !load.title.isEmpty { title = load.title }
        if !load.model.isEmpty && selectedModel.isEmpty { selectedModel = load.model }
        truncated = load.truncated
        messages = load.messages
        todos = load.todos
        if !load.activeStreamId.isEmpty && !busy {
            Task { await streamTokens(id: load.activeStreamId) }
        }
    }

    func newChat() async {
        guard let c = client else { return }
        do {
            currentSid = try await c.newSession()
            messages = []; liveText = ""; todos = []
            title = "New conversation"
            truncated = false
            panel = .chat
            await loadSessions()
        } catch { self.error = error.localizedDescription }
    }

    func deleteSession(_ id: String) async {
        guard let c = client else { return }
        await c.deleteSession(id: id)
        if currentSid == id {
            currentSid = ""; messages = []; liveText = ""; title = "Hermes"
        }
        await loadSessions()
    }

    func send(_ text: String) async {
        guard let c = client else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if currentSid.isEmpty { await newChat() }
        messages.append(ChatMessage(role: "user", content: trimmed))
        busy = true
        liveText = ""
        panel = .chat
        do {
            let start = try await c.startChat(sessionId: currentSid, message: trimmed, model: selectedModel.isEmpty ? nil : selectedModel)
            if let stream = start.stream_id, !stream.isEmpty { await streamTokens(id: stream) }
        } catch { self.error = error.localizedDescription }
        busy = false
        await loadSessions()
    }

    func stop() async {
        guard let c = client else { return }
        await c.cancelChat(sessionId: currentSid)
        flushLive()
        busy = false
    }

    private func streamTokens(id: String) async {
        guard let c = client else { return }
        busy = true
        await c.streamTokens(id: id) { ev, data in
            Task { @MainActor in self.handleSSE(event: ev, data: data) }
        }
        flushLive()
        busy = false
    }

    private func handleSSE(event: String, data: String) {
        guard let raw = data.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else { return }
        let ev = event.lowercased()
        if ev == "token" || ev == "delta" {
            liveText += (obj["text"] as? String) ?? (obj["delta"] as? String) ?? ""
        } else if ev == "todo_state", let arr = obj["todos"] as? [[String: Any]] {
            todos = arr.map { t in
                TodoItem(id: t["id"] as? String, content: t["content"] as? String, text: t["text"] as? String, title: t["title"] as? String, status: t["status"] as? String)
            }
        } else if ev.contains("tool") {
            let name = (obj["name"] as? String) ?? (obj["tool"] as? String) ?? "tool"
            messages.append(ChatMessage(role: "assistant", content: "", tool_name: name))
        } else if ev == "done" || ev.contains("complete") || ev == "error" {
            if ev == "error" { error = (obj["error"] as? String) ?? (obj["message"] as? String) }
            flushLive()
            busy = false
        }
    }

    private func flushLive() {
        if !liveText.isEmpty {
            messages.append(ChatMessage(role: "assistant", content: liveText))
            liveText = ""
        }
    }

    func cronAction(_ id: String, _ action: String) async {
        await client?.cronAction(id: id, action: action)
        await loadPanel()
    }

    func loadJobOutput(_ id: String) async { jobOutput = await client?.cronOutput(id: id) ?? "" }

    func moveTask(_ id: String, _ status: String) async {
        await client?.moveKanban(id: id, status: status)
        await loadPanel()
    }

    func toggleSkill(_ row: SkillRow) async {
        await client?.toggleSkill(name: row.name, enabled: row.disabled)
        await loadPanel()
    }

    func openSkill(_ name: String) async { skillBody = await client?.skillContent(name: name) ?? "" }

    func saveMemory(_ section: String, _ content: String) async {
        await client?.writeMemory(section: section, content: content)
        await loadPanel()
    }

    func switchProfile(_ name: String) async {
        await client?.switchProfile(name: name)
        await loadPanel()
        await loadSessions()
        models = await client?.models() ?? []
    }

    func setLogFile(_ file: String) async {
        logFile = file
        await loadPanel()
    }

    func saveSettings() async {
        guard let c = client, !settingEdits.isEmpty else { return }
        var body: [String: Any] = [:]
        for (k, v) in settingEdits {
            if v == "true" || v == "false" { body[k] = (v == "true") }
            else if let n = Int(v) { body[k] = n }
            else { body[k] = v }
        }
        await c.saveSettings(body)
        await loadPanel()
    }

    func approve(_ choice: String) async {
        guard let a = approval else { return }
        await client?.respondApproval(sid: currentSid, id: a.approvalId, choice: choice)
        approval = nil
    }

    func answerClarify(_ text: String) async {
        guard let q = clarify else { return }
        await client?.respondClarify(sid: currentSid, id: q.clarifyId, response: text)
        clarify = nil
    }

    private func startPoll() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard let self, let c = self.client, self.loggedIn, !self.currentSid.isEmpty else { continue }
                let a = await c.approval(sid: self.currentSid)
                let q = await c.clarify(sid: self.currentSid)
                await MainActor.run {
                    self.approval = a
                    self.clarify = q
                }
            }
        }
    }
}
