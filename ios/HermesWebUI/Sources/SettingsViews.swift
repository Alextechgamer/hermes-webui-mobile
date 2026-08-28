import SwiftUI

struct SettingsPane: View {
    @ObservedObject var store: AppStore
    @ObservedObject var settings: AppSettings

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 1) {
                Text("SETTINGS")
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(1.1)
                    .foregroundColor(Palette.muted)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                ScrollView {
                    VStack(alignment: .leading, spacing: 1) {
                        ForEach(SettingsSection.allCases) { s in
                            let sel = store.settingsSection == s
                            Text(s.label)
                                .font(.system(size: 14, weight: sel ? .semibold : .regular))
                                .foregroundColor(sel ? Palette.accent : Palette.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 10)
                                .background(sel ? Palette.accentBg : Color.clear)
                                .cornerRadius(12)
                                .padding(.horizontal, 8)
                                .contentShape(Rectangle())
                                .onTapGesture { store.settingsSection = s }
                        }
                    }
                }
            }
            .frame(width: 150)
            .background(Palette.sidebar)

            Group {
                switch store.settingsSection {
                case .providers: ProvidersSettings(store: store)
                case .plugins: PluginsSettings(store: store)
                case .extensions: ExtensionsSettings(store: store)
                case .help: HelpSettings()
                case .system: SystemSettings(store: store)
                default: KeySettings(store: store, section: store.settingsSection)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Palette.bg)
        .task { await store.loadPanel() }
    }
}

struct KeySettings: View {
    @ObservedObject var store: AppStore
    let section: SettingsSection

    var body: some View {
        let q = store.settingsQuery.trimmingCharacters(in: .whitespaces).lowercased()
        let items = store.settingsItems.filter {
            $0.section() == section && (q.isEmpty || $0.key.lowercased().contains(q) || $0.label.lowercased().contains(q))
        }
        VStack(alignment: .leading, spacing: 0) {
            Text(section.label)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(Palette.text)
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 4)
            TextField("Search settings…", text: $store.settingsQuery)
                .foregroundColor(Palette.text)
                .padding(10)
                .background(Palette.surface)
                .cornerRadius(8)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            Text(blurb)
                .font(.system(size: 12))
                .foregroundColor(Palette.muted)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            if items.isEmpty {
                Text("No \(section.label.lowercased()) keys on this server.")
                    .foregroundColor(Palette.muted)
                    .padding(16)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(items) { item in
                        SettingRow(store: store, item: item)
                    }
                }
            }
            if !store.settingEdits.isEmpty {
                Button("Save \(store.settingEdits.count) changes") { Task { await store.saveSettings() } }
                    .foregroundColor(Palette.accent)
                    .padding(12)
            }
        }
    }

    private var blurb: String {
        switch section {
        case .conversation: return "Transcript, tools, and how this chat behaves."
        case .appearance: return "Theme, accent, and visual style."
        case .preferences: return "Defaults and UI behavior."
        default: return "Same keys as desktop WebUI. Secrets stay hidden."
        }
    }
}

struct SettingRow: View {
    @ObservedObject var store: AppStore
    let item: SettingItem

    var body: some View {
        let current = store.settingEdits[item.key] ?? item.value
        VStack(alignment: .leading, spacing: 6) {
            Text(item.label)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(Palette.text)
            Text(item.key)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(Palette.muted)
            if item.type == "bool" {
                Toggle("", isOn: Binding(
                    get: { current == "true" },
                    set: { store.settingEdits[item.key] = $0 ? "true" : "false" }
                ))
                .labelsHidden()
                .tint(Palette.accent)
            } else if item.type != "json" {
                TextField("", text: Binding(
                    get: { current },
                    set: { store.settingEdits[item.key] = $0 }
                ))
                .padding(10)
                .background(Palette.surface)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border, lineWidth: 1))
                .cornerRadius(8)
                .foregroundColor(Palette.text)
            } else {
                Text(item.value).font(.system(size: 12)).foregroundColor(Palette.muted)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        Divider().background(Palette.border)
    }
}

