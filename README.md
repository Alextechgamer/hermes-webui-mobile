# Hermes WebUI — iOS & Android (native)

A **1:1 native port** of [hermes-webui](https://github.com/NousResearch/hermes-webui):
the same screens, the same APIs, the same dark-gold look — implemented in
**SwiftUI** and **Jetpack Compose**. Not a WebView.

The apps talk to a hermes-webui instance you already host (default port **8787**).
They do not run Hermes themselves.

This repo contains **no personal data**: no hostnames, no VPN IPs, no passwords,
no API keys. First launch asks for the WebUI URL.

## Can you build iOS without a Mac?

**Write the app: yes. Produce an installable `.ipa` / run on a phone: no.**

Apple only compiles and signs iOS apps with **Xcode on macOS**. This tree ships a
complete SwiftUI + XcodeGen project. On a Mac:

```bash
cd ios && ./setup.sh && open HermesWebUI.xcodeproj
```

A free Apple ID can run on your own iPhone (re-sign every 7 days).

Android **can** be built on Linux.

## What is ported (v1)

Matches hermes-webui's primary product surface:

| WebUI | Native |
|---|---|
| Login (password) | Login screen |
| Left session list + New chat | Drawer / sidebar |
| Chat + streaming tokens + tool cards | Chat thread + composer |
| Control Center settings (`GET/POST /api/settings`) | Settings editor |
| Dark theme `#0D0D1A` / gold `#FFD700` | Same tokens |

Not in v1 (still on the desktop WebUI): workspace file browser, kanban, cron UI,
voice, custom skins beyond dark-gold. The rail is ready to grow.

## Prerequisites

- hermes-webui running (`./ctl.sh start` / `./start.sh`), reachable from the phone
- Same LAN or a VPN; URL entered in-app (example placeholder only: `http://192.168.1.20:8787`)

## Android

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## iOS (Mac + Xcode)

```bash
cd ios && ./setup.sh && open HermesWebUI.xcodeproj
```

`Info.plist` sets `NSAllowsArbitraryLoads` (and **not** `NSAllowsLocalNetworking`,
which would silently block many VPN addresses).

## Layout

```
android/   Jetpack Compose
ios/       SwiftUI (XcodeGen)
```

## License

MIT. hermes-webui has its own license; this repo is only the native clients.
