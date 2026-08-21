import Foundation
import Combine

@MainActor
final class AppStore: ObservableObject {
    @Published var sessions: [SessionRow] = []
    @Published var messages: [ChatMessage] = []
    @Published var liveText: String = ""
    @Published var title: String = "Hermes"
    @Published var error: String?
    @Published var busy = false
    @Published var needsLogin = false
    @Published var loggedIn = false
    @Published var authEnabled = false
    @Published var panel: Panel = .chat
    @Published var detail: String = ""
    var currentSid: String = ""
    var client: APIClient?

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
        } catch {
            self.error = "Login failed"
        }
    }

    func loadSessions() async {
        guard let c = client else { return }
        do {
            sessions = try await c.sessions()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func open(_ row: SessionRow) async {
        guard let c = client else { return }
        currentSid = row.sid
        title = row.displayTitle
        liveText = ""
        do {
            let r = try await c.sessionMessages(id: row.sid)
            messages = r.messages
            if !r.title.isEmpty { title = r.title }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func newChat() async {
        guard let c = client else { return }
        do {
            let sid = try await c.newSession()
            currentSid = sid
            messages = []
            liveText = ""
            title = "New conversation"
            panel = .chat
            await loadSessions()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func go(_ p: Panel) async {
        panel = p
        title = p == .chat ? "Hermes" : p.label
        await loadPanel()
    }

    func loadPanel() async {
        guard let c = client else { return }
        do {
            switch panel {
            case .chat:
                await loadSessions()
            case .tasks:
                detail = try await c.getText("/api/crons")
            case .kanban:
                detail = (try? await c.getText("/api/kanban/tasks")) ?? (try await c.getText("/api/kanban/board"))
            case .spaces:
                detail = try await c.getText("/api/workspaces")
            case .skills:
                detail = try await c.getText("/api/skills")
            case .memory:
                detail = try await c.getText("/api/memory")
            case .logs:
                detail = try await c.getText("/api/logs")
            case .profiles:
                detail = try await c.getText("/api/profiles")
            case .dashboard:
                var s = ""
                s += "HEALTH\n" + ((try? await c.getText("/health")) ?? "") + "\n\n"
                s += "DASHBOARD STATUS\n" + ((try? await c.getText("/api/dashboard/status")) ?? "") + "\n\n"
                s += "DASHBOARD CONFIG\n" + ((try? await c.getText("/api/dashboard/config")) ?? "") + "\n\n"
                s += "AGENT HEALTH\n" + ((try? await c.getText("/api/health/agent")) ?? "")
                detail = s
            case .settings:
                break
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func send(_ text: String) async {
        guard let c = client else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if currentSid.isEmpty { await newChat() }
        messages.append(ChatMessage(role: "user", content: trimmed))
        busy = true
        liveText = ""
        do {
            let start = try await c.startChat(sessionId: currentSid, message: trimmed)
            if let stream = start.stream_id, !stream.isEmpty {
                await streamTokens(id: stream)
            }
        } catch {
            self.error = error.localizedDescription
        }
        busy = false
    }

    private func streamTokens(id: String) async {
        guard let c = client else { return }
        let u = c.baseURL.appendingPathComponent("api/chat/stream")
        var comp = URLComponents(url: u, resolvingAgainstBaseURL: false)!
        comp.queryItems = [URLQueryItem(name: "stream_id", value: id)]
        guard let url = comp.url else { return }
        var req = URLRequest(url: url)
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        do {
            let (bytes, _) = try await URLSession.shared.bytes(for: req)
            var event = ""
            for try await line in bytes.lines {
                if line.hasPrefix("event:") {
                    event = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                } else if line.hasPrefix("data:") {
                    let data = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                    handleSSE(event: event, data: data)
                } else if line.isEmpty {
                    event = ""
                }
            }
        } catch {
            self.error = error.localizedDescription
        }
        if !liveText.isEmpty {
            messages.append(ChatMessage(role: "assistant", content: liveText))
            liveText = ""
        }
    }

    private func handleSSE(event: String, data: String) {
        guard let raw = data.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: raw) else { return }
        let ev = event.lowercased()
        if ev == "token" || ev == "delta" {
            if let d = obj as? [String: Any] {
                liveText += (d["text"] as? String) ?? (d["delta"] as? String) ?? ""
            }
        } else if ev.contains("tool") {
            if let d = obj as? [String: Any] {
                let name = (d["name"] as? String) ?? (d["tool"] as? String) ?? "tool"
                messages.append(ChatMessage(role: "assistant", content: "", tool_name: name))
            }
        } else if ev == "done" || ev.contains("complete") {
            if !liveText.isEmpty {
                messages.append(ChatMessage(role: "assistant", content: liveText))
                liveText = ""
            }
        }
    }
}
