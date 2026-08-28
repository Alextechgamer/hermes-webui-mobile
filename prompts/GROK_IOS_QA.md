# Grok on Mac — on-device iOS QA pass for hermes-webui-mobile (v0.9.22)

Paste everything below the line into Grok on the Apple Silicon Mac. It has Xcode + iOS Simulators and can see the screen.

---

You are on Apple Silicon macOS with Xcode and iOS Simulators. Your job is a **real on-device QA pass of the native SwiftUI iOS app** in `Alextechgamer/hermes-webui-mobile`, exercising every user flow against a **live** Hermes WebUI, finding bugs, fixing the iOS-side ones, re-verifying, and committing. This mirrors an Android QA pass already done on Linux — but the **iOS fixes from that pass were written blind (Linux cannot compile Swift), so your #1 job is to confirm they actually work on iOS, then hunt for iOS-only bugs.**

Repo: https://github.com/Alextechgamer/hermes-webui-mobile — branch `main`, current tag `v0.9.22`.

## Absolute rules

1. **This is NOT a WebView.** Never add `WKWebView`/`UIWebView`/`SFSafariViewController` as product UI. Screens are native SwiftUI over REST/SSE.
2. **No secrets in git, ever.** Do not commit or print the WebUI password, and do not bake any hostname, Tailscale/LAN IP, MagicDNS name, `/Users/...`, `/home/alex`, or Apple Team ID into source. The WebUI URL is entered at runtime in the app's login screen only.
3. **Report honestly.** Never fake a screenshot, a passing check, or a server response. If something is broken, say so with the exact symptom. A real "3 flows are broken" beats a fabricated "all green."
4. **Verify side effects against the server, not just the UI.** When you pin/rename/delete/create, confirm it actually changed by re-reading the WebUI API (see "Server verification" below) — the UI updating is not proof the server changed.
5. If a real bug is in the iOS source, **fix it, rebuild, re-verify on device, and commit** with a clear message. If a bug is server-side or environmental, report it and move on — don't hack around it.

## Get what you need from the user first

