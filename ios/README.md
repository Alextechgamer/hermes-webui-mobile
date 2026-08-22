# iOS — native Hermes WebUI (Mac + Xcode)

SwiftUI client for hermes-webui. **No WebView.** Same REST+SSE contract as
Android (`docs/API.md`).

```bash
cd ios && ./setup.sh && open HermesWebUI.xcodeproj
```

First launch: enter WebUI URL (placeholder `http://192.168.1.20:8787`). Then
password if the server requires it.

Panels: Chat, Tasks, Kanban, Skills, Memory, Spaces, Profiles, Todos,
Files, Terminal, Insights, Logs, Dashboard, Settings.
Dictate + speak + native Mermaid live in Chat.

ATS: `NSAllowsArbitraryLoads` only (do not add `NSAllowsLocalNetworking`).

Linux cannot produce an iOS binary.
