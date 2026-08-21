# Android — native Hermes WebUI

Jetpack Compose client. **No WebView.** Same APIs as the iOS app
(`docs/API.md`). Version **0.3.0**.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch: WebUI URL (placeholder `http://192.168.1.20:8787`). Then login if required.

Panels: Chat, Tasks, Kanban, Skills, Memory, Spaces, Profiles, Todos,
Insights, Logs, Dashboard, Settings.
