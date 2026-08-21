# Android — native Hermes WebUI

Jetpack Compose client. **No WebView.** Same APIs as the iOS app.

```bash
echo "sdk.dir=\$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch: WebUI URL (placeholder `http://192.168.1.20:8787`). Then login if required.
