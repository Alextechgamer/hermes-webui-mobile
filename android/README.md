# Android — native Hermes WebUI

Jetpack Compose client. **No WebView.** Same APIs as the iOS app
(`docs/API.md`). Version **0.9.8**.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch: WebUI URL (placeholder `http://host:8787`). Then login if required.

Rail: Chat, Tasks, Kanban, Skills, Memory, Spaces, Profiles, Todos,
Insights, Logs, Settings. Conversations nest under Chat. Files / Terminal /
Hermes Console open from the composer folder chip or Settings → System.
Dictate + speak + native Mermaid live in Chat.
