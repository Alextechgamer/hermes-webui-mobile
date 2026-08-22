import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit

struct ChatPane: View {
    @ObservedObject var store: AppStore
    @Binding var draft: String
    @State private var showPrompts = false
    @State private var showModels = false
    @State private var showProfiles = false
    @State private var showReasoning = false
    @State private var modelQuery = ""
    @State private var pickingFiles = false
    @State private var pickingPhotos = false
    @State private var photoItems: [PhotosPickerItem] = []
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if let a = store.approval {
                ApprovalBanner(a: a) { choice in Task { await store.approve(choice) } }
            }
            if let q = store.clarify {
                ClarifyBanner(q: q) { reply in Task { await store.answerClarify(reply) } }
            }
            ScrollViewReader { proxy in
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 4) {
                        if store.truncated {
                            Button("Load full history") { Task { await store.loadFullHistory() } }
                                .font(.system(size: 12))
                                .foregroundColor(Palette.muted)
                                .padding(.top, 12)
                        }
                        if store.messages.isEmpty && store.liveText.isEmpty {
                            VStack(spacing: 8) {
                                Text("What can I help with?")
                                    .font(.system(size: 20, weight: .semibold))
                                    .tracking(-0.3)
                                    .foregroundColor(Palette.text)
                                if !store.sessions.isEmpty {
                                    Text("Recent conversations")
                                        .font(.system(size: 12))
                                        .foregroundColor(Palette.muted)
                                        .padding(.top, 16)
                                    ForEach(store.sessions.prefix(12)) { row in
                                        Button {
                                            Task { await store.open(row) }
                                        } label: {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(row.displayTitle).foregroundColor(Palette.text)
                                                Text("\(row.msgCount) messages").font(.caption).foregroundColor(Palette.muted)
                                            }
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .padding(10)
                                            .background(Palette.surface)
                                            .cornerRadius(10)
                                        }
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.top, 72)
                            .padding(.bottom, 24)
                        }
                        ForEach(store.messages) { m in
                            row(m).id(m.id)
                        }
                        if !store.liveText.isEmpty {
                            row(ChatMessage(id: "live", role: "assistant", content: store.liveText), live: true).id("live")
                        }
                        Color.clear.frame(height: 12).id("bottom")
                    }
                    .padding(.horizontal, 16)
                    }
                    .onAppear { jumpToLatest(proxy) }
                    .onChange(of: store.liveText) { _ in jumpToLatest(proxy) }
                    .onChange(of: store.messages.count) { _ in jumpToLatest(proxy) }
                    .onChange(of: store.messages.last?.id) { _ in jumpToLatest(proxy) }
                    if !store.messages.isEmpty {
                        Button { jumpToLatest(proxy) } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(Palette.accent)
                                .frame(width: 40, height: 40)
                                .background(Palette.surface)
                                .overlay(Circle().stroke(Palette.accent, lineWidth: 1))
                                .clipShape(Circle())
                        }
                        .padding(12)
                    }
                }
            }
            composer
        }
        .background(Palette.bg)
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result {
                Task { await store.attachFiles(urls) }
            }
        }
        .photosPicker(isPresented: $pickingPhotos, selection: $photoItems, matching: .any(of: [.images, .videos]))
        .onChange(of: photoItems) { items in
            guard !items.isEmpty else { return }
            Task {
                for (i, item) in items.enumerated() {
                    let ut = item.supportedContentTypes.first
                    let mime = ut?.preferredMIMEType ?? "image/jpeg"
                    let ext = ut?.preferredFilenameExtension ?? "jpg"
                    if let data = try? await item.loadTransferable(type: Data.self), !data.isEmpty {
                        await store.attachData(data, name: "photo-\(i).\(ext)", mime: mime)
                    }
                }
                await MainActor.run { photoItems = [] }
            }
        }
    }

    private func jumpToLatest(_ proxy: ScrollViewProxy) {
        let target = store.liveText.isEmpty ? (store.messages.last?.id ?? "bottom") : "bottom"
        DispatchQueue.main.async {
            withAnimation(.none) { proxy.scrollTo("bottom", anchor: .bottom) }
            proxy.scrollTo(target, anchor: .bottom)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
            proxy.scrollTo("bottom", anchor: .bottom)
        }
    }

    @ViewBuilder
    private func row(_ m: ChatMessage, live: Bool = false) -> some View {
        switch m.role {
        case "user": UserBubble(text: m.content)
        case "steer": SteerCard(text: m.content)
        case "thinking": ThinkingCard(m: m)
        case "tool": ToolCard(m: m)
        case "system":
            Text(m.content).font(.system(size: 12)).foregroundColor(Palette.muted).padding(.vertical, 8)
        default:
            AssistantBlock(store: store, m: m, live: live)
        }
    }

    private var composer: some View {
        let workspace = store.spaces.first(where: { $0.last })?.name
            ?? (store.fsRoot as NSString).lastPathComponent
        let workspaceLabel = workspace.isEmpty ? "Home" : workspace
        let profile = store.activeProfile.isEmpty ? "default" : store.activeProfile
        let model = store.selectedModel.isEmpty ? (store.models.first?.id ?? "") : store.selectedModel
        let canSend = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !store.pendingAttach.isEmpty

        return VStack(alignment: .leading, spacing: 6) {
            if store.listening {
                Text("Listening — tap mic to stop").font(.system(size: 11)).foregroundColor(Palette.accent).padding(.horizontal, 12)
            }
            if store.transcribing {
                Text("Transcribing…").font(.system(size: 11)).foregroundColor(Palette.accent).padding(.horizontal, 12)
            }
            if showPrompts && !store.prompts.isEmpty {
                ChipMenu(items: store.prompts.map { ($0.label, $0.text) }) { text in
                    draft = draft.isEmpty ? text : draft + "\n" + text
                    showPrompts = false
                }
            }
            if showProfiles && !store.profiles.isEmpty {
                ChipMenu(items: store.profiles.map { ($0.name, $0.name) }) { name in
                    Task { await store.switchProfile(name) }
                    showProfiles = false
                }
            }
            if showModels && !store.models.isEmpty {
                ModelPicker(options: store.models, selected: store.selectedModel, query: $modelQuery) { m in
                    store.pickModel(m)
                    showModels = false
                }
            }
            if showReasoning {
                ChipMenu(items: store.reasoning.options().map { ($0.1, $0.0) }) { effort in
                    Task { await store.setReasoning(effort) }
                    showReasoning = false
                }
            }
            if !store.pendingAttach.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(store.pendingAttach) { a in
                            HStack(spacing: 6) {
                                Text(a.name).font(.system(size: 12)).foregroundColor(Palette.text).lineLimit(1)
                                Button { store.dropAttach(a) } label: {
                                    Image(systemName: "xmark").font(.system(size: 10)).foregroundColor(Palette.muted)
                                }
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(Palette.surface)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border, lineWidth: 1))
                            .cornerRadius(8)
                        }
                    }
                }
                .padding(.horizontal, 12)
            }
            VStack(alignment: .leading, spacing: 0) {
                TextField("Message Hermes…", text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .focused($focused)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 4)
                    .foregroundColor(Palette.text)
                    .font(.system(size: 16))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        Menu {
                            Button("Photos") { pickingPhotos = true }
                            Button("Files") { pickingFiles = true }
                        } label: {
                            Image(systemName: "paperclip").font(.system(size: 16)).foregroundColor(Palette.muted).frame(width: 32, height: 32)
                        }

                        Button { showPrompts.toggle(); showModels = false; showProfiles = false } label: {
                            Image(systemName: "bookmark").font(.system(size: 16)).foregroundColor(Palette.muted).frame(width: 32, height: 32)
                        }
                        Button {
                            if store.listening {
                                Task {
                                    let t = await store.stopListen()
                                    if !t.isEmpty { draft = draft.isEmpty ? t : draft + " " + t }
                                }
                            } else {
                                store.startListen()
                            }
                        } label: {
                            Image(systemName: store.listening ? "stop.circle" : "mic")
                                .font(.system(size: 16))
                                .foregroundColor(store.listening ? Palette.danger : Palette.muted)
                                .frame(width: 32, height: 32)
                        }
                        Rectangle().fill(Palette.border).frame(width: 1, height: 16)
                        FooterChip(symbol: "person", label: profile) {
                            showProfiles.toggle(); showPrompts = false; showModels = false
                        }
                        FooterChip(symbol: "folder", label: workspaceLabel) {
                            Task { await store.go(.files) }
                        }
                        if !model.isEmpty {
                            FooterChip(symbol: "cpu", label: String(model.prefix(22))) {
                                showModels.toggle(); showPrompts = false; showProfiles = false; showReasoning = false
                            }
                        }
                        FooterChip(symbol: "lightbulb", label: "Reason \(store.reasoning.label)") {
                            showReasoning.toggle(); showModels = false; showPrompts = false; showProfiles = false
                        }
                        Spacer(minLength: 8)
                        if store.busy {
                            Text("STEER")
                                .font(.system(size: 10, weight: .bold))
                                .tracking(0.8)
                                .foregroundColor(Palette.accent)
                        }
                        Button {
                            let t = draft
                            draft = ""
                            focused = false
                            Task { await store.send(t) }
                        } label: {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(Palette.bg)
                                .frame(width: 34, height: 34)
                                .background(canSend ? Palette.accent : Palette.border)
                                .clipShape(Circle())
                        }
                        .disabled(!canSend)
                    }
                    .padding(.horizontal, 8)
                    .padding(.top, 4)
                    .padding(.bottom, 8)
                }
            }
            .background(Palette.surface)
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(focused ? Palette.accent : Palette.border2, lineWidth: 1))
            .cornerRadius(16)
            .padding(.horizontal, 12)
            .padding(.top, 6)
            .padding(.bottom, 12)
        }
        .background(Palette.bg)
    }
}

