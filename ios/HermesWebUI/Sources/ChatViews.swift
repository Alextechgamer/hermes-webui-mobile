import SwiftUI

struct ChatPane: View {
    @ObservedObject var store: AppStore
    @Binding var draft: String
    @State private var showPrompts = false
    @State private var showModels = false
    @State private var showProfiles = false
    @State private var modelQuery = ""

    var body: some View {
        VStack(spacing: 0) {
            if let a = store.approval { ApprovalBanner(a: a) { Task { await store.approve($0) } } }
            if let q = store.clarify { ClarifyBanner(q: q) { Task { await store.answerClarify($0) } } }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if store.truncated {
                            Button("Load full history") { Task { await store.loadFullHistory() } }
                                .foregroundColor(Palette.muted).font(.caption)
                        }
                        if store.messages.isEmpty && store.liveText.isEmpty {
                            VStack(spacing: 8) {
                                Text("What can I help with?").font(.title3.weight(.semibold)).foregroundColor(Palette.text)
                                Text("Same live chat as desktop Hermes WebUI.")
                                    .foregroundColor(Palette.muted).font(.footnote)
                            }.frame(maxWidth: .infinity).padding(.top, 72)
                        }
                        ForEach(store.messages, id: \.displayId) { m in row(m) }
                        if !store.liveText.isEmpty {
                            row(ChatMessage(role: "assistant", content: store.liveText)).id("live")
                        }
                    }.padding(16)
                }
                .onChange(of: store.liveText) { _ in proxy.scrollTo("live", anchor: .bottom) }
            }
            composer
        }
    }

    @ViewBuilder
    private func row(_ m: ChatMessage) -> some View {
        if m.role == "user" {
            HStack {
                Spacer(minLength: 40)
                Text(m.content)
                    .foregroundColor(Palette.text)
                    .padding(12)
                    .background(Palette.surface)
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.border, lineWidth: 1))
                    .cornerRadius(14)
            }
        } else if m.role == "thinking" {
            DisclosureGroup(m.running ? "Thinking" : "Thought") {
                Text(m.content).font(.footnote).foregroundColor(Palette.muted)
            }.foregroundColor(Palette.muted)
        } else if m.role == "tool" || (!(m.tool_name ?? m.name ?? "").isEmpty && m.content.isEmpty) {
            DisclosureGroup {
                if !m.content.isEmpty { Text(m.content).font(.system(.footnote, design: .monospaced)).foregroundColor(Palette.text) }
            } label: {
                Text(m.tool.isEmpty ? "Tool" : m.tool.replacingOccurrences(of: "_", with: " "))
                    .foregroundColor(Palette.accent)
            }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text("Hermes").font(.caption.weight(.medium)).foregroundColor(Palette.accent)
                ForEach(Array(splitChat(m.content).enumerated()), id: \.offset) { _, seg in
                    switch seg {
                    case .text(let t): if !t.isEmpty { Text(t).foregroundColor(Palette.text) }
                    case .mermaid(let src): MermaidBlock(source: src)
                    }
                }
                if !m.content.isEmpty {
                    Button("Copy") { UIPasteboard.general.string = m.content }.font(.caption).foregroundColor(Palette.muted)
                }
            }
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if store.listening { Text("Listening…").font(.caption).foregroundColor(Palette.accent).padding(.horizontal, 12) }
            if showModels {
                VStack(alignment: .leading, spacing: 6) {
                    TextField("Search models (openrouter, grok…)", text: $modelQuery)
                        .textInputAutocapitalization(.never)
                        .padding(8)
                        .background(Palette.bg)
                        .foregroundColor(Palette.text)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(filteredModels.prefix(80), id: \.self) { m in
                                Text(m)
                                    .font(.caption)
                                    .foregroundColor(store.selectedModel == m ? Palette.accent : Palette.text)
                                    .padding(.vertical, 4)
                                    .onTapGesture { store.selectedModel = m; showModels = false }
                            }
                        }
                    }
                    .frame(maxHeight: 220)
                }
                .padding(10)
                .background(Palette.surface)
                .padding(.horizontal, 12)
            }
            VStack(alignment: .leading, spacing: 0) {
                TextField("Message Hermes…", text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .padding(12)
                    .foregroundColor(Palette.text)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        Button { Task { await store.go(.files) } } label: { Image(systemName: "paperclip") }
                        Button { store.startListen() } label: { Image(systemName: store.listening ? "stop.circle" : "mic") }
                        Button(store.activeProfile.isEmpty ? "default" : store.activeProfile) { showProfiles.toggle() }
                            .font(.caption)
                        Button(store.selectedModel.isEmpty ? "Model" : store.selectedModel) { showModels.toggle() }
                            .font(.caption)
                        Button {
                            let t = draft; draft = ""
                            Task { await store.send(t) }
                        } label: {
                            Image(systemName: "arrow.up.circle.fill")
                                .font(.system(size: 26))
                        }
                    }
                    .foregroundColor(Palette.muted)
                    .padding(.horizontal, 12).padding(.bottom, 10)
                }
            }
            .background(Palette.surface)
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.accent.opacity(0.35), lineWidth: 1))
            .padding(12)
        }
        .background(Palette.bg)
    }

    private var filteredModels: [String] {
        let q = modelQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty { return store.models }
        return store.models.filter { $0.lowercased().contains(q) }
    }
}

struct ApprovalBanner: View {
    let a: Approval
    let on: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Approval needed · \(a.tool)").foregroundColor(Palette.accent).font(.subheadline)
            if !a.detail.isEmpty { Text(a.detail).foregroundColor(Palette.text).font(.footnote).lineLimit(6) }
            HStack {
                ForEach(["once", "session", "always", "deny"], id: \.self) { k in
                    Button(k.capitalized) { on(k) }
                        .foregroundColor(k == "deny" ? .red : Palette.accent)
                }
            }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Palette.surface)
    }
}

struct ClarifyBanner: View {
    let q: Clarify
    let on: (String) -> Void
    @State private var other = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(q.question.isEmpty ? "The agent needs a choice" : q.question).foregroundColor(Palette.text)
            ForEach(q.choices, id: \.self) { c in
                Button(c) { on(c) }.foregroundColor(Palette.accent)
            }
            TextField("Or type a reply", text: $other)
                .padding(8).background(Palette.bg).cornerRadius(8).foregroundColor(Palette.text)
            Button("Send") { if !other.isEmpty { on(other) } }.foregroundColor(Palette.accent)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Palette.surface)
    }
}
