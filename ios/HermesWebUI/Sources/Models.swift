import Foundation

struct AuthStatus: Codable {
    var auth_enabled: Bool = false
    var logged_in: Bool = false
    var password_auth_enabled: Bool = false
}

struct SessionRow: Codable, Identifiable {
    var session_id: String? = nil
    var raw_id: String? = nil
    var title: String? = nil
    var preview: String? = nil
    var message_count: Int? = nil
    var messages: Int? = nil
    var source: String? = nil
    var updated_at: String? = nil
    var model: String? = nil

    enum CodingKeys: String, CodingKey {
        case session_id, title, preview, message_count, messages, source, updated_at, model
        case raw_id = "id"
    }

    var sid: String { session_id ?? raw_id ?? "" }
    var id: String { sid }
    var displayTitle: String {
        let t = (title ?? "").trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? "New conversation" : t
    }
    var msgCount: Int { message_count ?? messages ?? 0 }
}

struct SessionList: Codable {
    var sessions: [SessionRow]? = nil
    var data: [SessionRow]? = nil
    var items: [SessionRow]? { sessions ?? data }
}

struct ChatMessage: Codable, Identifiable {
    var id: Int64? = nil
    var role: String = ""
    var content: String = ""
    var tool_name: String? = nil
    var name: String? = nil
    var displayId: String { "\(id ?? 0)-\(role)-\(content.hashValue)" }
}

struct SessionPayload: Codable {
    var session_id: String? = nil
    var title: String? = nil
    var messages: [ChatMessage]? = nil
    var session: SessionInner? = nil
    struct SessionInner: Codable {
        var session_id: String? = nil
        var title: String? = nil
        var messages: [ChatMessage]? = nil
    }
}

enum Panel: String, CaseIterable, Identifiable {
    case chat, tasks, kanban, spaces, skills, memory, logs, profiles, dashboard, settings
    var id: String { rawValue }
    var label: String {
        switch self {
        case .chat: return "Chat"
        case .tasks: return "Tasks"
        case .kanban: return "Kanban"
        case .spaces: return "Spaces"
        case .skills: return "Skills"
        case .memory: return "Memory"
        case .logs: return "Logs"
        case .profiles: return "Profiles"
        case .dashboard: return "Dashboard"
        case .settings: return "Settings"
        }
    }
}

struct ChatStart: Codable {
    var stream_id: String? = nil
    var session_id: String? = nil
    var title: String? = nil
}
