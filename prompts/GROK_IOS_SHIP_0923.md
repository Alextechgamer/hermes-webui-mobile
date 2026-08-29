# Grok on Mac — push iOS QA fixes, cut v0.9.23, finish the open checks

Paste everything below the line into Grok on the Apple Silicon Mac (Xcode + iOS Simulators). Continues the on-device iOS QA pass you just did on `Alextechgamer/hermes-webui-mobile`.

---

You already ran an on-device iOS QA pass and made two local commits (session Rename + Attach VoiceOver label; Terminal ANSI stripping + chat title restored after Files/Terminal). Those commits are **not pushed** and the GitHub v0.9.22 IPA does not contain them. This task: push them, finish the checks you didn't close, fix one cosmetic bug, cut **v0.9.23**, and archive the iOS IPA — while giving the Linux side your exact diffs so it can confirm nothing drifts from Android.

Repo: https://github.com/Alextechgamer/hermes-webui-mobile — branch `main`. Current pushed tag: `v0.9.22`.

## Absolute rules (unchanged)

1. **NOT a WebView.** No `WKWebView`/`UIWebView`/`SFSafariViewController` as product UI.
2. **No secrets in git, ever.** No password, hostname, LAN/Tailscale IP, MagicDNS name, `/Users/...`, `/home/alex`, or Apple Team ID in any committed file. WebUI URL + password are entered at runtime in the app only.
3. **Report honestly.** Never fake a screenshot, a passing check, a server response, or a build. If a check fails, say so with the exact symptom. A real "still broken" beats a fabricated pass.
4. **Verify side effects against the server**, not just the UI (cookie-jar curl recipe below). The UI updating is not proof the server changed.
5. **iOS-only changes.** Do NOT touch `android/`. Do NOT retag or overwrite existing releases/assets.

## Step 0 — show your work FIRST (before pushing)

The Linux side needs to confirm your two commits are pure iOS catch-up (no shared-logic drift from Android). Before you push, output for review:

```bash
git fetch origin
git log --oneline origin/main..HEAD          # should list your 2 local commits
git diff origin/main..HEAD -- ios/           # the full iOS diff
git diff origin/main..HEAD -- . ':(exclude)ios/'   # MUST be empty — prove nothing outside ios/ changed
```
Paste all three outputs in your reply. If the third command is non-empty, STOP and report what non-iOS files changed — that's unexpected.

## Step 1 — get environment + rebuild clean

Ask the user (do not guess): **WebUI URL the simulator can reach** (Mac/server LAN or Tailscale IP + `:8787`; `localhost` only if the WebUI runs on this Mac) and the **WebUI password** (paste into the app login field only). Confirm reachable: `curl -sS <URL>/api/auth/status` returns JSON, else stop.

Then:
```bash
git checkout main && git pull --ff-only          # your 2 commits are still local/ahead; that's fine
cd ios && ./setup.sh
xcrun simctl boot "iPhone 15 Pro"; open -a Simulator
```
Build + install + launch to the sim (GUI Cmd+R, or `xcodebuild ... -destination 'platform=iOS Simulator,name=iPhone 15 Pro' build` then `xcrun simctl install booted <.app>` + `xcrun simctl launch booted com.hermes.webui`). If it fails to build, paste the exact Swift error and stop.

Sign in. Confirm your two fixes still hold after a clean rebuild:
- **Rename a session** from its context menu → the title changes in-app AND on the server (`/api/sessions`).
- **Terminal**: Start → run `printf '\033[31mRED\033[0m done\n'` → the pane shows clean `RED done` with NO escape codes (`\033`, `[31m`, `[0m` must not appear in rendered output).
- **Title after Files/Terminal**: open a titled chat, note the title, visit Files then Terminal, return to Chat → the chat's title is shown again (not "Hermes", not a panel name).
Screenshot each.

## Step 2 — close the checks you didn't finish last pass

Run these and report PASS/FAIL + screenshot + server check where noted:

1. **Sign out → sign back in** (Settings → System → Sign out). Land on login with URL + saved password prefilled (password masked). Sign in → chat loads. Then force-quit + relaunch → session restored without re-login.
2. **Persona / "Set default"**:
   - Composer Persona chip → pick a personality (e.g. `concise`) → verify `personality` is set on the session: `/api/session?session_id=<sid>&messages=0` shows `"personality":"concise"`. Then set it back to Default → server shows it cleared/empty.
   - Model picker → open it, use search, tap **Set default** on the selected model → confirm no crash and the action fires (toast/consoles clean). (You don't need to disrupt the user's real default beyond confirming the call succeeds; note what you observe.)
