# Build native iOS Hermes WebUI on a Mac (paste into Claude Code)

You are on Apple Silicon macOS. Build the native SwiftUI Hermes WebUI app from
https://github.com/Alextechgamer/hermes-webui-mobile (or the local clone).

THIS IS NOT A WEBVIEW. SwiftUI screens talk to hermes-webui REST+SSE.
Contract: docs/API.md in this repo.

Panels to keep (same rail as desktop): Chat, Tasks, Kanban, Skills, Memory,
Spaces, Profiles, Todos, Insights, Logs, Dashboard, Settings.

Steps:
1. Confirm Xcode (`xcodebuild -version`).
2. `cd ios && ./setup.sh` (XcodeGen) → open HermesWebUI.xcodeproj
3. Signing: Automatically manage signing → Apple ID team. Bundle id `com.hermes.webui`.
4. Confirm Info.plist has NSAllowsArbitraryLoads=true and does NOT contain
   NSAllowsLocalNetworking (that combo blocks VPN/CGNAT http).
5. `xcodebuild -project HermesWebUI.xcodeproj -scheme HermesWebUI -destination 'generic/platform=iOS' -allowProvisioningUpdates build`
6. Install on device or Simulator. First screen: enter WebUI URL
   (example only: http://192.168.1.20:8787) — NOTHING is hardcoded.
7. If auth is on, password login. Then hamburger → every panel above.

Do not add WebView/WKWebView as product UI. Do not bake in any hostname, IP, or password.
