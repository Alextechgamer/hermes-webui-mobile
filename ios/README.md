# iOS — native Hermes WebUI (Mac + Xcode)

SwiftUI client for hermes-webui. **No WebView.** Same REST+SSE contract as
Android (`docs/API.md`). Marketing version **0.9.2**.

```bash
git pull
cd ios && ./setup.sh && open HermesWebUI.xcodeproj
```

First launch: enter WebUI URL (placeholder `http://192.168.1.20:8787`). Then
password if the server requires it. Login is remembered.

Rail: Chat, Tasks, Kanban, Skills, Memory, Spaces, Profiles, Todos,
Insights, Logs, Settings. Conversations nest under Chat. Files / Terminal /
Hermes Console open from the composer folder chip or Settings → System.

ATS: `NSAllowsArbitraryLoads` only (do not add `NSAllowsLocalNetworking`).

Paperclip attaches Photos/Files (`POST /api/upload`). Folder chip opens workspace Files. Settings → Providers / Plugins / Extensions / System match Android.

Linux cannot produce an iOS binary. Archive steps: `prompts/BUILD_IOS_ON_MAC.md`.