Ask the user for, and do not guess:
- **WebUI base URL** the simulator can reach (e.g. their Mac's LAN IP + `:8787`, or a Tailscale address). The iOS simulator shares the Mac's network, so `http://<mac-or-server-ip>:8787` must be reachable. `localhost` only works if the WebUI runs on this same Mac.
- **WebUI password** (paste it into the running app's login field only — never into a file or git).
Confirm the WebUI is actually up: `curl -sS <URL>/api/auth/status` should return JSON. If it doesn't, stop and tell the user the server isn't reachable from this Mac.

## Build + boot

1. `git fetch && git checkout main && git pull --ff-only`. Confirm `ios/project.yml` shows `MARKETING_VERSION: "0.9.22"`.
2. `cd ios && ./setup.sh` (runs XcodeGen). If `setup.sh` is missing, `xcodegen generate` then open.
3. Boot a modern simulator, e.g.:
   ```bash
   xcrun simctl list devices available | grep iPhone
   xcrun simctl boot "iPhone 15 Pro"   # or any available iPhone
   open -a Simulator
   ```
4. Build + install to the booted sim (GUI: pick the simulator, Cmd+R). Or CLI:
   ```bash
   xcodebuild -project ios/HermesWebUI.xcodeproj -scheme HermesWebUI \
     -destination 'platform=iOS Simulator,name=iPhone 15 Pro' \
     -configuration Debug -allowProvisioningUpdates build
   xcrun simctl install booted <path-to-.app-from-DerivedData>
   xcrun simctl launch booted com.hermes.webui
   ```
   If the build fails, report the **exact** Swift compiler error and stop — that itself is a finding (the iOS source was patched without a Swift compiler available, so a syntax/type error is plausible and valuable to catch).
5. Sign in: enter the URL, paste the password, tap Sign in. Confirm the chat screen loads with real sessions.

Tools for driving/seeing the sim: take screenshots with `xcrun simctl io booted screenshot /tmp/shot.png` and look at them; inspect the view tree with Xcode's Accessibility Inspector or `xcrun simctl` UI automation; tap via the Simulator UI or an XCUITest if you prefer. Use whatever lets you *see and verify* each screen.

## PRIORITY: confirm the 5 fixes from the Android QA pass actually work on iOS

These were fixed in iOS source without ever being compiled/run. Verify each on device:

1. **CLI session tab filters correctly.** Open the conversations drawer → tap the **CLI** tab. It must show ONLY CLI sessions (the count on the chip, e.g. "CLI (5)", must match the number of rows shown), NOT the full session list. The WebUI server ignores `?source=cli`, so the app must filter client-side on `is_cli_session`. If the CLI tab shows every session, that's the bug regressing.
2. **No chat-stream bleed across sessions.** Open session A, send a message so it's streaming/working, then WITHOUT waiting, open a different session B (or tap New conversation). B must NOT show A's "working…" banner, A's streaming tokens, or A's tool bubbles. Then reopen A — it should re-attach to its live turn. Confirm both directions.
3. **Send button always visible.** In the composer, the row of chips (model, Reason, YOLO, Persona, Compress…) scrolls horizontally, but the **send arrow (and STEER label when busy) must stay pinned on the right and never scroll off**. Try scrolling the chips fully — send must remain tappable.
4. **Sidebar order = server recency, pinned lifted.** Send a message in a brand-new conversation; it should appear at/near the **top** of the conversation list (most-recent-first), not sorted by message count. Pin a session → it jumps above unpinned ones while the rest keep recency order.
5. **Deleting the current session doesn't strand the app.** With a session open, delete it from its overflow/context menu. The app must fall back to the most recent remaining session (not sit on a dead `session_id`). Immediately after, open **Files** or **Terminal** — they must work (a stranded/dead session id makes those calls 404 "Session not found"). Confirm Files lists the workspace after the delete.

Report each as PASS / FAIL with a screenshot. Any FAIL is an iOS-source bug to fix.

## Full sweep (match the Android pass)

Exercise and verify each, with screenshots and server checks where noted:

- **Cold start + persisted login**: force-quit and relaunch — it should restore the session without re-login.
- **Drawer**: WebUI/CLI tabs + counts, filter box (hits `/api/sessions/search`), project chips (All + projects + new project), Import JSON.
- **E2E chat**: New conversation → send `Reply with exactly: pong` → confirm the streamed reply is `pong` and the session auto-titles. (This spawns a real agent turn — that's fine, it's one cheap turn. Don't spam it.)
- **Session context menu**: pin/unpin, archive, rename, duplicate, share link, export JSON/HTML/Markdown (iOS share sheet appears with the right file), branch, clear, retry/undo/regenerate-title, move-to-project, delete. Verify pin/rename/delete against the server.
- **Files CRUD**: create file, create folder, rename, move into a folder, delete — verify each on the server via `/api/list` (paths actually change).
- **Terminal** (Settings → System → Terminal): Start → run `echo qa-$((21+21))` → confirm `qa-42` streams back → set cols and Resize (run `stty size` to confirm) → Stop.
- **All rail panels**: Tasks (human schedules, status chips, Run/Pause/Output/Edit/history, running badge, + New job), Kanban (board switcher, search, assignee filter, add/complete/archive, bulk, new board), Skills (search + categories + New skill), Memory (MEMORY/USER/SOUL tabs), Insights (7/30/90/365 chips, numbers match `/api/insights`), Logs (file tabs, tail 100/200/500/1000, All/Errors/Warnings, Copy), Todos, Spaces (add, ↑/↓ reorder, remove, path suggestions), Profiles (new/switch/delete).
- **Settings**: search filter (type a word, rows narrow, humanized label + raw key), Providers (Set key / Refresh models / Remove key), Plugins + MCP servers section, Extensions (Enable/Disable/Uninstall + Gallery Install), System (Health card cpu/mem/disk/agent/gateway/sessions/streams, Sign out, Updates check/apply).
- **Composer chips**: YOLO on/off round-trip, Persona set then clear (verify `personality` on the session via the server), model picker search + "Set default".
- **iOS-specific things to watch**: drawer close ✕ fully below the Dynamic Island and tappable first try; safe-area insets; keyboard doesn't cover the active field; context menus (long-press) work; no SwiftUI layout warnings spamming the console; smooth on a notch device.
- **Sign out → sign back in** with the saved password.
- **Stability**: watch the Xcode console for crashes, `Fatal error`, purple runtime issues, or constraint/layout spam during the whole pass.

## Server verification (read-only, to confirm side effects)

Use a cookie jar so you can re-read state after actions (run from Terminal, never store the password in a file):
```bash
JAR=$(mktemp)
read -s PW               # paste password, it won't echo; do not save it anywhere
curl -sS -c "$JAR" -H 'Content-Type: application/json' \
  -d "{\"password\":\"$PW\"}" <URL>/api/auth/login >/dev/null
unset PW
curl -sS -b "$JAR" "<URL>/api/sessions" | python3 -m json.tool | head
curl -sS -b "$JAR" "<URL>/api/list?session_id=<sid>&path=." | python3 -m json.tool
```
Confirm: a pin shows `pinned:true` on that row; a rename/move changes the path in `/api/list`; a delete removes it from `/api/sessions`; a personality set shows `personality` on `/api/session?session_id=<sid>`.

## When you find a bug

- If it's in the iOS Swift source: fix it, rebuild, re-run that specific check on the sim until it passes, `git add -A && git commit -m "iOS QA: <what you fixed>"`. Keep commits focused and honestly described.
- If build/version bumping is needed, mirror the Android convention (bump `MARKETING_VERSION` + `CURRENT_PROJECT_VERSION` together) — but only if you're shipping a new tag, and ask the user before creating tags/releases.
- Do NOT touch Android source, do NOT retag existing releases, do NOT commit any file containing the password or an IP.

## Report back

Give the user:
- One line per priority check (5 fixes): PASS/FAIL + screenshot.
- A table of the full sweep: area → working / broken (with the exact symptom).
- Every bug found, whether you fixed it, and the commit hash if you did.
- Whether the app crashed or logged runtime issues at any point.
- A final honest verdict: is the iOS app actually 1:1-functional on device, or not yet?

Do not claim success you didn't observe. Screenshots and server reads are your evidence.
