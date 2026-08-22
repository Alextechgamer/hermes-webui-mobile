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
    var pinned: Bool? = nil

    enum CodingKeys: String, CodingKey {
        case session_id, title, preview, message_count, messages, source, updated_at, model, pinned
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

struct ChatMessage: Codable, Identifiable, Hashable {
    var id: Int64? = nil
    var role: String = ""
    var content: String = ""
    var tool_name: String? = nil
    var name: String? = nil
    var displayId: String { "\(id ?? 0)-\(role)-\(content.hashValue)-\(tool_name ?? "")" }
}

struct SessionPayload: Codable {
    var session_id: String? = nil
    var title: String? = nil
    var model: String? = nil
    var messages: [ChatMessage]? = nil
    var todo_state: TodoState? = nil
    var _messages_truncated: Bool? = nil
    var active_stream_id: String? = nil
    var session: SessionInner? = nil
    struct SessionInner: Codable {
        var session_id: String? = nil
        var title: String? = nil
        var model: String? = nil
        var messages: [ChatMessage]? = nil
        var todo_state: TodoState? = nil
        var _messages_truncated: Bool? = nil
        var active_stream_id: String? = nil
    }
}

struct TodoState: Codable {
    var todos: [TodoItem]? = nil
}

struct TodoItem: Codable, Identifiable, Hashable {
    var id: String? = nil
    var content: String? = nil
    var text: String? = nil
    var title: String? = nil
    var status: String? = nil
    var displayId: String { id ?? UUID().uuidString }
    var label: String { content ?? text ?? title ?? "" }
    var state: String { status ?? "pending" }
}

struct CronJob: Identifiable {
    var id: String
    var name: String
    var schedule: String
    var enabled: Bool
    var paused: Bool
    var prompt: String
    var lastStatus: String
    var lastRun: String
    var owner: String
    var readOnly: Bool
}

struct KanbanColumn: Identifiable {
    var id: String { name }
    var name: String
    var tasks: [KanbanTask]
}

struct KanbanTask: Identifiable {
    var id: String
    var title: String
    var status: String
    var assignee: String
}

struct SkillRow: Identifiable {
    var id: String { name }
    var name: String
    var description: String
    var category: String
    var disabled: Bool
}

struct MemoryDoc {
    var memory: String = ""
    var user: String = ""
    var soul: String = ""
    var project: String = ""
    var projectName: String = ""
}

struct SpaceRow: Identifiable {
    var id: String { path.isEmpty ? name : path }
    var name: String
    var path: String
    var last: Bool
}

struct ProfileRow: Identifiable {
    var id: String { name }
    var name: String
    var model: String
    var active: Bool
}

struct InsightModel: Identifiable {
    var id: String { model }
    var model: String
    var sessions: Int
    var tokens: Int
    var cost: Double
}

struct Insights {
    var days: Int = 30
    var sessions: Int = 0
    var messages: Int = 0
    var tokens: Int = 0
    var cost: Double = 0
    var cacheHit: Double? = nil
    var models: [InsightModel] = []
}

struct DashCard: Identifiable {
    var id: String { title }
    var title: String
    var value: String
    var hint: String
}

struct Approval {
    var approvalId: String
    var tool: String
    var detail: String
}

struct Clarify {
    var clarifyId: String
    var question: String
    var choices: [String]
}

struct SettingItem: Identifiable {
    var id: String { key }
    var key: String
    var type: String
    var value: String
}

enum Panel: String, CaseIterable, Identifiable {
    case chat, tasks, kanban, skills, memory, spaces, profiles, todos, files, terminal, insights, logs, dashboard, settings
    var id: String { rawValue }
    var label: String {
        switch self {
        case .chat: return "Chat"
        case .tasks: return "Tasks"
        case .kanban: return "Kanban"
        case .skills: return "Skills"
        case .memory: return "Memory"
        case .spaces: return "Spaces"
        case .profiles: return "Profiles"
        case .todos: return "Todos"
        case .files: return "Files"
        case .terminal: return "Terminal"
        case .insights: return "Insights"
        case .logs: return "Logs"
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

struct SessionLoad {
    var title: String
    var model: String
    var messages: [ChatMessage]
    var todos: [TodoItem]
    var truncated: Bool
    var activeStreamId: String
}

struct FsEntry: Identifiable {
    var id: String { path }
    var name: String
    var path: String
    var isDir: Bool
    var size: Int
}

struct FileDoc {
    var path: String
    var content: String
    var lines: Int
}
