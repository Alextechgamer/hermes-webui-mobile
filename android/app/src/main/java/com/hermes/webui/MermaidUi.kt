package com.hermes.webui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

sealed class ChatSeg {
    data class Text(val value: String) : ChatSeg()
    data class Mermaid(val source: String) : ChatSeg()
}

fun splitChat(content: String): List<ChatSeg> {
    val fence = Regex("```(?:mermaid)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    val out = mutableListOf<ChatSeg>()
    var last = 0
    for (m in fence.findAll(content)) {
        val body = m.groupValues[1].trim()
        if (last < m.range.first) out += ChatSeg.Text(content.substring(last, m.range.first))
        val looks = body.startsWith("graph", true) ||
            body.startsWith("flowchart", true) ||
            body.startsWith("sequenceDiagram", true) ||
            body.startsWith("pie", true) ||
            m.value.startsWith("```mermaid", true)
        if (looks && body.isNotBlank()) out += ChatSeg.Mermaid(body) else out += ChatSeg.Text(m.value)
        last = m.range.last + 1
    }
    if (last < content.length) out += ChatSeg.Text(content.substring(last))
    return out.ifEmpty { listOf(ChatSeg.Text(content)) }
}

data class MNode(val id: String, val label: String)
data class MEdge(val from: String, val to: String, val label: String = "")
data class MGraph(val dir: String, val nodes: List<MNode>, val edges: List<MEdge>)
data class MSeq(val actors: List<String>, val msgs: List<Triple<String, String, String>>)
data class MPie(val slices: List<Pair<String, Float>>)

fun parseFlow(src: String): MGraph? {
    val lines = src.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%%") }
    if (lines.isEmpty()) return null
    val head = Regex("""^(?:graph|flowchart)\s+(TD|TB|BT|RL|LR)\b""", RegexOption.IGNORE_CASE)
    val first = lines.first()
    if (head.find(first) == null && !first.startsWith("graph", true) && !first.startsWith("flowchart", true)) return null
    val dir = head.find(first)?.groupValues?.get(1)?.uppercase() ?: "TD"
    val nodes = linkedMapOf<String, String>()
    val edges = mutableListOf<MEdge>()
    val nodeBit = """([A-Za-z][\w-]*)(?:\[([^\]]+)\]|\(([^\)]+)\)|\{([^}]+)\}|"([^"]+)")?"""
    val edgeRe = Regex("""$nodeBit\s*(?:-->|---|==>|-.->)\s*(?:\|([^|]+)\|)?\s*$nodeBit""")
    val nodeRe = Regex("""^$nodeBit$""")
    fun add(id: String, a: String?, b: String?, c: String?, d: String?) {
        val label = listOfNotNull(a, b, c, d).firstOrNull()?.ifBlank { null } ?: nodes[id] ?: id
        nodes[id] = label
    }
    for (line in lines.drop(1)) {
        val e = edgeRe.find(line)
        if (e != null) {
            val g = e.groupValues
            add(g[1], g[2], g[3], g[4], g[5])
            add(g[7], g[8], g[9], g[10], g[11])
            edges += MEdge(g[1], g[7], g[6])
            continue
        }
        val n = nodeRe.find(line)
        if (n != null) add(n.groupValues[1], n.groupValues[2], n.groupValues[3], n.groupValues[4], n.groupValues[5])
    }
    if (nodes.isEmpty()) return null
    return MGraph(dir, nodes.map { MNode(it.key, it.value) }, edges)
}

fun parseSeq(src: String): MSeq? {
    val lines = src.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.none { it.startsWith("sequenceDiagram", true) }) return null
    val actors = linkedSetOf<String>()
    val msgs = mutableListOf<Triple<String, String, String>>()
    val re = Regex("""([\w][\w\s.-]*?)\s*(?:->>|-->>|-->|->)\s*([\w][\w\s.-]*?)\s*:\s*(.+)""")
    for (line in lines.drop(1)) {
        val m = re.find(line) ?: continue
        val a = m.groupValues[1].trim()
        val b = m.groupValues[2].trim()
        actors += a; actors += b
        msgs += Triple(a, b, m.groupValues[3].trim())
    }
    if (actors.isEmpty()) return null
    return MSeq(actors.toList(), msgs)
}

fun parsePie(src: String): MPie? {
    val lines = src.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.none { it.startsWith("pie", true) }) return null
    val slices = mutableListOf<Pair<String, Float>>()
    val re = Regex(""""([^"]+)"\s*:\s*([0-9.]+)""")
    for (line in lines) {
        val m = re.find(line) ?: continue
        slices += m.groupValues[1] to (m.groupValues[2].toFloatOrNull() ?: continue)
    }
    return if (slices.isEmpty()) null else MPie(slices)
}

@Composable
fun MermaidBlock(source: String, darkText: Boolean) {
    val flow = parseFlow(source)
    val seq = if (flow == null) parseSeq(source) else null
    val pie = if (flow == null && seq == null) parsePie(source) else null
    Column(
        Modifier.fillMaxWidth().background(if (darkText) Color(0x22000000) else Wui.Bg, RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        Text("mermaid", color = Wui.Accent, fontSize = 11.sp)
        when {
            flow != null -> FlowCanvas(flow)
            seq != null -> SeqCanvas(seq)
            pie != null -> PieCanvas(pie)
            else -> {}
        }
        Text(
            source,
            color = if (darkText) Color(0xFF1A1A2E) else Wui.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).height(120.dp),
        )
    }
}

