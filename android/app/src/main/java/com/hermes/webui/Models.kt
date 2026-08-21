package com.hermes.webui

enum class Panel(val label: String) {
    Chat("Chat"),
    Tasks("Tasks"),
    Kanban("Kanban"),
    Skills("Skills"),
    Memory("Memory"),
    Spaces("Spaces"),
    Profiles("Profiles"),
    Todos("Todos"),
    Insights("Insights"),
    Logs("Logs"),
    Dashboard("Dashboard"),
    Settings("Settings"),
}

data class AuthStatus(
    val authEnabled: Boolean = false,
    val loggedIn: Boolean = false,
)

data class SessionRow(
    val sid: String,
    val title: String,
    val preview: String = "",
    val msgCount: Int = 0,
    val source: String = "",
    val model: String = "",
    val pinned: Boolean = false,
) {
    val displayTitle: String get() = title.ifBlank { "New conversation" }
}

data class ChatMsg(
    val id: String,
    val role: String,
    val content: String,
    val tool: String = "",
)

data class CronJob(
    val id: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean,
    val paused: Boolean,
    val prompt: String,
    val lastStatus: String,
    val lastRun: String,
    val owner: String,
    val readOnly: Boolean,
)

data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask>,
)

data class KanbanTask(
    val id: String,
    val title: String,
    val status: String,
    val assignee: String = "",
    val priority: String = "",
)

data class SkillRow(
    val name: String,
    val description: String,
    val category: String,
    val disabled: Boolean,
)

data class MemoryDoc(
    val memory: String = "",
    val user: String = "",
    val soul: String = "",
    val project: String = "",
    val projectName: String = "",
)

data class SpaceRow(
    val name: String,
    val path: String,
    val last: Boolean = false,
)

data class ProfileRow(
    val name: String,
    val model: String = "",
    val active: Boolean = false,
)

data class TodoItem(
    val id: String,
    val content: String,
    val status: String,
)

data class InsightModel(
    val model: String,
    val sessions: Int,
    val tokens: Int,
    val cost: Double,
)

data class Insights(
    val days: Int = 30,
    val sessions: Int = 0,
    val messages: Int = 0,
    val tokens: Int = 0,
    val cost: Double = 0.0,
    val cacheHit: Double? = null,
    val models: List<InsightModel> = emptyList(),
)

data class DashCard(val title: String, val value: String, val hint: String = "")

data class Approval(
    val approvalId: String,
    val tool: String,
    val detail: String,
    val count: Int,
)

data class Clarify(
    val clarifyId: String,
    val question: String,
    val choices: List<String>,
)

data class SettingItem(
    val key: String,
    val type: String,
    val value: String,
)

data class SessionLoad(
    val title: String,
    val model: String,
    val messages: List<ChatMsg>,
    val todos: List<TodoItem>,
    val truncated: Boolean,
    val activeStreamId: String,
)
