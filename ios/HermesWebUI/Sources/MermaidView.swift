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
struct MEdge { var from: String; var to: String; var label: String = "" }
struct MGraph { var dir: String; var nodes: [MNode]; var edges: [MEdge] }
struct MSeq { var actors: [String]; var msgs: [(String, String, String)] }
struct MPie { var slices: [(String, Float)] }

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

func parseSeq(_ src: String) -> MSeq? {
    let lines = src.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    guard lines.contains(where: { $0.lowercased().hasPrefix("sequencediagram") }) else { return nil }
    var actors: [String] = []
    var msgs: [(String, String, String)] = []
    let re = try? NSRegularExpression(pattern: #"([\w][\w\s.-]*?)\s*(?:->>|-->>|-->|->)\s*([\w][\w\s.-]*?)\s*:\s*(.+)"#)
    for line in lines.dropFirst() {
        let ns = line as NSString
        guard let m = re?.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges >= 4 else { continue }
        let a = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
        let b = ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespaces)
        if !actors.contains(a) { actors.append(a) }
        if !actors.contains(b) { actors.append(b) }
        msgs.append((a, b, ns.substring(with: m.range(at: 3)).trimmingCharacters(in: .whitespaces)))
    }
    return actors.isEmpty ? nil : MSeq(actors: actors, msgs: msgs)
}

func parsePie(_ src: String) -> MPie? {
    let lines = src.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    guard lines.contains(where: { $0.lowercased().hasPrefix("pie") }) else { return nil }
    var slices: [(String, Float)] = []
    let re = try? NSRegularExpression(pattern: #""([^"]+)"\s*:\s*([0-9.]+)"#)
    for line in lines {
        let ns = line as NSString
        guard let m = re?.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges >= 3 else { continue }
        let name = ns.substring(with: m.range(at: 1))
        if let v = Float(ns.substring(with: m.range(at: 2))) { slices.append((name, v)) }
    }
    return slices.isEmpty ? nil : MPie(slices: slices)
}

struct MermaidBlock: View {
    let source: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("mermaid").font(.caption).foregroundColor(Palette.accent)
            if let g = parseFlow(source) {
                FlowCanvas(g: g).frame(height: CGFloat(80 * max(layer(g).count, 1) + 20))
            } else if let s = parseSeq(source) {
                SeqCanvas(s: s).frame(height: CGFloat(80 + 36 * s.msgs.count))
            } else if let p = parsePie(source) {
                PieCanvas(p: p).frame(height: 220)
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

struct SeqCanvas: View {
    let s: MSeq
    var body: some View {
        Canvas { ctx, size in
            let n = max(s.actors.count, 1)
            let gap = size.width / CGFloat(n)
            for (i, name) in s.actors.enumerated() {
                let x = (CGFloat(i) + 0.5) * gap
                var line = Path()
                line.move(to: CGPoint(x: x, y: 40))
                line.addLine(to: CGPoint(x: x, y: size.height - 8))
                ctx.stroke(line, with: .color(Palette.border), lineWidth: 2)
                ctx.draw(Text(name.prefix(16)).font(.caption2).foregroundColor(Palette.text), at: CGPoint(x: x, y: 20))
            }
            for (i, msg) in s.msgs.enumerated() {
                let y = 70 + CGFloat(i) * 36
                let i1 = s.actors.firstIndex(of: msg.0) ?? 0
                let i2 = s.actors.firstIndex(of: msg.1) ?? 0
                let x1 = (CGFloat(i1) + 0.5) * gap
                let x2 = (CGFloat(i2) + 0.5) * gap
                var p = Path()
                p.move(to: CGPoint(x: x1, y: y))
                p.addLine(to: CGPoint(x: x2, y: y))
                ctx.stroke(p, with: .color(Palette.accent), lineWidth: 2)
                ctx.draw(Text(msg.2.prefix(28)).font(.caption2).foregroundColor(Palette.text), at: CGPoint(x: (x1 + x2) / 2, y: y - 10))
            }
        }
    }
}

struct PieCanvas: View {
    let p: MPie
    var body: some View {
        let total = max(p.slices.reduce(0) { $0 + $1.1 }, 0.001)
        VStack(alignment: .leading, spacing: 6) {
            Canvas { ctx, size in
                let r = min(size.width, size.height) / 2 - 8
                var start = Angle.degrees(-90)
                for (i, slice) in p.slices.enumerated() {
                    let sweep = Angle.degrees(Double(360 * (slice.1 / total)))
                    var path = Path()
                    path.move(to: CGPoint(x: size.width / 2, y: size.height / 2))
                    path.addArc(center: CGPoint(x: size.width / 2, y: size.height / 2), radius: r, startAngle: start, endAngle: start + sweep, clockwise: false)
                    path.closeSubpath()
                    let hue = Double((i * 50) % 360) / 360
                    ctx.fill(path, with: .color(Color(hue: hue, saturation: 0.55, brightness: 0.9)))
                    start += sweep
                }
            }
            .frame(height: 160)
            ForEach(Array(p.slices.enumerated()), id: \.offset) { _, s in
                Text("\(s.0) · \(s.1)").font(.caption).foregroundColor(Palette.muted)
            }
        }
    }
}