@Composable
private fun FlowCanvas(g: MGraph) {
    val vertical = g.dir == "TD" || g.dir == "TB" || g.dir == "BT"
    val layers = layer(g)
    val cols = if (vertical) layers.maxOf { it.size } else layers.size
    val rows = if (vertical) layers.size else layers.maxOf { it.size }
    val h = (80 * rows + 40).dp
    Canvas(Modifier.fillMaxWidth().height(h).padding(4.dp)) {
        val cw = size.width / max(cols, 1)
        val rh = size.height / max(rows, 1)
        val boxW = min(cw * 0.8f, 160f)
        val boxH = min(rh * 0.55f, 48f)
        val pos = mutableMapOf<String, Offset>()
        layers.forEachIndexed { li, layer ->
            layer.forEachIndexed { ni, node ->
                val cx = if (vertical) (ni + 0.5f) * (size.width / max(layer.size, 1)) else (li + 0.5f) * cw
                val cy = if (vertical) (li + 0.5f) * rh else (ni + 0.5f) * (size.height / max(layer.size, 1))
                pos[node.id] = Offset(cx, cy)
                drawRoundRect(
                    color = Wui.Surface,
                    topLeft = Offset(cx - boxW / 2, cy - boxH / 2),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                )
                drawRoundRect(
                    color = Wui.Accent,
                    topLeft = Offset(cx - boxW / 2, cy - boxH / 2),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    style = Stroke(width = 2f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    node.label.take(22),
                    cx,
                    cy + 6f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#FFF8DC")
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 26f
                        isAntiAlias = true
                    },
                )
            }
        }
        for (e in g.edges) {
            val a = pos[e.from] ?: continue
            val b = pos[e.to] ?: continue
            drawLine(Wui.Accent, a, b, strokeWidth = 3f)
            val tip = Offset(b.x, b.y)
            val ang = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val p = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo((tip.x - 12 * cos(ang - 0.4)).toFloat(), (tip.y - 12 * sin(ang - 0.4)).toFloat())
                lineTo((tip.x - 12 * cos(ang + 0.4)).toFloat(), (tip.y - 12 * sin(ang + 0.4)).toFloat())
                close()
            }
            drawPath(p, Wui.Accent)
        }
    }
}

private fun layer(g: MGraph): List<List<MNode>> {
    val incoming = g.nodes.associate { it.id to 0 }.toMutableMap()
    for (e in g.edges) incoming[e.to] = (incoming[e.to] ?: 0) + 1
    val byId = g.nodes.associateBy { it.id }
    val remaining = g.nodes.map { it.id }.toMutableSet()
    val out = mutableListOf<List<MNode>>()
    var frontier = remaining.filter { (incoming[it] ?: 0) == 0 }.ifEmpty { remaining.take(1) }
    while (frontier.isNotEmpty()) {
        out += frontier.mapNotNull { byId[it] }
        remaining.removeAll(frontier.toSet())
        val next = mutableListOf<String>()
        for (id in frontier) {
            for (e in g.edges) if (e.from == id && e.to in remaining) next += e.to
        }
        frontier = next.distinct().ifEmpty { remaining.take(1) }
        if (out.size > 12) break
    }
    if (remaining.isNotEmpty()) out += remaining.mapNotNull { byId[it] }
    return out
}

@Composable
private fun SeqCanvas(s: MSeq) {
    val h = (80 + 36 * s.msgs.size).dp
    Canvas(Modifier.fillMaxWidth().height(h)) {
        val n = max(s.actors.size, 1)
        val gap = size.width / n
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FFF8DC")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 26f
            isAntiAlias = true
        }
        s.actors.forEachIndexed { i, name ->
            val x = (i + 0.5f) * gap
            drawLine(Wui.Border, Offset(x, 40f), Offset(x, size.height - 8f), 2f)
            drawContext.canvas.nativeCanvas.drawText(name.take(16), x, 28f, paint)
        }
        s.msgs.forEachIndexed { i, (a, b, msg) ->
            val y = 70f + i * 36f
            val x1 = (s.actors.indexOf(a) + 0.5f) * gap
            val x2 = (s.actors.indexOf(b) + 0.5f) * gap
            drawLine(Wui.Accent, Offset(x1, y), Offset(x2, y), 3f)
            drawContext.canvas.nativeCanvas.drawText(msg.take(28), (x1 + x2) / 2, y - 8f, paint)
        }
    }
}

@Composable
private fun PieCanvas(p: MPie) {
    val total = p.slices.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.001f)
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val r = min(size.minDimension / 2 - 8f, 90f)
        val c = Offset(size.width / 2, size.height / 2)
        var start = -90f
        p.slices.forEachIndexed { i, (_, v) ->
            val sweep = 360f * (v / total)
            val hue = (i * 50f) % 360f
            drawArc(
                Color.hsv(hue, 0.55f, 0.9f),
                start,
                sweep,
                true,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
            )
            start += sweep
        }
    }
    p.slices.forEach { (name, v) ->
        Text("$name · $v", color = Wui.Muted, fontSize = 12.sp)
    }
}
