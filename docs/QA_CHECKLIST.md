# On-device regression checklist

Run this on a real phone (both platforms when possible) before tagging a
release. Every line traces to a user-reported bug that was once marked
"fixed" and wasn't — code review alone does not close these.

Setup: WebUI reachable at `http://<host>:8787`, auth enabled, Console at
`:8790`. Fresh install for the autofill/password rows; upgrade install for
the migration row.

## Chat core

- [ ] **Mid-run send (steer)** — start a long turn, type while busy, send.
  A STEER card appears (not a user bubble), the reply picks up the steer at
  the next tool. Composer never disables; the send arrow stays tappable.
- [ ] **Steer + attachment** — while busy, attach a photo and send with text.
  Steer text lands mid-run; the file arrives on the next turn (not dropped).
- [ ] **Stop** — the titlebar square cancels the run. Nothing else cancels:
  backgrounding the app, switching panels, and killing the drawer must all
  leave the turn running (check the server keeps streaming).
- [ ] **Background survive** — send, background the app 60s, return. Android
  shows the "Hermes is working" notification; both platforms reconnect and
  show tokens produced while away, without a duplicated or restarted turn.
- [ ] **Open at bottom** — open a long conversation. It lands on the newest
  message, no visible jump from the top. Scroll up mid-stream: no forced
  autoscroll; gold down-arrow appears, tap jumps to latest.
- [ ] **Keyboard** — composer never hidden behind the keyboard (Android
  gesture nav + button nav). Send hides the keyboard.

## Composer chips

- [ ] **Reason chip** — ladder shows Default…Max, picking one persists to the
  next turn (verify against desktop composer showing the same effort).
- [ ] **Paperclip** — picks phone photos AND arbitrary files; upload chip
  appears with remove ✕; sent turn references the file. Folder chip still
  opens workspace Files.
- [ ] **Model picker** — search filters; OpenRouter models present; picked
  model used on next turn.

## Sessions / drawer

- [ ] **Rail order** — Chat · Tasks · Kanban · Skills · Memory · Spaces ·
  Profiles · Todos · Insights · Logs · **Dashboard** · Settings. Files and
  Terminal stay off the rail (composer chips). Kanban on iOS has the same
  board switcher / search / assignee filters / add / complete / archive as
  Android — not tap-to-advance columns.
- [ ] **Conversations dropdown** — collapsed by default, `Conversations · N`
  count is the real message-bearing count (not 0), expand state survives
  app restart.
- [ ] **Session counts** — iOS drawer and Recent conversations show real
  message counts, never a wall of "0 messages".
- [ ] **History rendering** — open a session with tool calls and (if
  available) parts-array content; no raw JSON `[{"type":...}]` in bubbles.
- [ ] **iOS drawer safe area** — close ✕ fully below the Dynamic Island /
  status bar, tappable on first try.
- [ ] **Android back** — with a file open: back closes file → back to Files
  parent → back to Chat → back leaves app. Drawer open: back closes drawer.

## Auth

- [ ] **First run** — URL field empty (no prefilled host). Proton Pass /
  platform autofill offers to fill URL as username + password.
- [ ] **Password saved** — kill and relaunch: no password prompt. iOS: verify
  `webuiPassword` is NOT in UserDefaults after login
  (upgrade installs: it migrates to Keychain then is deleted).
- [ ] **Session expiry** — restart the WebUI server (fresh cookies), then tap
  a panel. App relogins silently; POSTs after relogin succeed (CSRF is
  refreshed, watch for 403s).

## Panels

- [ ] **Insights** — is Hermes Console (`:8790`): burn, plans, live streams,
  cost editors. Auto-refreshes while open. Not the thin stats page.
- [ ] **Settings** — Conversation / Appearance / Preferences / Providers /
  Plugins / Extensions / System / Help all populate against the live server.
- [ ] **Icon** — launcher shows the cyan caduceus on navy on both platforms
  (round + adaptive on Android).
