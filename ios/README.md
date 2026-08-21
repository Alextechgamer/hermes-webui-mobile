# iOS — native Hermes WebUI (Mac + Xcode)

SwiftUI client for hermes-webui. **No WebView.** Talks to `/api/auth/*`,
`/api/sessions`, `/api/chat/start`, `/api/chat/stream`, `/api/settings`.

```bash
cd ios && ./setup.sh && open HermesWebUI.xcodeproj
```

First launch: enter WebUI URL (placeholder `http://192.168.1.20:8787`). Then
password if the server requires it. Conversations, chat streaming, settings.

ATS: `NSAllowsArbitraryLoads` only (do not add `NSAllowsLocalNetworking`).

Linux cannot produce an iOS binary.
