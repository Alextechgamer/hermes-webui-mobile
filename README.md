<p align="center">
  <img src="branding/app-icon-1024.png" width="128" height="128" alt="Hermes WebUI caduceus icon">
</p>

<h1 align="center">Hermes WebUI — iOS &amp; Android</h1>

<p align="center">
  Native <strong>1:1 clients</strong> of
  <a href="https://github.com/NousResearch/hermes-webui">hermes-webui</a>.
  SwiftUI + Jetpack Compose. Same dark-gold look, same rail, same REST + SSE APIs.
  <strong>Not a WebView. Not a PWA.</strong>
</p>

<p align="center">
  <a href="https://github.com/Alextechgamer/hermes-webui-mobile/releases/latest"><img src="https://img.shields.io/github/v/release/Alextechgamer/hermes-webui-mobile?color=FFD700&label=release" alt="Latest release"></a>
  <a href="https://github.com/Alextechgamer/hermes-webui-mobile/actions/workflows/android.yml"><img src="https://github.com/Alextechgamer/hermes-webui-mobile/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/iOS-16%2B-000000" alt="iOS 16+">
  <img src="https://img.shields.io/badge/license-MIT-0D0D1A" alt="MIT license">
  <img src="https://img.shields.io/badge/WebView-none-red" alt="No WebView">
</p>

