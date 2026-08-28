import Combine
import Foundation
import AVFoundation
import UIKit
import UniformTypeIdentifiers

@MainActor
final class AppStore: ObservableObject {
    @Published var sessions: [SessionRow] = []
    @Published var sessionQuery = ""
    @Published var sessionSource = ""
    @Published var webuiSessionCount = 0
    @Published var cliSessionCount = 0
    @Published var includeArchived = false
    @Published var projects: [ProjectRow] = []
    @Published var activeProjectId = ""
    @Published var shareNotice: String?
    @Published var kanbanSelected: Set<String> = []
    @Published var kanbanBulkStatus = "done"
    @Published var settingsQuery = ""
    @Published var commands: [SlashCommand] = []
    @Published var yoloEnabled = false
    @Published var commandOutput: String?
    @Published var updates = UpdatesStatus()
    @Published var updatesBusy = false
    @Published var messages: [ChatMessage] = []
    @Published var liveText: String = ""
    @Published var title: String = "Hermes"
    @Published var error: String?
    @Published var busy = false
    @Published var ready = false
    @Published var bootstrapping = false
    @Published var needsLogin = false
    @Published var loggedIn = false
    @Published var authEnabled = false
    @Published var panel: Panel = .chat
    @Published var truncated = false
    @Published var models: [ModelOption] = []
    @Published var selectedModel = ""
    @Published var reasoning = ReasoningStatus()
    @Published var jobs: [CronJob] = []
    @Published var columns: [KanbanColumn] = []
    @Published var kanbanBoards: [KanbanBoardMeta] = []
    @Published var kanbanBoard = ""
    @Published var kanbanSearch = ""
    @Published var kanbanAssignee = ""
    @Published var kanbanTenant = ""
    @Published var kanbanArchived = false
    @Published var kanbanMine = false
    @Published var kanbanDraft = ""
    @Published var kanbanOpen: KanbanTask?
    @Published var kanbanStats = KanbanStats()
    @Published var kanbanAssignees: [String] = []
    @Published var skills: [SkillRow] = []
    @Published var skillBody = ""
    @Published var memory = MemoryDoc()
    @Published var spaces: [SpaceRow] = []
    @Published var profiles: [ProfileRow] = []
    @Published var activeProfile = ""
    @Published var todos: [TodoItem] = []
    @Published var insights = Insights()
    @Published var insightsDays = 30
    @Published var logLines: [String] = []
    @Published var logFile = "agent"
    @Published var logTail = 200
    @Published var dash: [DashCard] = []
    @Published var console = ConsoleUsage()
    @Published var consoleError: String?
    @Published var costConfig = CostConfig()
    @Published var settingsItems: [SettingItem] = []
    @Published var settingEdits: [String: String] = [:]
    @Published var settingsSection: SettingsSection = .conversation
    @Published var approval: Approval?
    @Published var clarify: Clarify?
    @Published var jobOutput = ""
    @Published var fsPath = "."
    @Published var fsRoot = ""
    @Published var fsEntries: [FsEntry] = []
    @Published var fileDoc: FileDoc?
    @Published var fileDraft = ""
    @Published var termText = ""
    @Published var termRunning = false
    @Published var speakReplies = false
    @Published var listening = false
    @Published var transcribing = false
    @Published var voiceAvailable = false
    @Published var pendingAttach: [PendingAttach] = []
    @Published var prompts: [SavedPrompt] = []
    @Published var providers: [ProviderRow] = []
    @Published var plugins: [PluginRow] = []
    @Published var extensions: [ExtensionRow] = []
    @Published var mcpServers: [McpServer] = []
    @Published var searchHits: [SessionRow] = []
    @Published var cronRuns: [CronRun] = []
    @Published var termRows = 24
    @Published var termCols = 80
    @Published var shareExportURL: URL?
    @Published var personalities: [PersonalityRow] = []
    @Published var activePersonality = ""
    @Published var auxModels: [AuxModelRow] = []
    @Published var registry: [RegistryEntry] = []
    @Published var health = HealthInfo()
    @Published var runningCrons: Set<String> = []
    @Published var wsSuggestions: [String] = []
    @Published var compressing = false
    var currentSid: String = ""
    var streamId: String = ""
    var afterSeq: Int64 = 0
    var afterEventId = ""
    var userStopped = false
    var client: APIClient?
    private var pollTask: Task<Void, Never>?
    private var streamTask: Task<Void, Never>?
    private var sessionTask: Task<Void, Never>?
    private var listTask: Task<Void, Never>?
    private var termTask: Task<Void, Never>?
    private var audioPlayer: AVAudioPlayer?
    private var recorder: AVAudioRecorder?
    private var bgTask: UIBackgroundTaskIdentifier = .invalid
    private var outbound: [(String, [String])] = []

    func attach(url: URL) {
        let c = APIClient(baseURL: url)
        c.passwordProvider = { WebUIKeychain.read() ?? "" }
        c.onAuthLost = { [weak self] in
            Task { @MainActor in
                self?.needsLogin = true
                self?.loggedIn = false
                self?.ready = false
            }
        }
        client = c
    }

