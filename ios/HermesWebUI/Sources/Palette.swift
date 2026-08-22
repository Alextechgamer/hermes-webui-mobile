import SwiftUI

/// Tokens from hermes-webui `style.css` :root.dark (navy + gold) — same as Android `Wui`.
enum Palette {
    static let bg = Color(hex: 0x0D0D1A)
    static let sidebar = Color(hex: 0x141425)
    static let surface = Color(hex: 0x1A1A2E)
    static let text = Color(hex: 0xFFF8DC)
    static let strong = Color(hex: 0xFFF8EB)
    static let muted = Color(hex: 0xB8B8C8)
    static let accent = Color(hex: 0xFFD700)
    static let accentHover = Color(hex: 0xFFE44D)
    static let accentText = Color(hex: 0xE4C28D)
    static let accentBg = Color(hex: 0xFFD700, alpha: 0.08)
    static let accentBgStrong = Color(hex: 0xFFD700, alpha: 0.14)
    static let border = Color(hex: 0x2A2A45)
    static let border2 = Color(hex: 0xFFD700, alpha: 0.18)
    static let inputBg = Color.white.opacity(0.04)
    static let danger = Color(hex: 0xFF6B6B)
    static let ok = Color(hex: 0x7DCEA0)
    static let userBubble = Color(hex: 0xFFD700, alpha: 0.14)
    static let userBubbleBorder = Color(hex: 0xFFD700, alpha: 0.22)
    static let codeBg = Color(hex: 0x11111F)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

struct WuiChip: View {
    let text: String
    var selected: Bool = false
    var action: (() -> Void)?

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .medium))
            .foregroundColor(selected ? Palette.accentText : Palette.muted)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(selected ? Palette.accentBg : Color.white.opacity(0.05))
            .overlay(Capsule().stroke(selected ? Palette.accentBgStrong : Palette.border2, lineWidth: 1))
            .clipShape(Capsule())
            .onTapGesture { action?() }
    }
}

struct RoleDot: View {
    let letter: String
    var user: Bool = false

    var body: some View {
        Text(letter)
            .font(.system(size: 9, weight: .bold))
            .foregroundColor(Palette.accentText)
            .padding(5)
            .background(user ? Palette.accentBg : Palette.accentBgStrong)
            .clipShape(Circle())
            .overlay(Circle().stroke(Palette.accentBgStrong, lineWidth: 1))
    }
}

extension Panel {
    var symbol: String {
        switch self {
        case .chat: return "bubble.left"
        case .tasks: return "calendar"
        case .kanban: return "rectangle.split.3x1"
        case .skills: return "square.3.layers.3d"
        case .memory: return "memorychip"
        case .spaces: return "folder"
        case .profiles: return "person"
        case .todos: return "checklist"
        case .insights: return "chart.bar"
        case .files: return "doc"
        case .terminal: return "terminal"
        case .logs: return "doc.text"
        case .dashboard: return "square.grid.2x2"
        case .settings: return "gearshape"
        }
    }
}