This is a **community** client. It talks to **your** [Hermes Agent](https://github.com/NousResearch/hermes-agent) WebUI (typically `:8787`). Nothing is hardcoded: first launch asks for the URL. The app does not collect telemetry and does not proxy traffic through a third party.

## What you get

The hamburger / rail matches desktop WebUI:

**Chat · Tasks · Kanban · Skills · Memory · Spaces · Profiles · Todos · Insights · Logs · Settings**

Conversations live **under Chat** (WebUI / CLI tabs). Files, Terminal, and the Hermes Console (`:8790` usage desk) are **not** extra rail items — they open from Chat chips or Settings → System.

| Area | What the apps actually do |
|---|---|
| Chat | Live SSE tokens, mid-run **STEER**, Stop (only on purpose — backgrounding does **not** cancel), attachments (`POST /api/upload`), slash commands, YOLO, Persona, Compress, model picker + Set default, reasoning effort, native Mermaid canvas, dictate / speak |
| Sessions | Pin, archive, rename, duplicate, share, export JSON / HTML / Markdown, import JSON, branch, move to project, retry / undo / regenerate title, search |
| Tasks | Human schedules, New job, Edit, pause / resume / run, output history, running badge |
| Kanban | Boards, search, assignee / tenant chips, add / complete / archive, bulk status, dispatcher |
| Skills | Search, category groups, toggle, New skill |
| Memory | `memory` / `user` / `soul` / `project_context` sections, write-back |
| Spaces | Add / remove, path suggestions, reorder |
| Profiles | Switch / create / delete |
| Insights | `GET /api/insights` for 7 / 30 / 90 / 365 days + skill usage + models. **Not** the `:8790` Console |
| Logs | Tail, severity, copy |
| Settings | Conversation / Appearance / Preferences / Providers / Plugins (MCP) / Extensions (gallery) / System (health, updates, sign out, Console URL) |
| Files | Create, rename, move, delete, save against the session workspace |
| Terminal | Start / input / resize / SSE output (ANSI stripped) |

Look (same tokens as desktop): `--bg` `#0D0D1A` · `--sidebar` `#141425` · `--surface` `#1A1A2E` · `--text` `#FFF8DC` · `--accent` `#FFD700` · `--border` `#2A2A45`.

## What this is not

- **Not Nous Research official.** Upstream UI is [NousResearch/hermes-webui](https://github.com/NousResearch/hermes-webui). File agent bugs there; file **this app’s** bugs [here](https://github.com/Alextechgamer/hermes-webui-mobile/issues).
- **Not a WebView** of `/` or `/chat`. Chat in the browser is an xterm TUI over `/api/pty`. These apps speak REST + SSE.
- **Not the official Hermes Dashboard** (`:9119`). That panel was removed in v0.9.16.
- **Not on the App Store or Play Store** yet. Releases are sideload APK + ad-hoc / development IPA.
- **Not a pixel clone of 400k+ lines of WebUI JS.** Panel and API coverage is the bar.

Honest leftovers vs desktop: mermaid.js (we draw a native flowchart / sequence / pie subset), xterm.js, git gutter / vscode reveal / office editors on Files, passkeys, onboarding, i18n, wiki/notes. Transcript markdown and image thumbnails are still plain text + name chips.

## Requirements

1. A running **hermes-webui** (Hermes Agent). Default port **8787**. Optional Console on **8790**.
2. The phone must **reach that host** — LAN, VPN, or a reverse proxy. `localhost` on the phone is the phone, not your PC.
3. **Android 8.0** (API 26) or later, or **iOS 16** or later.

Auth is the same as desktop: cookie + CSRF (`X-Hermes-CSRF-Token`). No bearer token. If the server has a password, enter it once; Android keeps it in EncryptedSharedPreferences, iOS in the Keychain.

## Install

Download the latest assets from [Releases](https://github.com/Alextechgamer/hermes-webui-mobile/releases/latest):

| Platform | Asset | Notes |
|---|---|---|
| Android | `hermes-webui-<version>-debug.apk` | Sideload. Allow install from this source. Debug-signed (not Play Store). |
| iOS | `hermes-webui-<version>.ipa` | Ad Hoc / Development. Install with Xcode, Apple Configurator, AltStore, or Sideloadly. Needs a signing team on a Mac. |

### First launch

1. URL field starts **empty** (no baked-in host).
2. Enter `http://<host>:8787` (or `https://` if you terminate TLS).
3. Optional: Hermes Console URL `http://<host>:8790`. Leave blank to derive `:8790` from the WebUI host.
4. Password if auth is on. Password managers / platform autofill can fill URL + password.

From an Android emulator on the same machine as WebUI, the host is usually `http://10.0.2.2:8787`.

Chat is live SSE. Leaving the app does **not** cancel the turn. Android keeps a “Hermes is working” foreground service while a reply is streaming; coming back reconnects without a refresh.

## Build from source

### Android (Linux, macOS, Windows)

JDK **17** and an Android SDK (compile SDK 34).

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
export JAVA_HOME=/path/to/jdk17
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` is gitignored. Copy `android/local.properties.example` if you want a template.

### iOS (macOS + Xcode only)

Linux can **edit** `ios/`; it cannot compile an `.ipa`.

```bash
git pull
cd ios && ./setup.sh          # XcodeGen → HermesWebUI.xcodeproj
open HermesWebUI.xcodeproj
```

Signing: Automatically manage signing → your Team. Bundle id `com.hermes.webui`. Do **not** commit a team id.

ATS must stay `NSAllowsArbitraryLoads = true` **only**. Do not also set `NSAllowsLocalNetworking` — iOS then ignores ArbitraryLoads and blocks CGNAT / VPN `http://` hosts.

Full archive steps: [docs/BUILD_IOS.md](docs/BUILD_IOS.md).

## Repository layout

```
android/     Jetpack Compose app (com.hermes.webui)
ios/         SwiftUI app + XcodeGen project.yml
docs/        API contract, QA checklist, iOS build, releasing
branding/    Official Hermes caduceus source + 1024px app icon
scripts/     Regenerates launcher icons from branding/
```

Both clients share one contract: [docs/API.md](docs/API.md). If Android and iOS disagree, that is a bug.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md). Short version:

- Keep **1:1 WebUI** behavior. Do not start a custom-design app in this repo.
- No WebView / WKWebView / SFSafariViewController as product UI.
- No hostnames, IPs, passwords, Apple Team IDs, or home paths in git.
- When a feature is shared behavior, land it on **both** Android and iOS.
- Run `./gradlew testDebugUnitTest` and the [on-device checklist](docs/QA_CHECKLIST.md) before a release.

Security reports: [SECURITY.md](SECURITY.md). Code of conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Versioning & releases

`versionName` / `MARKETING_VERSION` and `versionCode` / `CURRENT_PROJECT_VERSION` stay in lockstep (currently **1.0.0** / **36**).

Linux cuts the git tag and attaches the Android APK. A Mac attaches the IPA to the **same** tag. See [docs/RELEASING.md](docs/RELEASING.md).

## Credits

- [Hermes Agent](https://github.com/NousResearch/hermes-agent) and [hermes-webui](https://github.com/NousResearch/hermes-webui) by [Nous Research](https://nousresearch.com/)
- Caduceus launcher mark from the official WebUI branding
- Native clients in this repository by [Alextechgamer](https://github.com/Alextechgamer)

## License

[MIT](LICENSE)
