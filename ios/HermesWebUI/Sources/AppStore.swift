import Combine
import Foundation
import AVFoundation
import UIKit
import UniformTypeIdentifiers

@MainActor
final class AppStore: ObservableObject {
    @Published var sessions: [SessionRow] = []
    @Published var sessionQuery = ""
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
    @Published var console = ConsoleUsage()
    @Published var consoleError: String?
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
        client = APIClient(baseURL: url)
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
            if needsLogin, let pw = UserDefaults.standard.string(forKey: "webuiPassword"), !pw.isEmpty {
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
            if loggedIn || !needsLogin {
                ready = true
                try await c.refreshCSRF()
                await loadSessions()
                await loadModels()
                startPoll()
                listenForSessionList()
                let last = UserDefaults.standard.string(forKey: "lastSid") ?? ""
                if !last.isEmpty { await openSid(last, keepPanel: true) }
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
            UserDefaults.standard.set(password, forKey: "webuiPassword")
            needsLogin = false
            loggedIn = true
            ready = true
            await loadSessions()
            await loadModels()
            startPoll()
        } catch {
            self.error = "Login failed"
        }
    }

    func loadSessions() async {
        guard let c = client else { return }
        do { sessions = try await c.sessions() } catch { self.error = error.localizedDescription }
    }

    func loadModels() async {
        guard let c = client else { return }
        let r = await c.models()
        models = r.1
        if selectedModel.isEmpty, !r.0.isEmpty { selectedModel = r.0 }
        prompts = (try? await c.prompts()) ?? []
        if let pr = try? await c.profiles() {
            activeProfile = pr.0
            profiles = pr.1
        }
        spaces = (try? await c.spaces()) ?? []
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
            consoleError = nil
        } catch {
            consoleError = error.localizedDescription
        }
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
            case .files: await loadFiles(fsPath)
            case .terminal: break
            case .insights: insights = try await c.insights()
            case .logs: logLines = try await c.logs(file: logFile)
            case .dashboard:
                await loadConsole()
            case .settings:
                settingsItems = try await c.settings()
                settingEdits = [:]
                await loadModels()
                providers = (try? await c.providers()) ?? []
                plugins = (try? await c.plugins()) ?? []
                extensions = (try? await c.extensions()) ?? []
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func open(_ row: SessionRow) async { await openSid(row.sid) }

    func openSid(_ id: String, keepPanel: Bool = false) async {
        guard let c = client else { return }
        currentSid = id
        UserDefaults.standard.set(id, forKey: "lastSid")
        liveText = ""
        if !keepPanel { panel = .chat }
        listenForSession()
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
        messages = load.messages.filter { ["user", "assistant", "tool", "thinking", "system"].contains($0.role) }
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
        pendingAttach.removeAll()
        if trimmed.isEmpty && files.isEmpty { return }
        let shown = trimmed.isEmpty ? files.map { ($0 as NSString).lastPathComponent }.joined(separator: ", ") : trimmed
        messages.append(ChatMessage(id: "u-\(Int(Date().timeIntervalSince1970 * 1000))", role: "user", content: shown))
        let payload = trimmed.isEmpty ? "See attached files." : trimmed
        if busy {
            outbound.append((payload, files))
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
                attachments: attachments
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
            let done = ev.contains("done") || ev.contains("result") || ev.contains("end") || (obj["done"] as? String) == "true"
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
            currentSid = ""; messages = []; liveText = ""; title = "Hermes"
        }
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

    func startTerm() async {
        guard let c = client else { return }
        if !(await ensureSid()) {
            error = "Open or create a chat first — Terminal is bound to that session."
            return
        }
        do {
            try await c.startTerminal(sid: currentSid)
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
        await loadModels()
    }

    func setLogFile(_ file: String) async {
        logFile = file
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
                if self.panel == .dashboard { await self.loadConsole() }
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

    private func listenForSession() {
        guard let c = client, !currentSid.isEmpty else { return }
        sessionTask?.cancel()
        let sid = currentSid
        sessionTask = Task { [weak self] in
            while !Task.isCancelled {
                await c.streamSession(sid: sid, knownCount: self?.messages.count ?? 0) { ev, data in
                    Task { @MainActor in
                        guard let self else { return }
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
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
    }

    private func listenForSessionList() {
        guard let c = client else { return }
        listTask?.cancel()
        listTask = Task { [weak self] in
            while !Task.isCancelled {
                await c.streamSessionList { ev, _ in
                    if ev.contains("session") || ev.isEmpty || ev == "sessions_changed" {
                        Task { await self?.loadSessions() }
                    }
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
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

private func stripAnsi(_ s: String) -> String {
    let pattern = "\\u{001B}\\[[0-9;?]*[A-Za-z]|\\u{001B}\\].*?(\\u{0007}|\\u{001B}\\\\)"
    return s.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
}