struct SteerCard: View {
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("STEER")
                .font(.system(size: 10, weight: .bold))
                .tracking(1)
                .foregroundColor(Palette.accent)
            Text(text).font(.system(size: 13)).foregroundColor(Palette.text)
            Text("Hermes will pick this up at the next tool.")
                .font(.system(size: 11))
                .foregroundColor(Palette.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Palette.accent.opacity(0.12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.accent.opacity(0.35), lineWidth: 1))
        .cornerRadius(12)
        .padding(.vertical, 6)
    }
}

struct UserBubble: View {
    let text: String
    var body: some View {
        HStack {
            Spacer(minLength: 40)
            Text(text)
                .font(.system(size: 14))
                .foregroundColor(Palette.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Palette.userBubble)
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.userBubbleBorder, lineWidth: 1))
                .cornerRadius(16)
        }
        .padding(.vertical, 8)
    }
}

struct AssistantBlock: View {
    @ObservedObject var store: AppStore
    let m: ChatMessage
    var live: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                RoleDot(letter: "H", user: false)
                Text("Hermes").font(.system(size: 12, weight: .medium)).foregroundColor(Palette.accentText)
                if live { Text("streaming").font(.system(size: 10)).foregroundColor(Palette.muted) }
            }
            .padding(.bottom, 2)
            ForEach(Array(splitChat(m.content).enumerated()), id: \.offset) { _, seg in
                switch seg {
                case .text(let t):
                    if !t.isEmpty {
                        Text(t).font(.system(size: 14)).foregroundColor(Palette.text).padding(.bottom, 4)
                    }
                case .mermaid(let src):
                    MermaidBlock(source: src)
                }
            }
            if !m.content.isEmpty && !live {
                HStack(spacing: 14) {
                    Button("Copy") { UIPasteboard.general.string = m.content }
                    Button("Speak") { Task { await store.speak(m.content) } }
                }
                .font(.system(size: 11))
                .foregroundColor(Palette.muted)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 10)
    }
}

