# Build native iOS Hermes WebUI on a Mac (paste into Claude Code)

You are on Apple Silicon macOS. Build the native SwiftUI Hermes WebUI app from
https://github.com/Alextechgamer/hermes-webui-mobile (or the local clone).

**Pull `main` first.** Current source is **0.9.12** (`MARKETING_VERSION` in
`ios/project.yml`). THIS IS NOT A WEBVIEW. SwiftUI talks to hermes-webui
REST+SSE. Contract: `docs/API.md`.

Rail (same as desktop WebUI): Chat, Tasks, Kanban, Skills, Memory, Spaces,
Profiles, Todos, Insights, Logs, Settings. Conversations nest under Chat.
Files / Terminal / Hermes Console are opened from Chat chips or
Settings → System — they are not extra rail items.

## Device / archive

1. Confirm Xcode (`xcodebuild -version`) and an Apple ID team.
2. `git pull` then `cd ios && ./setup.sh` (installs XcodeGen via brew if needed).
3. `open HermesWebUI.xcodeproj`
4. Signing: Automatically manage signing → your Team. Bundle id `com.hermes.webui`.
5. Confirm Info.plist has `NSAllowsArbitraryLoads = true` and does **not**
   contain `NSAllowsLocalNetworking` (that combo blocks VPN/CGNAT http).
6. Destinations:
   - Simulator: pick any iPhone simulator, Run (⌘R).
   - Device: plug in the iPhone, trust the computer, pick the device, Run.
   - IPA (Ad Hoc / Development): Product → Archive, then Organizer → Distribute App
     → Ad Hoc or Development → export. Or:

```bash
cd ios
xcodebuild -project HermesWebUI.xcodeproj -scheme HermesWebUI \
  -destination 'generic/platform=iOS' -configuration Release \
  -allowProvisioningUpdates \
  -archivePath "$PWD/build/HermesWebUI.xcarchive" archive

xcodebuild -exportArchive \
  -archivePath "$PWD/build/HermesWebUI.xcarchive" \
  -exportPath "$PWD/build/ipa" \
  -exportOptionsPlist ExportOptions.plist \
  -allowProvisioningUpdates
```

`ExportOptions.plist` is created by Xcode Organizer the first time you
export; do not invent a team id. If that file is missing, use the Organizer GUI.

7. First screen: enter WebUI URL (example only: `http://192.168.1.20:8787`) —
   NOTHING is hardcoded. Password if auth is on. Login is remembered.

Do not add WebView/WKWebView as product UI. Do not bake in any hostname, IP, or password.

Paperclip attaches Photos/Files via `POST /api/upload` (same as Android). Folder
chip opens workspace Files. Keyboard safe-area is handled by SwiftUI. Mid-run
send queues the next turn. Settings sections match desktop.
