# Android — native Hermes WebUI

Jetpack Compose client. **No WebView.** Same APIs as the iOS app
([docs/API.md](../docs/API.md)). Version **1.0.0** (`versionCode` 36).

```bash
cp local.properties.example local.properties   # then set sdk.dir
export JAVA_HOME=/path/to/jdk17
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch: WebUI URL (placeholder `http://host:8787`). Then login if required.

Rail: Chat, Tasks, Kanban, Skills, Memory, Spaces, Profiles, Todos,
Insights, Logs, Settings. Conversations nest under Chat. Files / Terminal /
Hermes Console open from the composer folder chip or Settings → System.
Dictate + speak + native Mermaid live in Chat.

See the [root README](../README.md) for install, contributing, and releases.
