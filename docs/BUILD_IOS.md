# Build the iOS app (macOS + Xcode)

Linux can edit `ios/`. Only a Mac can compile an `.ipa`.

Current marketing version is in `ios/project.yml` (`MARKETING_VERSION` /
`CURRENT_PROJECT_VERSION`). Keep those equal to Android `versionName` /
`versionCode`.

This is **not a WebView**. SwiftUI talks to hermes-webui over REST + SSE.
Contract: [API.md](API.md).

## Simulator or device

1. Confirm Xcode (`xcodebuild -version`) and an Apple ID team.
2. `git pull` then `cd ios && ./setup.sh` (installs [XcodeGen](https://github.com/yonaskolb/XcodeGen) via Homebrew if needed).
3. `open HermesWebUI.xcodeproj`
4. Signing: Automatically manage signing → your Team. Bundle id `com.hermes.webui`.
   Do **not** commit a `DEVELOPMENT_TEAM` or `ExportOptions.plist`.
5. Confirm `Info.plist` has `NSAllowsArbitraryLoads = true` and does **not**
   contain `NSAllowsLocalNetworking` (that combo blocks VPN / CGNAT `http://`).
6. Run on a simulator or a trusted device (⌘R).

First screen: enter your WebUI URL (placeholder `http://host:8787`). Nothing
is hardcoded. Password if the server requires it. Login is remembered in the
Keychain.

## Archive an IPA (Ad Hoc / Development)

Prefer Xcode GUI if `ExportOptions.plist` is missing (do not invent a team id):

- Product → Archive
- Organizer → Distribute App → **Ad Hoc** or **Development** → export
- Rename to `hermes-webui-<version>.ipa`

CLI only if a working `ExportOptions.plist` already exists locally:

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

If export fails on signing, stop and fix Xcode signing. Do not fake an IPA.

Attach the IPA to the **existing** GitHub tag that already has the Android APK
— see [RELEASING.md](RELEASING.md). Do not retag. Do not replace the APK.