struct ProvidersSettings: View {
    @ObservedObject var store: AppStore
    @State private var editing: String?
    @State private var key = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Providers").font(.system(size: 18, weight: .semibold)).foregroundColor(Palette.text)
                Text("API keys for AI providers. Same as desktop Settings → Providers.")
                    .font(.system(size: 12)).foregroundColor(Palette.muted)
                    .padding(.bottom, 4)
                if store.providers.isEmpty {
                    Text("No providers returned.").foregroundColor(Palette.muted)
                }
                ForEach(store.providers) { p in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(p.displayName).fontWeight(.semibold).foregroundColor(Palette.text)
                            Spacer()
                            Text(p.hasKey ? "configured" : "no key")
                                .font(.system(size: 12))
                                .foregroundColor(p.hasKey ? Palette.ok : Palette.muted)
                        }
                        Text(p.id + (p.keySource.isEmpty ? "" : " · \(p.keySource)"))
                            .font(.system(size: 11)).foregroundColor(Palette.muted)
                        if p.configurable {
                            if editing == p.id {
                                SecureField("API key", text: $key)
                                    .padding(10)
                                    .background(Palette.bg)
                                    .cornerRadius(8)
                                    .foregroundColor(Palette.text)
                                HStack {
                                    Button("Save") {
                                        Task { await store.setProviderKey(p.id, key); editing = nil; key = "" }
                                    }.foregroundColor(Palette.accent)
                                    Button("Cancel") { editing = nil; key = "" }.foregroundColor(Palette.muted)
                                }
                            } else {
                                Button("Set key") { editing = p.id; key = "" }
                                    .font(.system(size: 13))
                                    .foregroundColor(Palette.accent)
                            }
                        }
                    }
                    .padding(12)
                    .background(Palette.surface)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                    .cornerRadius(12)
                }
                ForEach(store.settingsItems.filter { $0.section() == .providers }) { item in
                    SettingRow(store: store, item: item)
                }
                if !store.settingEdits.isEmpty {
                    Button("Save setting changes") { Task { await store.saveSettings() } }
                        .foregroundColor(Palette.accent)
                }
            }
            .padding(16)
        }
    }
}

struct PluginsSettings: View {
    @ObservedObject var store: AppStore
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Plugins").font(.system(size: 18, weight: .semibold)).foregroundColor(Palette.text)
                Text("Installed Hermes plugins. Read-only, same as desktop.")
                    .font(.system(size: 12)).foregroundColor(Palette.muted)
                if store.plugins.isEmpty { Text("No plugins installed.").foregroundColor(Palette.muted) }
                ForEach(store.plugins) { p in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(p.name).fontWeight(.semibold).foregroundColor(Palette.text)
                        if !p.description.isEmpty {
                            Text(p.description).font(.system(size: 12)).foregroundColor(Palette.muted)
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.surface)
                    .cornerRadius(12)
                }
            }
            .padding(16)
        }
    }
}

struct ExtensionsSettings: View {
    @ObservedObject var store: AppStore
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Extensions").font(.system(size: 18, weight: .semibold)).foregroundColor(Palette.text)
                Text("WebUI extensions on this instance.")
                    .font(.system(size: 12)).foregroundColor(Palette.muted)
                if store.extensions.isEmpty { Text("No extensions installed.").foregroundColor(Palette.muted) }
                ForEach(store.extensions) { e in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(e.name).fontWeight(.semibold).foregroundColor(Palette.text)
                            Spacer()
                            Text(e.enabled ? "on" : "off")
                                .font(.system(size: 12))
                                .foregroundColor(e.enabled ? Palette.ok : Palette.muted)
                        }
                        if !e.description.isEmpty {
                            Text(e.description).font(.system(size: 12)).foregroundColor(Palette.muted)
                        }
                    }
                    .padding(12)
                    .background(Palette.surface)
                    .cornerRadius(12)
                }
            }
            .padding(16)
        }
    }
}

struct SystemSettings: View {
    @ObservedObject var store: AppStore
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("System").font(.system(size: 18, weight: .semibold)).foregroundColor(Palette.text)
                Text("Instance access and Hermes Console.")
                    .font(.system(size: 12)).foregroundColor(Palette.muted)
                    .padding(.bottom, 4)
                Button { Task { await store.go(.console) } } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Hermes Console").fontWeight(.semibold).foregroundColor(Palette.text)
                            Text("Usage, burn, live streams — :8790 /api/usage")
                                .font(.system(size: 12)).foregroundColor(Palette.muted)
                        }
                        Spacer()
                        Text("Open").foregroundColor(Palette.accent)
                    }
                    .padding(14)
                    .background(Palette.surface)
                    .cornerRadius(12)
                }
                Button { Task { await store.go(.terminal) } } label: {
                    HStack {
                        Text("Terminal").fontWeight(.semibold).foregroundColor(Palette.text)
                        Spacer()
                        Text("Open").foregroundColor(Palette.accent)
                    }
                    .padding(14)
                    .background(Palette.surface)
                    .cornerRadius(12)
                }
                ForEach(store.settingsItems.filter { $0.section() == .system }) { item in
                    SettingRow(store: store, item: item)
                }
                if !store.settingEdits.isEmpty {
                    Button("Save \(store.settingEdits.count) changes") { Task { await store.saveSettings() } }
                        .foregroundColor(Palette.accent)
                }
            }
            .padding(16)
        }
    }
}

struct HelpSettings: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Help").font(.system(size: 18, weight: .semibold)).foregroundColor(Palette.text)
            Text("Native client of hermes-webui. Not a WebView.")
                .font(.system(size: 13)).foregroundColor(Palette.muted)
            Text("Docs: github.com/NousResearch/hermes-webui")
                .font(.system(size: 13)).foregroundColor(Palette.accentText)
                .padding(.top, 4)
            Text("This app: github.com/Alextechgamer/hermes-webui-mobile")
                .font(.system(size: 13)).foregroundColor(Palette.accentText)
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
