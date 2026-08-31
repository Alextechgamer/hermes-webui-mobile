# Releasing

Android and iOS versions stay in lockstep.

| Field | File | Example |
|---|---|---|
| Android `versionName` | `android/app/build.gradle.kts` | `1.0.0` |
| Android `versionCode` | same | `36` |
| iOS `MARKETING_VERSION` | `ios/project.yml` | `1.0.0` |
| iOS `CURRENT_PROJECT_VERSION` | same | `36` |

Bump **all four** in the same commit. `versionCode` / `CURRENT_PROJECT_VERSION`
only ever increase.

## Linux (APK + tag)

```bash
cd android
export JAVA_HOME=/path/to/jdk17
./gradlew testDebugUnitTest assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/hermes-webui-VERSION-debug.apk
```

Rebuild the APK **after** the version bump — an earlier `assembleDebug` still
has the old `versionName`.

```bash
git tag vVERSION
git push origin main --tags
gh release create vVERSION /tmp/hermes-webui-VERSION-debug.apk \
  --title "vVERSION" \
  --notes-file CHANGELOG.md
```

Prefer short notes that point at the CHANGELOG section for that version.

Do not bake hostnames, IPs, passwords, or Apple Team IDs into the tag or notes.

## macOS (IPA onto the same tag)

Linux cannot compile iOS. On a Mac, follow [BUILD_IOS.md](BUILD_IOS.md), then:

```bash
gh release upload vVERSION hermes-webui-VERSION.ipa
```

- Do **not** retag.
- Do **not** delete or `--clobber` the APK.
- Do **not** bump versions on the Mac.
- If `hermes-webui-VERSION.ipa` is already on the release, stop.

```bash
gh release view vVERSION --json assets --jq '.assets[] | {name,size}'
```

You should see both `hermes-webui-VERSION-debug.apk` and `hermes-webui-VERSION.ipa`.

## Checklist before tagging

- [ ] `docs/API.md` matches what both clients call
- [ ] [QA_CHECKLIST.md](QA_CHECKLIST.md) run on a device or emulator against a live WebUI
- [ ] `OfficialDashView` / `:9119` dashboard panel still absent
- [ ] Insights still hits `/api/insights` (not `:8790`)
- [ ] First-run URL field empty
- [ ] `git grep` for hostnames, `100.x`, passwords, `/home/`, `/Users/` is clean