    func bootstrap() async {
        guard let c = client else { return }
        error = nil
        bootstrapping = true
        defer { bootstrapping = false }
        do {
            let st = try await c.authStatus()
            authEnabled = st.auth_enabled
            loggedIn = st.logged_in || !st.auth_enabled
            needsLogin = st.auth_enabled && !st.logged_in
            if needsLogin {
                AppSettings.migratePasswordFromDefaults()
                let pw = WebUIKeychain.read() ?? ""
                if !pw.isEmpty {
                    do {
                        try await c.login(password: pw)
                        needsLogin = false
                        loggedIn = true
                    } catch {
                        needsLogin = true
                        ready = false
                        return
                    }
                }
            }
            if loggedIn || !needsLogin {
                ready = true
                try await c.refreshCSRF()
                await loadSessions()
                await loadModels()
                await loadCommands()
                startPoll()
                listenForSessionList()
                let last = UserDefaults.standard.string(forKey: "lastSid") ?? ""
                if !last.isEmpty {
                    await openSid(last, keepPanel: true)
                } else if let first = sessions.first, !first.sid.isEmpty {
                    await openSid(first.sid, keepPanel: true)
                }
                voiceAvailable = await c.transcribeAvailable()
            } else {
                ready = false
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
            WebUIKeychain.write(password)
            UserDefaults.standard.removeObject(forKey: "webuiPassword")
            needsLogin = false
            loggedIn = true
            ready = true
            await loadSessions()
            await loadModels()
            await loadCommands()
            startPoll()
        } catch {
            self.error = "Login failed"
        }
    }

    func loadSessions() async {
        guard let c = client else { return }
        do {
            let res = try await c.sessions(source: sessionSource, includeArchived: includeArchived)
            sessions = res.rows
            webuiSessionCount = res.webuiCount
            cliSessionCount = res.cliCount
            projects = await c.projects()
        } catch { self.error = error.localizedDescription }
    }

    func setSessionSource(_ source: String) async {
        guard sessionSource != source else { return }
        sessionSource = source
        await loadSessions()
    }

    func loadModels() async {
        guard let c = client else { return }
        let r = await c.models()
        models = r.1
        if selectedModel.isEmpty, !r.0.isEmpty { selectedModel = r.0 }
        await loadReasoning()
        prompts = (try? await c.prompts()) ?? []
        if let pr = try? await c.profiles() {
            activeProfile = pr.0
            profiles = pr.1
        }
        spaces = (try? await c.spaces()) ?? []
    }

    func pickModel(_ id: String) {
        selectedModel = id
        Task { await loadReasoning() }
    }

    func setReasoning(_ effort: String) async {
        guard let c = client else { return }
        let provider = models.first(where: { $0.id == selectedModel })?.provider ?? ""
        reasoning = await c.setReasoning(effort: effort, model: selectedModel, provider: provider)
    }

    func loadReasoning() async {
        guard let c = client else { return }
        let provider = models.first(where: { $0.id == selectedModel })?.provider ?? ""
        reasoning = await c.reasoning(model: selectedModel, provider: provider)
    }

    func go(_ p: Panel) async {
        panel = p
        title = p == .chat ? title : p.label
        await loadPanel()
    }

    func loadConsole() async {
        guard let c = client else { return }
        let override = UserDefaults.standard.string(forKey: "dashboardURL") ?? ""
        let base = ConsoleFmt.consoleBase(webui: c.baseURL.absoluteString, override: override)
        do {
            console = try await c.consoleUsage(dashBase: base)
            if costConfig.models.isEmpty {
                if let cfg = try? await c.costConfig(dashBase: base) { costConfig = cfg }
            }
            consoleError = nil
        } catch {
            consoleError = error.localizedDescription
        }
    }

    func saveCostPlans(_ plans: [CostPlan]) async {
        guard let c = client else { return }
        let override = UserDefaults.standard.string(forKey: "dashboardURL") ?? ""
        let base = ConsoleFmt.consoleBase(webui: c.baseURL.absoluteString, override: override)
        let payload: [[String: Any]] = plans.map {
            ["name": $0.name, "price_usd": $0.price_usd, "cycle": $0.cycle, "note": $0.note, "covers_providers": $0.covers_providers]
        }
        do {
            try await c.saveCostConfig(dashBase: base, body: ["subscriptions": payload])
            if let cfg = try? await c.costConfig(dashBase: base) { costConfig = cfg }
            await loadConsole()
        } catch { consoleError = error.localizedDescription }
    }

    func saveCostRates(_ rates: [CostModelRate]) async {
        guard let c = client else { return }
        let override = UserDefaults.standard.string(forKey: "dashboardURL") ?? ""
        let base = ConsoleFmt.consoleBase(webui: c.baseURL.absoluteString, override: override)
        let payload: [[String: Any]] = rates.map {
            ["id": $0.id, "input": $0.input, "output": $0.output, "cache_read": $0.cache_read, "cache_write": $0.cache_write]
        }
        do {
            try await c.saveCostConfig(dashBase: base, body: ["models": payload])
            if let cfg = try? await c.costConfig(dashBase: base) { costConfig = cfg }
            await loadConsole()
        } catch { consoleError = error.localizedDescription }
    }

    func loadPanel() async {
        guard let c = client else { return }
        error = nil
        do {
            switch panel {
            case .chat: await loadSessions()
            case .tasks:
                jobs = try await c.crons()
                runningCrons = await c.cronsRunning()
            case .kanban: await loadKanban()
            case .skills: skills = try await c.skills()
            case .memory: memory = try await c.memory()
            case .spaces: spaces = try await c.spaces()
            case .profiles:
                let r = try await c.profiles()
                activeProfile = r.0
                profiles = r.1
            case .todos:
                if !currentSid.isEmpty { await openSid(currentSid, keepPanel: true) }
            case .files: await loadFiles(fsPath)
            case .terminal: break
            case .insights:
                insights = try await c.insights(days: insightsDays)
            case .console:
                await loadConsole()
            case .logs: logLines = try await c.logs(file: logFile, tail: logTail)
            case .settings:
                settingsItems = try await c.settings()
                settingEdits = [:]
                await loadModels()
                providers = (try? await c.providers()) ?? []
                plugins = (try? await c.plugins()) ?? []
                extensions = (try? await c.extensions()) ?? []
                mcpServers = await c.mcpServers()
                auxModels = await c.auxModels()
                registry = await c.extensionsRegistry()
                health = await c.health()
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func open(_ row: SessionRow) async { await openSid(row.sid) }

    private func detachChatStream() {
        // Leaving a possibly-streaming session: stop painting its events into the
        // next view. The turn keeps running server-side; apply() re-attaches via
        // active_stream_id when we come back.
        streamTask?.cancel()
        streamTask = nil
        streamId = ""
        afterSeq = 0
        busy = false
    }

    func openSid(_ id: String, keepPanel: Bool = false) async {
        guard let c = client else { return }
        if id != currentSid { detachChatStream() }
        currentSid = id
        UserDefaults.standard.set(id, forKey: "lastSid")
        liveText = ""
        if !keepPanel { panel = .chat }
        listenForSession()
        do {
            let load = try await c.loadSession(id: id)
            apply(load)
            await refreshYolo()
        } catch { self.error = error.localizedDescription }
    }

    func loadFullHistory() async {
        guard let c = client, !currentSid.isEmpty else { return }
        do { apply(try await c.loadSession(id: currentSid, full: true)) } catch { self.error = error.localizedDescription }
    }

    private func apply(_ load: SessionLoad) {
        if !load.title.isEmpty { title = load.title }
        if !load.model.isEmpty && selectedModel.isEmpty { selectedModel = load.model }
        if !load.model.isEmpty { Task { await loadReasoning() } }
        truncated = load.truncated
        let incoming = load.messages.filter { ["user", "assistant", "tool", "thinking", "system"].contains($0.role) }
        let steers = messages.filter { $0.role == "steer" }
        messages = incoming + steers
        todos = load.todos
        let liveId = load.activeStreamId
        if !liveId.isEmpty {
            if streamId != liveId || streamTask == nil {
                Task { await attachStream(liveId, replay: streamId == liveId && afterSeq > 0) }
            }
        } else if busy && !userStopped {
            settleTurn()
        }
    }

    func send(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let files = pendingAttach.map(\.path)
        if trimmed.hasPrefix("/") && files.isEmpty && isKnownSlash(trimmed) {
            await runSlash(trimmed)
            return
        }
        pendingAttach.removeAll()
        if trimmed.isEmpty && files.isEmpty { return }
        let shown = trimmed.isEmpty ? files.map { ($0 as NSString).lastPathComponent }.joined(separator: ", ") : trimmed
        messages.append(ChatMessage(id: "u-\(Int(Date().timeIntervalSince1970 * 1000))", role: busy ? "steer" : "user", content: shown))
        let payload = trimmed.isEmpty ? "See attached files." : trimmed
        if busy {
            if currentSid.isEmpty {
                outbound.append((payload, files))
                return
            }
            if !trimmed.isEmpty {
                let ok = await client?.steer(sessionId: currentSid, text: payload) ?? false
                if !ok && files.isEmpty { outbound.append((payload, [])) }
                else if !ok && !files.isEmpty { outbound.append((payload, files)) }
                else if ok && !files.isEmpty { outbound.append(("See attached files.", files)) }
            } else if !files.isEmpty {
                outbound.append((payload, files))
            }
            return
        }
        await startTurn(payload, files)
    }

    private func startTurn(_ trimmed: String, _ attachments: [String]) async {
        guard let c = client else { return }
        if currentSid.isEmpty { await newChat() }
        busy = true
        liveText = ""
        userStopped = false
        afterSeq = 0
        afterEventId = ""
        panel = .chat
        holdBackground()
        do {
            let start = try await c.startChat(
                sessionId: currentSid,
                message: trimmed,
                model: selectedModel.isEmpty ? nil : selectedModel,
                attachments: attachments,
                modelProvider: models.first(where: { $0.id == selectedModel })?.provider
            )
            if let stream = start.stream_id, !stream.isEmpty {
                await attachStream(stream, replay: false)
            } else {
                await recoverLive()
            }
        } catch {
            self.error = error.localizedDescription
            busy = false
            endBackground()
        }
    }

    func dropAttach(_ item: PendingAttach) {
        pendingAttach.removeAll { $0.path == item.path }
    }

    func attachFiles(_ urls: [URL]) async {
        guard let c = client else { return }
        if !(await ensureSid()) { return }
        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            do {
                let name = url.lastPathComponent.isEmpty ? "file" : url.lastPathComponent
                let dest = FileManager.default.temporaryDirectory.appendingPathComponent("up-\(UUID().uuidString)-\(name)")
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.copyItem(at: url, to: dest)
                let uploaded = try await c.upload(sid: currentSid, fileURL: dest, filename: name, mime: mimeType(for: url))
                pendingAttach.append(uploaded)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func attachData(_ data: Data, name: String, mime: String) async {
        guard let c = client else { return }
        if !(await ensureSid()) { return }
        do {
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent("up-\(UUID().uuidString)-\(name)")
            try data.write(to: dest)
            let uploaded = try await c.upload(sid: currentSid, fileURL: dest, filename: name, mime: mime)
            pendingAttach.append(uploaded)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() async {
        guard let c = client else { return }
        userStopped = true
        streamTask?.cancel()
        await c.cancelChat(sessionId: currentSid)
        settleTurn()
    }

    private func attachStream(_ id: String, replay: Bool) async {
        guard let c = client, !id.isEmpty else { return }
        streamId = id
        UserDefaults.standard.set(id, forKey: "lastStreamId")
        busy = true
        holdBackground()
        streamTask?.cancel()
        streamTask = Task { [weak self] in
            guard let self else { return }
            let ok = await c.streamTokens(id: id, replay: replay, afterSeq: self.afterSeq, afterEventId: self.afterEventId) { ev, data, eid in
                Task { @MainActor in self.handleSSE(event: ev, data: data, eventId: eid) }
            }
            await MainActor.run {
                if self.userStopped {
                    self.settleTurn()
                } else {
                    Task { await self.reconnectOrRecover(id) }
                }
            }
            _ = ok
        }
    }

    private func reconnectOrRecover(_ id: String) async {
        try? await Task.sleep(nanoseconds: 800_000_000)
        guard !userStopped else { return }
        let still = await client?.streamStatus(id: id) ?? false
        if still {
            await attachStream(id, replay: true)
        } else {
            await recoverLive()
        }
    }

    func recoverLive() async {
        let id = currentSid.isEmpty ? (UserDefaults.standard.string(forKey: "lastSid") ?? "") : currentSid
        guard !id.isEmpty, let c = client else { return }
        do {
            let load = try await c.loadSession(id: id)
            if currentSid.isEmpty {
                currentSid = id
                listenForSession()
            }
            apply(load)
        } catch { self.error = error.localizedDescription }
    }

    func onForeground() {
        guard loggedIn else { return }
        Task {
            await recoverLive()
            listenForSession()
            listenForSessionList()
        }
    }

    func onBackground() {
        holdBackground()
    }

    private func settleTurn() {
        flushLive()
        for i in messages.indices where messages[i].running {
            messages[i].running = false
        }
        busy = false
        streamId = ""
        UserDefaults.standard.set("", forKey: "lastStreamId")
        endBackground()
        Task { await loadSessions() }
        if !outbound.isEmpty {
            let next = outbound.removeFirst()
            Task { await startTurn(next.0, next.1) }
        }
    }

    private func handleSSE(event: String, data: String, eventId: String) {
        if !eventId.isEmpty {
            afterEventId = eventId
            if let n = Int64(eventId) { afterSeq = max(afterSeq, n) }
        } else {
            afterSeq += 1
        }
        guard let raw = data.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else { return }
        let ev = event.lowercased()
        if ev == "token" || ev == "delta" {
            liveText += (obj["text"] as? String) ?? (obj["delta"] as? String) ?? ""
            busy = true
        } else if ev == "todo_state", let arr = obj["todos"] as? [[String: Any]] {
            todos = arr.enumerated().map { i, t in
                TodoItem(
                    id: (t["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "\(i)",
                    content: (t["content"] as? String) ?? (t["text"] as? String) ?? (t["title"] as? String) ?? "",
                    status: (t["status"] as? String) ?? "pending"
                )
            }
        } else if ev.contains("think") || ev == "reasoning" {
            let piece = (obj["text"] as? String) ?? (obj["delta"] as? String) ?? (obj["content"] as? String) ?? (obj["thinking"] as? String) ?? ""
            if let idx = messages.lastIndex(where: { $0.role == "thinking" && $0.running }) {
                messages[idx].content += piece
            } else if !piece.isEmpty {
                messages.append(ChatMessage(id: "th-\(Int(Date().timeIntervalSince1970 * 1000))", role: "thinking", content: piece, running: true))
            }
        } else if ev.contains("tool") {
            let name = (obj["name"] as? String) ?? (obj["tool"] as? String) ?? (obj["function"] as? String) ?? "tool"
            let preview = (obj["preview"] as? String) ?? (obj["snippet"] as? String) ?? (obj["result"] as? String) ?? (obj["output"] as? String) ?? (obj["text"] as? String) ?? ""
            let done = ev.contains("done") || ev.contains("result") || ev.contains("end") || jsonFlag(obj["done"])
            if let idx = messages.lastIndex(where: { $0.role == "tool" && $0.tool == name && $0.running }) {
                if !preview.isEmpty {
                    messages[idx].preview = preview
                    messages[idx].content = preview
                }
                messages[idx].running = !done
            } else {
                messages.append(ChatMessage(id: "t-\(Int(Date().timeIntervalSince1970 * 1000))", role: "tool", content: preview, tool: name, preview: preview, running: !done))
            }
        } else if ev == "done" || ev.contains("complete") {
            settleTurn()
        } else if ev == "error" {
            error = (obj["error"] as? String) ?? (obj["message"] as? String)
            settleTurn()
        }
    }

    func newChat() async {
        guard let c = client else { return }
        do {
            detachChatStream()
            currentSid = try await c.newSession()
            UserDefaults.standard.set(currentSid, forKey: "lastSid")
            messages = []; liveText = ""; todos = []
            title = "New conversation"
            truncated = false
            panel = .chat
            listenForSession()
            await loadSessions()
        } catch { self.error = error.localizedDescription }
    }

    func deleteSession(_ id: String) async {
        guard let c = client else { return }
        await c.deleteSession(id: id)
        if currentSid == id {
            detachChatStream()
            currentSid = ""; messages = []; liveText = ""; title = "Hermes"
            UserDefaults.standard.set("", forKey: "lastSid")
        }
        await loadSessions()
        // Desktop parity (sessions.js): after deleting the CURRENT session, load
        // the most recent remaining one so file/terminal ops keep a valid session_id.
        if currentSid.isEmpty, let next = sessions.first(where: { $0.sid != id }) {
            await openSid(next.sid, keepPanel: true)
        }
    }

    func pinSession(_ row: SessionRow) async {
        await client?.pinSession(id: row.sid, pinned: !(row.pinned ?? false))
        await loadSessions()
    }

    func archiveSession(_ row: SessionRow) async {
        await client?.archiveSession(id: row.sid, archived: !row.archived)
        await loadSessions()
    }

    func renameSession(_ id: String, _ titleText: String) async {
        await client?.renameSession(id: id, title: titleText)
        if currentSid == id { title = titleText }
        await loadSessions()
    }

    func duplicateSession(_ id: String) async {
        _ = await client?.duplicateSession(id: id)
        await loadSessions()
    }

    func moveSession(_ id: String, _ projectId: String?) async {
        await client?.moveSession(id: id, projectId: projectId)
        await loadSessions()
    }

    func clearSession(_ id: String) async {
        await client?.clearSession(id: id)
        if currentSid == id { messages = []; liveText = "" }
    }

    func shareSession(_ id: String) async {
        let url = await client?.shareCreate(id: id) ?? ""
        shareNotice = url.isEmpty ? "Share failed" : url
        if !url.isEmpty { UIPasteboard.general.string = url }
    }

    func branchSession(_ id: String) async {
        let newId = await client?.branchSession(id: id) ?? ""
        await loadSessions()
        if !newId.isEmpty { await openSid(newId) }
    }

    func importSessionJson(_ text: String) async {
        let id = await client?.importSession(jsonText: text) ?? ""
        await loadSessions()
        if !id.isEmpty { await openSid(id) }
        else { error = "Import failed" }
    }

    func onSessionQuery(_ q: String) async {
        sessionQuery = q
        let needle = q.trimmingCharacters(in: .whitespaces)
        if needle.count < 2 {
            searchHits = []
            return
        }
        try? await Task.sleep(nanoseconds: 280_000_000)
        guard sessionQuery == q else { return }
        searchHits = await client?.searchSessions(q: needle) ?? []
    }

    func exportSession(_ format: String, id: String) async {
        guard !id.isEmpty else { return }
        let ext = format == "html" ? "html" : (format == "md" ? "md" : "json")
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent("hermes-\(id).\(ext)")
        do {
            if format == "md" {
                var md = "# \(title)\n\n"
                for m in messages {
                    md += "**\(m.role)**\n\n\(m.content)\n\n"
                }
                try md.write(to: dest, atomically: true, encoding: .utf8)
            } else {
                let data = try await client?.downloadExport(id: id, format: format) ?? Data()
                try data.write(to: dest)
            }
            shareExportURL = dest
            presentShare(dest)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func presentShare(_ url: URL) {
        let av = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        top.present(av, animated: true)
    }

    func createProject(_ name: String) async {
        await client?.createProject(name: name)
        await loadSessions()
    }

    func deleteProject(_ id: String) async {
        await client?.deleteProject(id: id)
        if activeProjectId == id { activeProjectId = "" }
        await loadSessions()
    }

    private func flushLive() {
        if !liveText.isEmpty {
            let text = liveText
            messages.append(ChatMessage(id: "a-\(Int(Date().timeIntervalSince1970 * 1000))", role: "assistant", content: text))
            liveText = ""
            if speakReplies { Task { await speak(text) } }
        }
    }

    func ensureSid() async -> Bool {
        if !currentSid.isEmpty { return true }
        guard let c = client else { return false }
        do {
            currentSid = try await c.newSession()
            return !currentSid.isEmpty
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func loadFiles(_ path: String) async {
        guard let c = client else { return }
        if !(await ensureSid()) {
            error = "Open or create a chat first — Files uses that session workspace."
            return
        }
        do {
            let r = try await c.listDir(sid: currentSid, path: path)
            fsRoot = r.0
            fsPath = path
            fsEntries = r.1
            fileDoc = nil
        } catch { self.error = error.localizedDescription }
    }

    func openFs(_ entry: FsEntry) async {
        if entry.isDir { await loadFiles(entry.path) } else { await openFile(entry.path) }
    }

    func fsUp() async {
        if fsPath.isEmpty || fsPath == "." { return }
        let parent = (fsPath as NSString).deletingLastPathComponent
        await loadFiles(parent.isEmpty ? "." : parent)
    }

    func openFile(_ path: String) async {
        guard let c = client else { return }
        do {
            let doc = try await c.readFile(sid: currentSid, path: path)
            fileDoc = doc
            fileDraft = doc.content
        } catch { self.error = error.localizedDescription }
    }

    func saveFile() async {
        guard let c = client, let path = fileDoc?.path else { return }
        await c.saveFile(sid: currentSid, path: path, content: fileDraft)
    }

    private func joinFs(_ name: String) -> String {
        let dir = fsPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if dir.isEmpty || dir == "." { return name.trimmingCharacters(in: .whitespaces) }
        return dir + "/" + name.trimmingCharacters(in: .whitespaces)
    }

    func createFile(_ name: String) async {
        guard let c = client else { return }
        let path = joinFs(name)
        guard !path.isEmpty else { return }
        await c.createFile(sid: currentSid, path: path)
        await loadFiles(fsPath)
        await openFile(path)
    }

    func createDir(_ name: String) async {
        guard let c = client else { return }
        let path = joinFs(name)
        guard !path.isEmpty else { return }
        await c.createDir(sid: currentSid, path: path)
        await loadFiles(fsPath)
    }

    func deleteFs(_ entry: FsEntry) async {
        guard let c = client else { return }
        await c.deleteFile(sid: currentSid, path: entry.path)
        if fileDoc?.path == entry.path { fileDoc = nil }
        await loadFiles(fsPath)
    }

    func renameFs(_ entry: FsEntry, _ newName: String) async {
        guard let c = client, !newName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        await c.renameFile(sid: currentSid, path: entry.path, newName: newName.trimmingCharacters(in: .whitespaces))
        await loadFiles(fsPath)
    }

    func moveFs(_ entry: FsEntry, _ destDir: String) async {
        guard let c = client, !destDir.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        await c.moveFile(sid: currentSid, path: entry.path, destDir: destDir.trimmingCharacters(in: .whitespaces))
        await loadFiles(fsPath)
    }

    func loadCommands() async {
        commands = await client?.commands().filter { !$0.cliOnly } ?? []
    }

    private func slashName(_ text: String) -> String {
        String(text.trimmingCharacters(in: .whitespaces).dropFirst()).split(separator: " ").first.map(String.init)?.lowercased() ?? ""
    }

    func isKnownSlash(_ text: String) -> Bool {
        let name = slashName(text)
        return !name.isEmpty && commands.contains { $0.name.lowercased() == name }
    }

    func matchingCommands(_ draft: String) -> [SlashCommand] {
        guard draft.hasPrefix("/") else { return [] }
        let q = String(draft.dropFirst()).split(separator: " ").first.map(String.init)?.lowercased() ?? ""
        return commands.filter { $0.name.lowercased().hasPrefix(q) }.prefix(12).map { $0 }
    }

    func runSlash(_ text: String) async {
        let name = slashName(text)
        let rest = text.trimmingCharacters(in: .whitespaces).drop(while: { $0 != " " }).trimmingCharacters(in: .whitespaces)
        switch name {
        case "new", "reset": await newChat()
        case "retry": await retryLast()
        case "undo": await undoLast()
        case "yolo": await toggleYolo()
        case "compress": await compressSession()
        case "personality":
            if rest.isEmpty { await loadPersonalities() }
            else { await setPersonality(["none", "default", "clear"].contains(rest.lowercased()) ? "" : rest) }
        case "title":
            if rest.isEmpty { await regenerateTitle() } else { await execSlash(text) }
        default: await execSlash(text)
        }
    }

    func execSlash(_ text: String) async {
        let out = await client?.execCommand(text.trimmingCharacters(in: .whitespaces)) ?? ""
        commandOutput = out.isEmpty ? "(no output)" : out
        if !currentSid.isEmpty { await openSid(currentSid, keepPanel: true) }
        await loadSessions()
    }

    func retryLast() async {
        guard !currentSid.isEmpty else { return }
        await client?.retrySession(id: currentSid)
        await openSid(currentSid, keepPanel: true)
    }

    func undoLast() async {
        guard !currentSid.isEmpty else { return }
        await client?.undoSession(id: currentSid)
        await openSid(currentSid, keepPanel: true)
    }

    func regenerateTitle() async {
        guard !currentSid.isEmpty else { return }
        let t = await client?.regenerateTitle(id: currentSid) ?? ""
        if !t.isEmpty { title = t }
        await loadSessions()
    }

    func refreshYolo() async {
        guard !currentSid.isEmpty else { return }
        yoloEnabled = await client?.yoloStatus(id: currentSid) ?? false
    }

    func toggleYolo() async {
        guard !currentSid.isEmpty else { return }
        yoloEnabled = await client?.setYolo(id: currentSid, enabled: !yoloEnabled) ?? !yoloEnabled
    }

    func signOut() async {
        await client?.logout()
        needsLogin = true
        loggedIn = false
        ready = false
        messages = []
        liveText = ""
        sessions = []
    }

    func checkUpdates() async {
        updatesBusy = true
        updates = await client?.updatesCheck() ?? UpdatesStatus()
        updatesBusy = false
    }

    func applyUpdate(_ target: String) async {
        updatesBusy = true
        let msg = await client?.updatesApply(target: target) ?? "apply failed"
        commandOutput = msg == "ok" ? "Update \(target) started" : msg
        updates = await client?.updatesCheck() ?? updates
        updatesBusy = false
    }

    func loadPersonalities() async {
        personalities = await client?.personalities() ?? []
    }

    func setPersonality(_ name: String) async {
        guard !currentSid.isEmpty else { return }
        await client?.setPersonality(sid: currentSid, name: name)
        activePersonality = name
        commandOutput = name.isEmpty ? "Personality cleared" : "Personality set: \(name)"
    }

    func setDefaultModel(_ opt: ModelOption) async {
        await client?.setDefaultModel(provider: opt.provider, model: ModelIds.forSend(opt.id, provider: opt.provider))
        commandOutput = "Default model saved: \(opt.label)"
    }

    func refreshProviderModels(_ provider: String) async {
        let msg = await client?.refreshModels(provider: provider) ?? "refresh failed"
        commandOutput = msg == "ok" ? "Models refreshed for \(provider)" : msg
        await loadModels()
    }

    func removeProviderKey(_ provider: String) async {
        await client?.deleteProviderKey(provider: provider)
        await loadPanel()
    }

    func toggleExtension(_ id: String, _ enabled: Bool) async {
        await client?.extensionToggle(id: id, enabled: enabled)
        commandOutput = "Extension \(enabled ? "enabled" : "disabled"). Reload WebUI to apply."
        await loadPanel()
    }

    func installExtension(_ entry: RegistryEntry) async {
        await client?.extensionInstall(entry)
        commandOutput = "Installed \(entry.name)"
        await loadPanel()
    }

    func uninstallExtension(_ id: String) async {
        await client?.extensionUninstall(id: id)
        await loadPanel()
    }

    func compressSession() async {
        guard !currentSid.isEmpty, !compressing, let c = client else { return }
        compressing = true
        let target = currentSid
        var status = await c.compressStart(sid: target)
        var err = ""
        while status != "done" && status != "error" {
            try? await Task.sleep(nanoseconds: 900_000_000)
            let (s, e) = await c.compressStatus(sid: target)
            status = s; err = e
        }
        commandOutput = status == "done" ? "Session compressed" : "Compression failed: \(err.isEmpty ? "error" : err)"
        if currentSid == target { await openSid(target, keepPanel: true) }
        compressing = false
    }

    func suggestWorkspaces(_ prefix: String) async {
        wsSuggestions = await client?.workspacesSuggest(prefix: prefix) ?? []
    }

    func moveWorkspace(_ path: String, up: Bool) async {
        var paths = spaces.compactMap { $0.path.isEmpty ? nil : $0.path }
        guard let i = paths.firstIndex(of: path) else { return }
        let j = up ? i - 1 : i + 1
        guard j >= 0, j < paths.count else { return }
        paths.swapAt(i, j)
        await client?.workspacesReorder(paths: paths)
        await loadModels()
        await loadPanel()
    }

    func startTerm() async {
        guard let c = client else { return }
        if !(await ensureSid()) {
            error = "Open or create a chat first — Terminal is bound to that session."
            return
        }
        do {
            try await c.startTerminal(sid: currentSid, rows: termRows, cols: termCols)
            termRunning = true
            termTask?.cancel()
            termTask = Task { [weak self] in
                guard let self else { return }
                await c.streamTerminal(sid: self.currentSid) { ev, data in
                    Task { @MainActor in self.onTerm(ev, data) }
                }
                await MainActor.run { self.termRunning = false }
            }
        } catch { self.error = error.localizedDescription }
    }

    func termType(_ line: String) async {
        let payload = line.hasSuffix("\n") ? line : line + "\n"
        await client?.terminalInput(sid: currentSid, data: payload)
    }

    func stopTerm() async {
        termTask?.cancel()
        await client?.closeTerminal(sid: currentSid)
        termRunning = false
    }

    func resizeTerm() async {
        guard !currentSid.isEmpty else { return }
        await client?.resizeTerminal(sid: currentSid, rows: termRows, cols: termCols)
    }

    private func onTerm(_ ev: String, _ data: String) {
        guard let raw = data.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else { return }
        let e = ev.lowercased()
        if e == "output", let t = obj["text"] as? String, !t.isEmpty {
            var next = stripAnsi(termText + t)
            if next.count > 24000 { next = String(next.suffix(20000)) }
            termText = next
        } else if e == "terminal_closed" || e == "terminal_error" {
            if e == "terminal_error" { error = obj["error"] as? String }
            termRunning = false
        }
    }

    func speak(_ text: String) async {
        guard let c = client else { return }
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        do {
            let data = try await c.tts(text: clean)
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            audioPlayer = try AVAudioPlayer(data: data)
            audioPlayer?.play()
        } catch { self.error = error.localizedDescription }
    }

    func startListen() {
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] ok in
            Task { @MainActor in
                guard let self else { return }
                if !ok {
                    self.error = "Microphone permission denied"
                    return
                }
                self.beginRecording()
            }
        }
    }

    private func beginRecording() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("hermes-dictation.m4a")
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try AVAudioSession.sharedInstance().setActive(true)
            recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder?.record()
            listening = true
        } catch { self.error = error.localizedDescription }
    }

    func stopListen() async -> String {
        listening = false
        recorder?.stop()
        let url = recorder?.url
        recorder = nil
        guard let url, let c = client else { return "" }
        transcribing = true
        defer { transcribing = false }
        do { return try await c.transcribe(fileURL: url) } catch {
            self.error = error.localizedDescription
            return ""
        }
    }

    func cronAction(_ id: String, _ action: String) async {
        await client?.cronAction(id: id, action: action)
        await loadPanel()
    }

    func createCron(name: String, schedule: String, prompt: String) async {
        do {
            try await client?.createCron(name: name, schedule: schedule, prompt: prompt)
            await loadPanel()
        } catch { self.error = error.localizedDescription }
    }

    func deleteCron(_ id: String) async {
        await client?.deleteCron(id: id)
        await loadPanel()
    }

    func loadJobOutput(_ id: String) async {
        jobOutput = await client?.cronOutput(id: id) ?? ""
        cronRuns = await client?.cronHistory(id: id) ?? []
    }

    func updateCron(id: String, name: String, schedule: String, prompt: String, deliver: String) async {
        await client?.updateCron(id: id, name: name, schedule: schedule, prompt: prompt, deliver: deliver)
        await loadPanel()
    }

    func loadKanban() async {
        guard let c = client else { return }
        error = nil
        let (cur, boards) = await c.kanbanBoards()
        kanbanBoards = boards
        if kanbanBoard.isEmpty || !boards.contains(where: { $0.slug == kanbanBoard }) {
            kanbanBoard = cur.isEmpty ? (boards.first?.slug ?? "") : cur
        }
        do {
            columns = try await c.kanban(
                board: kanbanBoard,
                assignee: kanbanAssignee,
                tenant: kanbanTenant,
                includeArchived: kanbanArchived,
                onlyMine: kanbanMine
            )
            kanbanStats = await c.kanbanStats(board: kanbanBoard)
            kanbanAssignees = await c.kanbanAssignees(board: kanbanBoard)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func switchKanbanBoard(_ slug: String) async {
        kanbanBoard = slug
        await client?.switchKanbanBoard(slug)
        await loadKanban()
    }

    func createKanbanTask() async {
        let title = kanbanDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { return }
        kanbanDraft = ""
        await client?.createKanbanTask(title: title, board: kanbanBoard)
        await loadKanban()
    }

    func dispatchKanban(dry: Bool) async {
        await client?.dispatchKanban(board: kanbanBoard, dryRun: dry)
        await loadKanban()
    }

    func createKanbanBoard(_ name: String) async {
        let slug = name.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        guard !slug.isEmpty else { return }
        await client?.createKanbanBoard(name: name, slug: slug)
        kanbanBoard = slug
        await loadKanban()
    }

    func toggleKanbanSelect(_ id: String) {
        if kanbanSelected.contains(id) { kanbanSelected.remove(id) } else { kanbanSelected.insert(id) }
    }

    func bulkKanban() async {
        let ids = Array(kanbanSelected)
        guard !ids.isEmpty else { return }
        await client?.bulkKanban(ids: ids, status: kanbanBulkStatus, board: kanbanBoard)
        kanbanSelected.removeAll()
        await loadKanban()
    }

    func moveTask(_ id: String, _ status: String) async {
        await client?.moveKanban(id: id, status: status, board: kanbanBoard)
        await loadKanban()
        if var open = kanbanOpen, open.id == id {
            if status == "archived" { kanbanOpen = nil }
            else { open.status = status; kanbanOpen = open }
        }
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
        await loadModels()
    }

    func createProfile(_ name: String) async {
        do {
            try await client?.createProfile(name: name)
            await loadPanel()
        } catch { self.error = error.localizedDescription }
    }

    func deleteProfile(_ name: String) async {
        await client?.deleteProfile(name: name)
        await loadPanel()
    }

    func addWorkspace(_ path: String) async {
        do {
            try await client?.addWorkspace(path: path)
            await loadPanel()
        } catch { self.error = error.localizedDescription }
    }

    func removeWorkspace(_ path: String) async {
        await client?.removeWorkspace(path: path)
        await loadPanel()
    }

    func saveSkill(name: String, category: String, content: String) async {
        do {
            try await client?.saveSkill(name: name, category: category, content: content)
            await loadPanel()
        } catch { self.error = error.localizedDescription }
    }

    func deleteSkill(_ name: String) async {
        await client?.deleteSkill(name: name)
        await loadPanel()
    }

    func setLogFile(_ file: String) async {
        logFile = file
        await loadPanel()
    }

    func setLogTail(_ n: Int) async {
        logTail = n
        await loadPanel()
    }

    func saveSettings() async {
        guard let c = client, !settingEdits.isEmpty else { return }
        var body: [String: Any] = [:]
        for (k, v) in settingEdits {
            let item = settingsItems.first { $0.key == k }
            if item?.type == "bool" || v == "true" || v == "false" { body[k] = (v == "true") }
            else if item?.type == "number" || Int(v) != nil { body[k] = Int(v) ?? v }
            else { body[k] = v }
        }
        await c.saveSettings(body)
        await loadPanel()
    }

    func setProviderKey(_ id: String, _ key: String) async {
        await client?.setProviderKey(id: id, key: key)
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
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                guard let self, let c = self.client, self.loggedIn else { continue }
                if self.panel == .insights { await self.loadPanel() }
                if self.panel == .console { await self.loadConsole() }
                guard !self.currentSid.isEmpty else { continue }
                let a = await c.approval(sid: self.currentSid)
                let q = await c.clarify(sid: self.currentSid)
                let st = await c.sessionStatus(sid: self.currentSid)
                await MainActor.run {
                    self.approval = a
                    self.clarify = q
                }
                if !st.0.isEmpty && (self.streamId != st.0 || self.streamTask == nil) && !self.userStopped {
                    await self.attachStream(st.0, replay: self.streamId == st.0)
                }
                if st.1 > self.messages.count && !self.busy {
                    await self.recoverLive()
                }
            }
        }
    }

    private var sessionStreamBackoff: UInt64 = 1_500_000_000
    private var listStreamBackoff: UInt64 = 2_000_000_000

    private func listenForSession() {
        guard let c = client, !currentSid.isEmpty else { return }
        sessionTask?.cancel()
        let sid = currentSid
        sessionTask = Task { [weak self] in
            while !Task.isCancelled {
                await c.streamSession(sid: sid, knownCount: self?.messages.count ?? 0) { ev, data in
                    Task { @MainActor in
                        guard let self else { return }
                        self.sessionStreamBackoff = 1_500_000_000
                        let e = ev.lowercased()
                        let obj = (data.data(using: .utf8)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
                        if e == "server_turn_started" {
                            let id = (obj["stream_id"] as? String) ?? (obj["streamId"] as? String) ?? ""
                            if !id.isEmpty && !self.userStopped {
                                await self.attachStream(id, replay: self.streamId == id)
                            }
                        } else if e == "session_updated" || e.contains("complete") {
                            await self.recoverLive()
                        }
                    }
                }
                let wait = await MainActor.run { self?.sessionStreamBackoff ?? 1_500_000_000 }
                try? await Task.sleep(nanoseconds: wait)
                await MainActor.run {
                    self?.sessionStreamBackoff = min(wait * 2, 30_000_000_000)
                }
            }
        }
    }

    private func listenForSessionList() {
        guard let c = client else { return }
        listTask?.cancel()
        listTask = Task { [weak self] in
            while !Task.isCancelled {
                await c.streamSessionList { ev, _ in
                    Task { @MainActor in
                        self?.listStreamBackoff = 2_000_000_000
                    }
                    if ev.contains("session") || ev.isEmpty || ev == "sessions_changed" {
                        Task { await self?.loadSessions() }
                    }
                }
                let wait = await MainActor.run { self?.listStreamBackoff ?? 2_000_000_000 }
                try? await Task.sleep(nanoseconds: wait)
                await MainActor.run {
                    self?.listStreamBackoff = min(wait * 2, 30_000_000_000)
                }
            }
        }
    }

    private func holdBackground() {
        if bgTask != .invalid { return }
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "hermes-live") { [weak self] in
            Task { @MainActor in self?.endBackground() }
        }
    }

    private func endBackground() {
        if bgTask != .invalid {
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
        }
    }
}

private func jsonFlag(_ any: Any?) -> Bool {
    if let b = any as? Bool { return b }
    if let n = any as? NSNumber { return n.boolValue }
    if let s = any as? String { return s == "true" || s == "1" }
    return false
}

private func stripAnsi(_ s: String) -> String {
    let pattern = "\\u{001B}\\[[0-9;?]*[A-Za-z]|\\u{001B}\\].*?(\\u{0007}|\\u{001B}\\\\)"
    return s.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
}
