# Changelog

All notable user-facing changes live here. Versions are `versionName` /
`MARKETING_VERSION` (Android `versionCode` and iOS `CURRENT_PROJECT_VERSION`
stay equal).

## 1.0.0 — 2026-08-30

First **public** cut of the native iOS + Android clients.

- Public README, contributing / security / CoC, issue + PR templates, Android CI
- Operator-only Mac agent prompts removed from the tree
- No personal hosts, IPs, or passwords in source (first-run URL is empty)
- Feature-complete relative to hermes-webui rail panels as of v0.9.23
- Android `versionCode` **36** / iOS `CURRENT_PROJECT_VERSION` **36**

iOS IPA is attached to the same GitHub tag from a Mac; Linux ships the APK.

## 0.9.23

iOS on-device QA: session Rename + VoiceOver Attach, Terminal ANSI strip,
chat title restore after Files/Terminal, Tasks timestamp wrap, Memory tab
fill, sign-out password refill. Android version bump only.

## 0.9.22

QA pass, both platforms: CLI filter is client-side (`is_cli_session`), chat
stream detaches on session switch, send + STEER pinned outside the chip
scroller, sidebar keeps server recency (pinned lifted), delete-current falls
back to the next session.

## 0.9.21

Personalities, Compress, Set default model, provider refresh/delete,
extensions gallery, Tasks running badge, Spaces reorder, System Health.

## 0.9.20

Session export JSON/HTML/Markdown, import, branch, session search. Cron edit
+ output history. MCP list. Files Move. Terminal rows/cols + resize.

## 0.9.19

Files CRUD, slash commands, retry / undo / YOLO / regenerate title, sign out,
Updates check/apply.

## 0.9.18

Session projects, pin / archive / rename / duplicate / share / clear / delete.
Kanban new board + bulk status. Settings search.

## 0.9.17

Insights = `GET /api/insights`. Console `:8790` under Settings → System.
Tasks, Skills, Logs, Spaces, Profiles, Conversations tabs, empty-chat starters.

## 0.9.16

Official Dashboard (`:9119`) panel removed from both apps.

## 0.9.0 – 0.9.15

Native chat SSE, Files, Terminal, voice, Settings, login/Keychain, Insights,
Kanban, composer chips, CSRF retry, caduceus icon.

## 0.5.0 – 0.8.1

Early native chrome: live chat, panels, back swipe, session list, Console.

## 0.1.0 – 0.2.0

First Android debug builds.
