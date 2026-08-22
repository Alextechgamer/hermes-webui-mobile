# Hermes WebUI — iOS & Android (native)

A native **1:1 client** of [hermes-webui](https://github.com/NousResearch/hermes-webui):
same dark-gold look (`#0D0D1A` / `#FFD700`), same hamburger menu of every WebUI
rail panel, same REST + SSE APIs. **Not a WebView.**

This repo has **no personal data**. First launch asks for the WebUI URL.

## Panels (same rail as desktop WebUI)

Chat · Tasks · Kanban · Skills · Memory · Spaces · Profiles · Todos ·
Insights · Logs · Settings

**Insights** is Hermes Console (usage, burn, live streams) plus editors for
your monthly plans and per-model API rates. Conversations live **under Chat**
as a collapsed dropdown. Paperclip attaches photos/files from the phone
(`POST /api/upload`). Folder chip opens workspace Files.

## Honest leftover vs desktop

Full mermaid.js, xterm.js, and the desktop workspace tree extras (git
gutter, vscode reveal, office editors) are not cloned. Native Files +
Terminal + Canvas mermaid + server STT/TTS cover the same jobs. No WebView.

## iOS without a Mac?

You can **write** iOS here. You **cannot** compile an `.ipa` on Linux —
that needs Xcode on macOS. `ios/` + `prompts/BUILD_IOS_ON_MAC.md`.

## Android

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
# JDK 17
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enter `http://<host>:8787` then your WebUI password if auth is on.
Optional Hermes Console URL: `http://<host>:8790`.

Current debug build: **0.9.7**.

Chat is live SSE (same as desktop). Leaving the app does **not** cancel the
turn. Android keeps a “Hermes is working” foreground service while a reply
is streaming; coming back reconnects without a refresh.

## License

MIT.
