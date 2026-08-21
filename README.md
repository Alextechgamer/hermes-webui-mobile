# Hermes WebUI — iOS & Android (native)

A native **1:1 client** of [hermes-webui](https://github.com/NousResearch/hermes-webui):
same dark-gold look (`#0D0D1A` / `#FFD700`), same hamburger menu of every WebUI
panel, same APIs. **Not a WebView.**

This repo has **no personal data**. First launch asks for the WebUI URL.

## Panels (same as desktop WebUI)

Chat · Tasks (cron) · Kanban · Spaces · Skills · Memory · Logs · Profiles ·
**Dashboard** · Settings

Dashboard talks to `/api/dashboard/status` + `/health` on WebUI, and optionally
a separate dashboard URL (official Hermes Dashboard `:9119` or usage dashboard
`:8790`).

## iOS without a Mac?

You can **write** iOS here. You **cannot** compile an `.ipa` on Linux — that
needs Xcode on macOS. `ios/` + `prompts/BUILD_IOS_ON_MAC.md`.

## Android

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enter `http://<host>:8787` then your WebUI password if auth is on.
Optional dashboard URL: `http://<host>:9119`.

## License

MIT.
