import Foundation

struct AuthStatus: Codable {
    var auth_enabled: Bool = false
    var logged_in: Bool = false
    var password_auth_enabled: Bool = false
}

struct SessionRow: Identifiable {
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

    var sid: String { session_id ?? raw_id ?? "" }
    var id: String { sid }
    var displayTitle: String {
        let t = (title ?? "").trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? "New conversation" : t
    }
    var msgCount: Int { messages ?? message_count ?? 0 }
}

struct SessionList {
    var sessions: [SessionRow]? = nil
    var data: [SessionRow]? = nil
    var items: [SessionRow]? { sessions ?? data }
}

struct ChatMessage: Identifiable, Hashable {
    var id: String
    var role: String = ""
    var content: String = ""
    var tool: String = ""
    var preview: String = ""
    var running: Bool = false
}

struct SessionPayload: Codable {
    var session_id: String? = nil
    var title: String? = nil
    var model: String? = nil
    var session: SessionInner? = nil
    struct SessionInner: Codable {
        var session_id: String? = nil
        var title: String? = nil
        var model: String? = nil
    }
}

struct TodoItem: Identifiable, Hashable {
    var id: String
    var content: String
    var status: String
    var displayId: String { id }
    var label: String { content }
    var state: String { status }
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

struct KanbanTask: Identifiable, Equatable {
    var id: String
    var title: String
    var status: String
    var assignee: String
    var priority: String
    var body: String = ""
    var tenant: String = ""
    var comments: Int = 0
}

struct KanbanBoardMeta: Identifiable {
    var id: String { slug }
    var slug: String
    var name: String
    var total: Int = 0
    var current: Bool = false
}

struct KanbanStats {
    var byStatus: [String: Int] = [:]
    func line() -> String {
        let order = ["triage", "todo", "ready", "running", "blocked", "done"]
        return order.compactMap { k in
            guard let n = byStatus[k], n > 0 else { return nil }
            return "\(n) \(k.capitalized)"
        }.joined(separator: "  ")
    }
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
    var count: Int = 0
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

    func section() -> SettingsSection {
        let k = key.lowercased()
        if ["theme", "skin", "font", "accent", "density", "rtl", "appearance"].contains(where: { k.contains($0) }) {
            return .appearance
        }
        if ["provider", "api_key", "openrouter", "openai", "anthropic"].contains(where: { k.contains($0) }) {
            return .providers
        }
        if ["password", "auth", "port", "update", "max_token", "check_for", "version"].contains(where: { k.contains($0) }) {
            return .system
        }
        if ["sidebar", "session", "tool", "think", "mermaid", "message_mode", "conversation", "transcript", "stream", "compact", "pin"].contains(where: { k.contains($0) }) {
            return .conversation
        }
        return .preferences
    }
}

enum Panel: String, CaseIterable, Identifiable {
    case chat, tasks, kanban, skills, memory, spaces, profiles, todos, insights, logs, settings, files, terminal, dashboard
    var id: String { rawValue }
    var inRail: Bool {
        switch self {
        case .files, .terminal: return false
        default: return true
        }
    }
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

enum SettingsSection: String, CaseIterable, Identifiable {
    case conversation, appearance, preferences, providers, plugins, extensions, system, help
    var id: String { rawValue }
    var label: String {
        switch self {
        case .conversation: return "Conversation"
        case .appearance: return "Appearance"
        case .preferences: return "Preferences"
        case .providers: return "Providers"
        case .plugins: return "Plugins"
        case .extensions: return "Extensions"
        case .system: return "System"
        case .help: return "Help"
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

struct PendingAttach: Identifiable, Hashable {
    var name: String
    var path: String
    var mime: String = ""
    var isImage: Bool = false
    var id: String { path }
}

struct ProviderRow: Identifiable {
    var id: String
    var displayName: String
    var hasKey: Bool
    var configurable: Bool
    var keySource: String
}

struct PluginRow: Identifiable {
    var id: String { name }
    var name: String
    var description: String
    var enabled: Bool = true
}

struct ExtensionRow: Identifiable {
    var id: String
    var name: String
    var enabled: Bool = false
    var description: String = ""
}

struct ModelOption: Identifiable, Hashable {
    var id: String
    var label: String
    var provider: String
}

struct ReasoningStatus {
    var effort: String = ""
    var supported: [String] = []
    var showToggle: Bool = true

    var label: String { ReasoningStatus.label(for: effort) }

    func options() -> [(String, String)] {
        let ladder: [(String, String)] = [
            ("", "Default"),
            ("none", "None"),
            ("minimal", "Minimal"),
            ("low", "Low"),
            ("medium", "Medium"),
            ("high", "High"),
            ("xhigh", "Extra High"),
            ("max", "Max"),
        ]
        if supported.isEmpty { return ladder }
        let allowed = Set(["", "none"] + supported)
        return ladder.filter { allowed.contains($0.0) }
    }

    static func label(for effort: String) -> String {
        switch effort.lowercased() {
        case "", "default": return "Default"
        case "none": return "None"
        case "minimal": return "Minimal"
        case "low": return "Low"
        case "medium": return "Medium"
        case "high": return "High"
        case "xhigh": return "Extra High"
        case "max": return "Max"
        default: return effort.capitalized
        }
    }
}

struct SavedPrompt: Identifiable {
    var id: String
    var label: String
    var text: String
}