struct ThinkingCard: View {
    let m: ChatMessage
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "lightbulb").font(.system(size: 12)).foregroundColor(Palette.muted)
                Text(m.running ? "Thinking" : "Thought")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(Palette.muted)
                Spacer()
                Image(systemName: open ? "chevron.up" : "chevron.down").font(.system(size: 11)).foregroundColor(Palette.muted)
            }
            if open && !m.content.isEmpty {
                Text(m.content).font(.system(size: 12)).foregroundColor(Palette.muted)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Palette.inputBg)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
        .cornerRadius(12)
        .onTapGesture { open.toggle() }
        .onAppear { open = m.running }
        .padding(.vertical, 4)
    }
}

struct ToolCard: View {
    let m: ChatMessage
    @State private var open = false

    var body: some View {
        let preview = (m.preview.isEmpty ? m.content : m.preview).trimmingCharacters(in: .whitespacesAndNewlines)
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "wrench").font(.system(size: 12)).foregroundColor(Palette.accentText)
                Text(humanTool(m.tool))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(Palette.accentText)
                if m.running { Text("running").font(.system(size: 10)).foregroundColor(Palette.muted) }
                Spacer()
                Image(systemName: open ? "chevron.up" : "chevron.down").font(.system(size: 11)).foregroundColor(Palette.muted)
            }
            if !open && !preview.isEmpty {
                Text(String(preview.prefix(120)))
                    .font(.system(size: 11))
                    .foregroundColor(Palette.muted)
                    .lineLimit(1)
                    .padding(.leading, 22)
            }
            if open && !preview.isEmpty {
                Text(String(preview.prefix(4000)))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(Palette.text)
                    .padding(.top, 4)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Palette.inputBg)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
        .cornerRadius(12)
        .onTapGesture { open.toggle() }
        .padding(.vertical, 3)
    }
}

