package com.hermes.webui

enum class Panel(val label: String, val inRail: Boolean = true) {
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
    Settings("Settings"),
    Files("Files", inRail = false),
    Terminal("Terminal", inRail = false),
    Console("Console", inRail = false),
}

enum class SettingsSection(val label: String) {
    Conversation("Conversation"),
    Appearance("Appearance"),
    Preferences("Preferences"),
    Providers("Providers"),
    Plugins("Plugins"),
    Extensions("Extensions"),
    System("System"),
    Help("Help"),
}

data class PendingAttach(
    val name: String,
    val path: String,
    val mime: String = "",
    val isImage: Boolean = false,
)

data class ProviderRow(
    val id: String,
    val displayName: String,
    val hasKey: Boolean,
    val configurable: Boolean,
    val keySource: String,
)

data class PluginRow(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

data class ExtensionRow(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val description: String = "",
)

data class AuthStatus(
    val authEnabled: Boolean = false,
    val loggedIn: Boolean = false,
)

data class ModelOption(
    val id: String,
    val label: String,
    val provider: String,
)

data class ReasoningStatus(
    val effort: String = "",
    val supported: List<String> = emptyList(),
    val showToggle: Boolean = true,
) {
    fun label(): String = reasoningLabel(effort)
    fun options(): List<Pair<String, String>> {
        val ladder = listOf(
            "" to "Default",
            "none" to "None",
            "minimal" to "Minimal",
            "low" to "Low",
            "medium" to "Medium",
            "high" to "High",
            "xhigh" to "Extra High",
            "max" to "Max",
        )
        val allowed = if (supported.isEmpty()) {
            ladder.map { it.first }.toSet()
        } else {
            setOf("", "none") + supported
        }
        return ladder.filter { it.first in allowed }
    }
}

fun reasoningLabel(effort: String): String = when (effort.lowercase()) {
    "", "default" -> "Default"
    "none" -> "None"
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Extra High"
    "max" -> "Max"
    else -> effort.replaceFirstChar { it.uppercase() }
}

data class SessionRow(
    val sid: String,
    val title: String,
    val preview: String = "",
    val msgCount: Int = 0,
    val source: String = "",
    val model: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val projectId: String = "",
) {
    val displayTitle: String get() = title.ifBlank { "New conversation" }
}

data class ProjectRow(
    val id: String,
    val name: String,
    val color: String = "",
)

data class ChatMsg(
    val id: String,
    val role: String,
    val content: String,
    val tool: String = "",
    val preview: String = "",
    val running: Boolean = false,
)

data class SavedPrompt(
    val id: String,
    val label: String,
    val text: String,
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
    val nextRun: String = "",
    val owner: String,
    val readOnly: Boolean,
    val deliver: String = "local",
)

data class CronRun(
    val filename: String,
    val size: Int = 0,
    val modified: String = "",
)

data class McpServer(
    val name: String,
    val enabled: Boolean = true,
    val description: String = "",
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
    val body: String = "",
    val tenant: String = "",
    val comments: Int = 0,
)

data class KanbanBoardMeta(
    val slug: String,
    val name: String,
    val total: Int = 0,
    val current: Boolean = false,
)

data class KanbanStats(
    val byStatus: Map<String, Int> = emptyMap(),
    val byAssignee: Map<String, Int> = emptyMap(),
) {
    fun line(): String {
        val order = listOf("triage", "todo", "ready", "running", "blocked", "done")
        val bits = order.mapNotNull { k -> byStatus[k]?.takeIf { it > 0 }?.let { "${it} ${k.replaceFirstChar { c -> c.uppercase() }}" } }
        val running = byStatus["running"] ?: 0
        return (bits + if (running > 0) listOf("$running Running") else emptyList()).distinct().joinToString("  ")
    }
}

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
    val cacheHitPct: Double? = null,
    val costShare: Double? = null,
)

data class SkillUsage(
    val name: String,
    val uses: Int,
    val views: Int,
    val patches: Int,
)

data class SessionsResult(
    val rows: List<SessionRow>,
    val webuiCount: Int = 0,
    val cliCount: Int = 0,
)

data class Insights(
    val days: Int = 30,
    val sessions: Int = 0,
    val messages: Int = 0,
    val tokens: Int = 0,
    val cost: Double = 0.0,
    val cacheHit: Double? = null,
    val models: List<InsightModel> = emptyList(),
    val skills: List<SkillUsage> = emptyList(),
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
) {
    val label: String get() = key.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { c -> c.uppercase() }
    }
}

data class SessionLoad(
    val title: String,
    val model: String,
    val messages: List<ChatMsg>,
    val todos: List<TodoItem>,
    val truncated: Boolean,
    val activeStreamId: String,
)

data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
)

data class FileDoc(
    val path: String,
    val content: String,
    val size: Int = 0,
    val lines: Int = 0,
)

data class SlashCommand(
    val name: String,
    val description: String,
    val category: String = "",
    val argsHint: String = "",
    val cliOnly: Boolean = false,
)

data class UpdateTarget(
    val name: String,
    val behind: Int = 0,
    val current: String = "",
    val latest: String = "",
    val dirty: Boolean = false,
)

data class UpdatesStatus(
    val webui: UpdateTarget = UpdateTarget("webui"),
    val agent: UpdateTarget = UpdateTarget("agent"),
    val checkedAt: String = "",
)
