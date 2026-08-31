# Contributing

Thanks for helping. This repo is a **native 1:1 client** of [hermes-webui](https://github.com/NousResearch/hermes-webui). The product is the same rail, the same APIs, and the same dark-gold look — not a redesign.

## Before you start

1. You need a live hermes-webui (`:8787`) you can reach from an emulator or device.
2. Read [docs/API.md](docs/API.md). That file is the contract both apps implement.
3. Skim [docs/QA_CHECKLIST.md](docs/QA_CHECKLIST.md) so you know which flows have already bitten us.

## Hard rules

1. **No WebView.** No `WebView`, `WKWebView`, `UIWebView`, or `SFSafariViewController` as product UI. Files, Terminal, voice, and Mermaid are native (REST + SSE + Canvas).
2. **No personal data in git.** No hostnames, LAN/VPN IPs, MagicDNS names, passwords, `/home/…`, `/Users/…`, or Apple Team IDs. First-run URL is empty. `local.properties`, `.env`, and `ExportOptions.plist` stay gitignored.
3. **1:1, not a new app.** Do not invent panels, rename the rail, or restyle the palette. Tokens: `#0D0D1A` / `#141425` / `#1A1A2E` / `#FFF8DC` / `#FFD700` / `#2A2A45`.
4. **Both platforms.** If the change is shared behavior, land Android (`android/`) and iOS (`ios/`) together. Linux cannot compile Swift; still write the iOS source and keep it in the same PR.
5. **Do not auto-send chat.** A test message on connect starts a live agent run on the user’s machine.

## Where to put work

| Change | Location |
|---|---|
| Android UI | `android/app/src/main/java/com/hermes/webui/` |
| Android API | `ApiClient.kt` |
| iOS UI | `ios/HermesWebUI/Sources/` |
| iOS API | `APIClient.swift` |
| Shared contract | `docs/API.md` |
| Icons | `scripts/make_app_icon.py` + `branding/` |

Match endpoint names and JSON keys to desktop. Session list count is **`messages`**, not `message_count`. Grok / xAI model ids keep a version **dot** (`grok-4.6`), never `grok-4-6`. `POST /api/chat/start` sends `session_id`, `message`, **`model`**, and **`model_provider`**.

## Dev setup

### Android

```bash
cd android
cp local.properties.example local.properties   # then set sdk.dir
export JAVA_HOME=/path/to/jdk17
./gradlew testDebugUnitTest assembleDebug
```

### iOS (macOS)

```bash
cd ios && ./setup.sh
open HermesWebUI.xcodeproj
```

See [docs/BUILD_IOS.md](docs/BUILD_IOS.md). ATS: `NSAllowsArbitraryLoads` only.

## Pull requests

- One concern per PR. Conventional commits (`feat:`, `fix:`, `docs:`, `chore:`) help.
- Update `docs/API.md` when you add an endpoint.
- Add or extend unit tests under `android/app/src/test/` when you touch parsing / ids.
- Do not bump `versionName` / `MARKETING_VERSION` in a feature PR unless you are cutting a release ([docs/RELEASING.md](docs/RELEASING.md)).
- Fill in the PR template checklist.

Bugs in **Hermes Agent / hermes-webui itself** belong upstream:

- https://github.com/NousResearch/hermes-agent
- https://github.com/NousResearch/hermes-webui

Bugs in **these native apps** (wrong panel, missed endpoint, iOS/Android drift) belong here.

## Code of conduct

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Report security issues privately — [SECURITY.md](SECURITY.md).
