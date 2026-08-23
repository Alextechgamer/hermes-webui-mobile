# Grok on Mac — archive IPA onto the existing v0.9.13 tag

Paste everything below the line into Grok on the Apple Silicon Mac.

---

You are on Apple Silicon macOS with Xcode. Your only job is to archive the **native SwiftUI** Hermes WebUI app and **attach the IPA to the existing GitHub release `v0.9.13`**. Linux already shipped the APK. You cannot compile this on Linux; do not try to rebuild Android.

Repo: https://github.com/Alextechgamer/hermes-webui-mobile
Tag already exists: `v0.9.13` on `main`. Release already has `hermes-webui-0.9.13-debug.apk`.
**Do not retag. Do not delete or replace the APK. Do not create a new release. Do not bump the version. Do not commit unless you must fix a local signing file that is gitignored.**

This release ports WebUI **Kanban** on iOS to match Android: board switcher, search, assignee/tenant filters, include-archived / only-mine, add task, complete/archive, card detail + status chips, dispatcher preview/run. `AppStore.loadKanban()` uses the existing `/api/kanban/*` client. Official Dashboard (`:9119`) is unchanged.

THIS IS NOT A WEBVIEW. Do not add `WKWebView` / `UIWebView` / `SFSafariViewController` as product UI. Do not bake in any hostname, Tailscale IP, MagicDNS name, `/home/alex`, password, or Apple Team ID.

## Hard checks before you archive

1. `xcodebuild -version` works. Confirm an Apple ID team is available in Xcode (Signing & Capabilities). `ios/project.yml` has **no** `DEVELOPMENT_TEAM` — pick the team locally. Do not write a team id into git.
2. `git clone` if needed, else `git fetch && git checkout main && git pull --ff-only`. Confirm:
   - `ios/project.yml` has `MARKETING_VERSION: "0.9.13"` and `CURRENT_PROJECT_VERSION: "25"`.
   - `ios/HermesWebUI/Sources/AppStore.swift` has `func loadKanban()`.
   - `ios/HermesWebUI/Sources/PanelViews.swift` has board switcher + "New task" (no `kanbanNext` tap-to-advance).
   - `NSAllowsArbitraryLoads = true` and **does not** contain `NSAllowsLocalNetworking`.
3. `cd ios && ./setup.sh`
4. `open HermesWebUI.xcodeproj` → Signing: Automatically manage signing → your Team. Bundle id `com.hermes.webui`.

If `KanbanPane` fails to compile, report the exact Swift error. Do not delete the file to “fix” it.

## Archive + export

Prefer Xcode GUI if `ExportOptions.plist` is missing (do not invent a team id):

- Product → Archive
- Organizer → Distribute App → **Ad Hoc or Development** → export
- Rename the exported IPA to **`hermes-webui-0.9.13.ipa`**

CLI only if a working `ExportOptions.plist` already exists locally (do not commit it):

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

cp "$PWD/build/ipa/"*.ipa "$PWD/build/ipa/hermes-webui-0.9.13.ipa"
```

If export fails on signing, stop and say what Xcode printed. Do not fake an IPA.

## Upload onto the same tag

```bash
gh auth status
gh release view v0.9.13
gh release upload v0.9.13 hermes-webui-0.9.13.ipa
```

Use the real path to the IPA. **Never** `--clobber` the APK. If `hermes-webui-0.9.13.ipa` is already on the release, stop and report.

```bash
gh release view v0.9.13 --json assets --jq '.assets[] | {name,size}'
```

You should see both `hermes-webui-0.9.13-debug.apk` and `hermes-webui-0.9.13.ipa`. Reply with the release URL and both asset names + sizes. Done.
