import SwiftUI

enum ChatSeg {
    case text(String)
    case mermaid(String)
}

func splitChat(_ content: String) -> [ChatSeg] {
    let pattern = "```(?:mermaid)?\\s*\\n([\\s\\S]*?)```"
    guard let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else {
        return [.text(content)]
    }
    let ns = content as NSString
    var out: [ChatSeg] = []
    var last = 0
    for m in re.matches(in: content, range: NSRange(location: 0, length: ns.length)) {
        if m.range.location > last {
            out.append(.text(ns.substring(with: NSRange(location: last, length: m.range.location - last))))
        }
        let body = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
        let raw = ns.substring(with: m.range)
        let looks = body.lowercased().hasPrefix("graph") || body.lowercased().hasPrefix("flowchart")
            || body.lowercased().hasPrefix("sequencediagram") || body.lowercased().hasPrefix("pie")
            || raw.lowercased().hasPrefix("```mermaid")
        out.append(looks && !body.isEmpty ? .mermaid(body) : .text(raw))
        last = m.range.location + m.range.length
    }
    if last < ns.length { out.append(.text(ns.substring(from: last))) }
    return out.isEmpty ? [.text(content)] : out
}

struct MNode { var id: String; var label: String }
struct MEdge { var from: String; var to: String }
struct MGraph { var dir: String; var nodes: [MNode]; var edges: [MEdge] }

func parseFlow(_ src: String) -> MGraph? {
    let lines = src.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && !$0.hasPrefix("%%") }
    guard let first = lines.first else { return nil }
    let low = first.lowercased()
    guard low.hasPrefix("graph") || low.hasPrefix("flowchart") else { return nil }
    var dir = "TD"
    for d in ["TD", "TB", "BT", "RL", "LR"] where first.uppercased().contains(d) { dir = d }
    var labels: [String: String] = [:]
    var edges: [MEdge] = []
    let node = #"([A-Za-z][\w-]*)(?:\[([^\]]+)\]|\(([^\)]+)\)|\{([^}]+)\})?"#
    let edgeRe = try? NSRegularExpression(pattern: node + #"\s*(?:-->|---|==>)\s*"# + node)
    for line in lines.dropFirst() {
        let ns = line as NSString
        if let m = edgeRe?.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges >= 7 {
            let a = ns.substring(with: m.range(at: 1))
            let b = ns.substring(with: m.range(at: 5))
            func lab(_ i: Int) -> String? {
                let r = m.range(at: i)
                return r.location == NSNotFound ? nil : ns.substring(with: r)
            }
            labels[a] = lab(2) ?? lab(3) ?? lab(4) ?? labels[a] ?? a
            labels[b] = lab(6) ?? lab(7) ?? lab(8) ?? labels[b] ?? b
            edges.append(MEdge(from: a, to: b))
        }
    }
    if labels.isEmpty { return nil }
    return MGraph(dir: dir, nodes: labels.map { MNode(id: $0.key, label: $0.value) }, edges: edges)
}

struct MermaidBlock: View {
    let source: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("mermaid").font(.caption).foregroundColor(Palette.accent)
            if let g = parseFlow(source) {
                FlowCanvas(g: g).frame(height: CGFloat(80 * max(layer(g).count, 1) + 20))
            }
            ScrollView(.horizontal) {
                Text(source).font(.system(.footnote, design: .monospaced)).foregroundColor(Palette.muted)
            }.frame(maxHeight: 120)
        }
        .padding(8)
        .background(Palette.bg)
        .cornerRadius(8)
    }
}

private func layer(_ g: MGraph) -> [[MNode]] {
    var incoming: [String: Int] = Dictionary(uniqueKeysWithValues: g.nodes.map { ($0.id, 0) })
    for e in g.edges { incoming[e.to, default: 0] += 1 }
    let byId = Dictionary(uniqueKeysWithValues: g.nodes.map { ($0.id, $0) })
    var remaining = Set(g.nodes.map(\.id))
    var out: [[MNode]] = []
    var frontier = remaining.filter { incoming[$0, default: 0] == 0 }
    if frontier.isEmpty { frontier = remaining }
    while !frontier.isEmpty {
        out.append(frontier.compactMap { byId[$0] })
        remaining.subtract(frontier)
        var next = Set<String>()
        for id in frontier {
            for e in g.edges where e.from == id && remaining.contains(e.to) { next.insert(e.to) }
        }
        frontier = next.isEmpty ? remaining : next
        if out.count > 12 { break }
    }
    return out
}

struct FlowCanvas: View {
    let g: MGraph
    var body: some View {
        let layers = layer(g)
        Canvas { ctx, size in
            let vertical = g.dir == "TD" || g.dir == "TB" || g.dir == "BT"
            var pos: [String: CGPoint] = [:]
            for (li, layer) in layers.enumerated() {
                for (ni, node) in layer.enumerated() {
                    let cx = vertical ? CGFloat(ni + 1) * size.width / CGFloat(max(layer.count + 1, 2)) : CGFloat(li + 1) * size.width / CGFloat(max(layers.count + 1, 2))
                    let cy = vertical ? CGFloat(li + 1) * size.height / CGFloat(max(layers.count + 1, 2)) : CGFloat(ni + 1) * size.height / CGFloat(max(layer.count + 1, 2))
                    pos[node.id] = CGPoint(x: cx, y: cy)
                    let rect = CGRect(x: cx - 50, y: cy - 16, width: 100, height: 32)
                    ctx.fill(Path(roundedRect: rect, cornerRadius: 6), with: .color(Palette.surface))
                    ctx.stroke(Path(roundedRect: rect, cornerRadius: 6), with: .color(Palette.accent), lineWidth: 1.5)
                    ctx.draw(Text(node.label.prefix(18)).font(.caption2).foregroundColor(Palette.text), at: CGPoint(x: cx, y: cy))
                }
            }
            for e in g.edges {
                guard let a = pos[e.from], let b = pos[e.to] else { continue }
                var p = Path()
                p.move(to: a); p.addLine(to: b)
                ctx.stroke(p, with: .color(Palette.accent), lineWidth: 2)
            }
        }
    }
}
