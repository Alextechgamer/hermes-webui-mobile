import SwiftUI

struct ChatPane: View {
    @ObservedObject var store: AppStore
    @Binding var draft: String

    var body: some View {
        VStack(spacing: 0) {
            if let a = store.approval { ApprovalBanner(a: a) { Task { await store.approve($0) } } }
            if let q = store.clarify { ClarifyBanner(q: q) { Task { await store.answerClarify($0) } } }
            if store.truncated {
                Button("Load full history") { Task { await store.loadFullHistory() } }
                    .foregroundColor(Palette.accent).padding(8)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if store.messages.isEmpty && store.liveText.isEmpty {
                            Text("New conversation. Same chat API as desktop WebUI — type below.")
                                .foregroundColor(Palette.muted).padding(8)
                        }
                        ForEach(store.messages, id: \.displayId) { m in
                            bubble(m)
                        }
                        if !store.liveText.isEmpty {
                            bubble(ChatMessage(role: "assistant", content: store.liveText)).id("live")
                        }
                    }
                    .padding(16)
                }
                .onChange(of: store.liveText) { _ in proxy.scrollTo("live", anchor: .bottom) }
            }
            composer
        }
    }

    private var composer: some View {
        VStack(spacing: 0) {
            HStack {
                Button(store.speakReplies ? "Speak replies on" : "Speak replies off") {
                    store.speakReplies.toggle()
                }.font(.caption).foregroundColor(store.speakReplies ? Palette.accent : Palette.muted)
                if store.listening { Text("Listening…").font(.caption).foregroundColor(Palette.accent) }
                if store.transcribing { Text("Transcribing…").font(.caption).foregroundColor(Palette.accent) }
                Spacer()
            }.padding(.horizontal, 12).padding(.top, 6)
            if !store.models.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(store.models.prefix(16), id: \.self) { m in
                            Text(m)
                                .font(.caption)
                                .foregroundColor(store.selectedModel == m ? Palette.accent : Palette.muted)
                                .padding(6)
                                .background(Palette.surface)
                                .cornerRadius(8)
                                .onTapGesture { store.selectedModel = m }
                        }
                    }.padding(.horizontal, 10).padding(.vertical, 6)
                }
            }
            HStack(alignment: .bottom) {
                TextField("Message Hermes…", text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .padding(10)
                    .background(Palette.surface)
                    .cornerRadius(10)
                    .foregroundColor(Palette.text)
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
                    Image(systemName: store.listening ? "stop.circle" : "mic.circle")
                        .font(.system(size: 26))
                        .foregroundColor(store.listening ? .red : Palette.accent)
                }
                Button {
                    let t = draft; draft = ""
                    Task { await store.send(t) }
                } label: {
                    Image(systemName: store.busy ? "stop.circle.fill" : "arrow.up.circle.fill")
                        .font(.system(size: 28))
                        .foregroundColor(Palette.accent)
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !store.busy)
                .simultaneousGesture(TapGesture().onEnded {
                    if store.busy { Task { await store.stop() } }
                })
            }
            .padding(12)
            .background(Palette.sidebar)
        }
    }

    private func bubble(_ m: ChatMessage) -> some View {
        let mine = m.role == "user"
        return HStack {
            if mine { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                if let tool = m.tool_name ?? m.name, !tool.isEmpty {
                    Text("⚙ \(tool)").font(.caption).foregroundColor(Palette.accent)
                }
                ForEach(Array(splitChat(m.content).enumerated()), id: \.offset) { _, seg in
                    switch seg {
                    case .text(let t):
                        if !t.isEmpty { Text(t).foregroundColor(mine ? .black : Palette.text) }
                    case .mermaid(let src):
                        MermaidBlock(source: src)
                    }
                }
                if !mine && !m.content.isEmpty {
                    Button {
                        Task { await store.speak(m.content) }
                    } label: {
                        Image(systemName: "speaker.wave.2").foregroundColor(Palette.accent)
                    }
                }
            }
            .padding(10)
            .background(mine ? Palette.accent : Palette.surface)
            .cornerRadius(12)
            if !mine { Spacer(minLength: 40) }
        }
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