3. **Files rename + move in the list**:
   - Open a session so Files has a workspace. Create `qa-ios.txt` → verify on server (`/api/list?session_id=<sid>&path=.` shows it).
   - Rename it to `qa-ios2.txt` → server `/api/list` shows the new name, old gone.
   - Create a folder `qa-ios-dir`, move `qa-ios2.txt` into it → server shows it under `qa-ios-dir/`.
   - Delete `qa-ios-dir` (recursive) → server shows it gone. Leave the workspace clean.
4. **Memory MEMORY tab**: open Memory → MEMORY tab. If it's empty, cross-check the server: `curl -b "$JAR" "<URL>/api/memory"` (or whatever the app calls — check `APIClient.swift` for the memory endpoint). If the server returns content but the tab is blank → that's a real iOS bug, fix it. If the server is genuinely empty, note "empty data, not a bug" and move on.

## Step 3 — fix the cosmetic bug you found

**Tasks timestamps wrap.** In the Tasks (cron) panel, the last-run / next-run timestamps wrap to a second line / overflow their row. Fix the layout so each job's schedule + last/next fit cleanly (e.g. `.lineLimit(1)` + `.minimumScaleFactor` or a tighter format / `.fixedSize` / truncation) without clipping meaning. Rebuild, screenshot before/after. Keep the change iOS-only and minimal.

## Step 4 — version bump + commit

Only after the above is verified:
```bash
# bump BOTH together, mirroring the Android convention
# ios/project.yml: MARKETING_VERSION 0.9.22 -> 0.9.23, CURRENT_PROJECT_VERSION 34 -> 35
```
Commit the timestamp fix (and any Memory-tab fix) as focused commits, e.g. `iOS: fix Tasks timestamp wrap` and `iOS: v0.9.23 bump`. Then:
```bash
git log --oneline origin/main..HEAD     # your original 2 + the new ones
git push origin main
```

## Step 5 — cut the v0.9.23 tag + release, attach the IPA

```bash
git tag v0.9.23
git push origin v0.9.23
```
Create the release (do NOT touch v0.9.12–v0.9.22 or their assets):
```bash
gh release create v0.9.23 --title "v0.9.23 — iOS QA fixes: Rename, Terminal ANSI, title restore, timestamp wrap" \
  --notes "iOS-only on-device QA fixes verified on a real simulator: session Rename now works (+ VoiceOver label), Terminal output has ANSI escape codes stripped, chat title is restored after visiting Files/Terminal, Tasks timestamps no longer wrap. Android already had these behaviors; this brings iOS to parity. Android APK from v0.9.22 is unchanged (no Android source changed)."
```
Archive the IPA (GUI preferred if no `ExportOptions.plist`: Product → Archive → Distribute → Ad Hoc/Development → export; rename to `hermes-webui-0.9.23.ipa`). Pick your signing Team locally — never write a Team ID into git. Then:
```bash
gh release upload v0.9.23 hermes-webui-0.9.23.ipa
gh release view v0.9.23 --json assets --jq '.assets[] | {name,size}'
```
If signing/export fails, paste the exact Xcode error and stop — do not fake an IPA.

Note: Linux will attach the Android `hermes-webui-0.9.23-debug.apk` to this same tag separately, OR you can leave the release iOS-only and let Linux add the APK. Do not build Android yourself.

## Server verification recipe (read-only)

```bash
JAR=$(mktemp); read -s PW
curl -sS -c "$JAR" -H 'Content-Type: application/json' -d "{\"password\":\"$PW\"}" <URL>/api/auth/login >/dev/null
unset PW
curl -sS -b "$JAR" "<URL>/api/sessions" | python3 -m json.tool | head
curl -sS -b "$JAR" "<URL>/api/list?session_id=<sid>&path=." | python3 -m json.tool
curl -sS -b "$JAR" "<URL>/api/session?session_id=<sid>&messages=0" | python3 -m json.tool | grep -i personality
```

## Report back

- The three `git diff` outputs from Step 0 (so Linux can confirm iOS-only, no drift).
- PASS/FAIL for each Step 1–2 check, with screenshots.
- Before/after screenshot of the Tasks timestamp fix; whether the Memory tab was a bug or just empty data.
- Final `gh release view v0.9.23 --json assets` output showing the IPA attached, and the release URL.
- Honest verdict: is the iOS app now fully 1:1-functional on device?

Do not claim anything you didn't observe. Screenshots + server reads are your evidence.
