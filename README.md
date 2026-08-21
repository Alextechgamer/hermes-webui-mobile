# Hermes WebUI — iOS & Android (native)

A native **1:1 client** of [hermes-webui](https://github.com/NousResearch/hermes-webui):
same dark-gold look (`#0D0D1A` / `#FFD700`), same hamburger menu of every WebUI
rail panel, same REST + SSE APIs. **Not a WebView.**

This repo has **no personal data**. First launch asks for the WebUI URL.

## Panels (same rail as desktop WebUI)

Chat · Tasks · Kanban · Skills · Memory · Spaces · Profiles · Todos ·
Insights · Logs · **Dashboard** · Settings

Chat is a real conversation (sessions, stream tokens, stop, model chip,
approvals / clarify). Other panels bind the same endpoints the desktop
tabs use — not JSON dumps. See `docs/API.md`.

Dashboard talks to `/api/dashboard/status` + `/health` on WebUI, and
optionally a separate dashboard URL (official Hermes Dashboard `:9119`
or usage dashboard `:8790`).

## Honest gaps

Voice, Mermaid, the workspace editor, and the terminal pane stay on
desktop WebUI. This client does not embed a WebView to fake them.

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
Optional dashboard URL: `http://<host>:9119`.

Current debug build: **0.3.0**.

## License

MIT.