private func humanTool(_ raw: String) -> String {
    let n = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    switch n.lowercased() {
    case "read_file", "read": return "Read a file"
    case "search_files", "grep", "rg": return "Searched workspace"
    case "skill_view", "skill_manage": return "Loaded a skill"
    case "terminal", "execute", "run": return "Ran a command"
    case "web_search": return "Searched the web"
    case "write_file": return "Wrote a file"
    case "patch": return "Patched a file"
    default:
        let spaced = n.replacingOccurrences(of: "_", with: " ")
        return spaced.prefix(1).uppercased() + spaced.dropFirst()
    }
}

struct ModelPicker: View {
    let options: [ModelOption]
    let selected: String
    @Binding var query: String
    let onPick: (String) -> Void

    var body: some View {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = needle.isEmpty ? options : options.filter {
            $0.id.lowercased().contains(needle) || $0.label.lowercased().contains(needle) || $0.provider.lowercased().contains(needle)
        }
        let grouped = Dictionary(grouping: filtered, by: { $0.provider.isEmpty ? "other" : $0.provider })
        VStack(alignment: .leading, spacing: 6) {
            TextField("Search models (openrouter, grok…)", text: $query)
                .textInputAutocapitalization(.never)
                .font(.system(size: 13))
                .padding(10)
                .background(Palette.bg)
                .foregroundColor(Palette.text)
                .cornerRadius(8)
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    if filtered.isEmpty {
                        Text("No models match.").font(.system(size: 12)).foregroundColor(Palette.muted).padding(10)
                    }
                    ForEach(grouped.keys.sorted(), id: \.self) { provider in
                        Text(provider)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(Palette.accent)
                            .padding(.horizontal, 10)
                            .padding(.top, 8)
                        let rows = grouped[provider] ?? []
                        ForEach(rows.prefix(80)) { m in
                            Text(m.label.isEmpty ? m.id : m.label)
                                .font(.system(size: 13))
                                .foregroundColor(m.id == selected ? Palette.accent : Palette.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 7)
                                .contentShape(Rectangle())
                                .onTapGesture { onPick(m.id) }
                        }
                    }
                }
            }
            .frame(maxHeight: 280)
        }
        .padding(6)
        .background(Palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
        .cornerRadius(12)
        .padding(.horizontal, 12)
    }
}

struct ChipMenu: View {
    let items: [(String, String)]
    let onPick: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.prefix(16).enumerated()), id: \.offset) { _, pair in
                Button(pair.0) { onPick(pair.1) }
                    .font(.system(size: 13))
                    .foregroundColor(Palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
            }
        }
        .padding(6)
        .background(Palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
        .cornerRadius(12)
        .padding(.horizontal, 12)
    }
}

struct FooterChip: View {
    let symbol: String
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: symbol).font(.system(size: 12)).foregroundColor(Palette.muted)
                Text(label).font(.system(size: 12)).foregroundColor(Palette.muted).lineLimit(1)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
        }
    }
}

struct ApprovalBanner: View {
    let a: Approval
    let on: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Approval needed · \(a.tool)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(Palette.accent)
            if !a.detail.isEmpty {
                Text(a.detail).font(.system(size: 12)).foregroundColor(Palette.text).lineLimit(6)
            }
            HStack(spacing: 8) {
                ForEach([("once", "Once"), ("session", "Session"), ("always", "Always"), ("deny", "Deny")], id: \.0) { pair in
                    WuiChip(text: pair.1, selected: pair.0 != "deny") { on(pair.0) }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.accentBg)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.accentBgStrong, lineWidth: 1))
        .cornerRadius(12)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

struct ClarifyBanner: View {
    let q: Clarify
    let on: (String) -> Void
    @State private var other = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(q.question.isEmpty ? "The agent needs a choice" : q.question)
                .font(.system(size: 13))
                .foregroundColor(Palette.text)
            ForEach(q.choices, id: \.self) { c in
                Button(c) { on(c) }.font(.system(size: 13)).foregroundColor(Palette.accent)
            }
            TextField("Or type a reply", text: $other)
                .padding(8)
                .background(Palette.bg)
                .cornerRadius(8)
                .foregroundColor(Palette.text)
            Button("Send") { if !other.isEmpty { on(other) } }.foregroundColor(Palette.accent)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
        .cornerRadius(12)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}
