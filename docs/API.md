# hermes-webui native client contract

Shared by the SwiftUI and Compose apps. Panel/API coverage is the bar,
not pixel-perfect cloning of 400k+ lines of WebUI JS.

Auth is cookie + CSRF. No bearer token.

- `GET /api/auth/status` → `{auth_enabled, logged_in, password_auth_enabled}`
- `POST /api/auth/login` `{password}` then scrape CSRF from `GET /`
- Send `X-Hermes-CSRF-Token` on every POST / PATCH / DELETE

## Chat

- `GET /api/sessions` — list. Count field is `messages` (fallback `message_count`).
- `POST /api/session/new` → `{session_id}`
- `GET /api/session?session_id=&messages=1&resolve_model=0&msg_limit=80`
  - messages live at `session.messages` or top-level `messages`
  - huge sidecars auto-tail unless `full=1`
  - `_messages_truncated` / `todo_state` may be present
- `POST /api/chat/start` **requires** `session_id` + `message` (optional `model`)
- `GET /api/chat/stream?stream_id=` SSE event `token` `{text}`
  - reconnect with `replay=1&after_seq=&after_event_id=` — do **not** treat socket close as cancel
- `GET /api/chat/stream/status?stream_id=` → `{active}`
- `GET /api/session/status?session_id=` → `{active_stream_id, message_count, agent_running}`
- `GET /api/session/stream?session_id=&known_count=` — live `server_turn_started` / `session_updated`
- `GET /api/sessions/events` — sidebar `sessions_changed`
- `POST /api/chat/cancel` `{session_id}` — **only** when the user taps Stop
- Leaving the app must not POST cancel. Android holds a dataSync foreground service while a turn is live. iOS uses a background task + reconnect on resume.
- `POST /api/session/delete` `{session_id}`
- `POST /api/session/update` `{session_id, model?}`
- Never auto-send a test message.

## Approvals / clarify

- `GET /api/approval/pending?session_id=` → `{pending, pending_count}`
- `POST /api/approval/respond` `{session_id, approval_id, choice}`
  - choice: `once` | `session` | `always` | `deny`
- `GET /api/clarify/pending?session_id=`
- `POST /api/clarify/respond` `{session_id, clarify_id, response}`

## Panels (same rail as desktop)

| Panel | GET | writes |
|---|---|---|
| Tasks | `/api/crons` `{jobs}` | `/api/crons/{run,pause,resume}` `{job_id}` |
| Kanban | `/api/kanban/board` `{columns:[{name,tasks}]}` | PATCH `/api/kanban/tasks/<id>` `{status}` |
| Skills | `/api/skills` `{skills:[{name,description,category,disabled}]}` | `/api/skills/toggle` `{name,enabled}` |
| Memory | `/api/memory` `{memory,user,soul,project_context}` | `/api/memory/write` `{section,content}` |
| Spaces | `/api/workspaces` `{workspaces,last}` | — |
| Profiles | `/api/profiles` `{profiles,active}` | `/api/profile/switch` `{name}` |
| Todos | from session `todo_state` / last tool `{todos}` | — |
| Insights | `/api/insights?days=30` | — |
| Files | `/api/list?session_id=&path=` `{entries,workspace}` | `/api/file/save` `{session_id,path,content}` |
| Terminal | SSE `/api/terminal/output?session_id=` event `output` `{text}` | `/api/terminal/{start,input,close}` |
| Logs | `/api/logs?file=agent&tail=200` `{lines}` | — |
| Dashboard | Hermes Console `GET :8790/api/usage` (derived from WebUI host, or Connection override) | — |
| Settings | `/api/settings`, `/api/models` | `POST /api/settings` |

## Files / terminal / voice

- List: `GET /api/list?session_id=&path=`
- Read: `GET /api/file?session_id=&path=` → `{path,content,size,lines}`
- Save: `POST /api/file/save` `{session_id,path,content}`
- Terminal start: `POST /api/terminal/start` `{session_id,rows,cols}`
- Terminal input: `POST /api/terminal/input` `{session_id,data}`
- Terminal stream: `GET /api/terminal/output?session_id=` SSE `output` `{text}`
- Dictate: `POST /api/transcribe` multipart `file` → `{transcript}`
- Speak: `POST /api/tts` `{text,engine}` → audio bytes

## Honest gaps

Mermaid is a **native subset** (flowchart / sequence / pie) drawn on Canvas —
not mermaid.js. Terminal strips ANSI. Voice uses the server STT/TTS endpoints
plus the device mic; it is not browser SpeechRecognition. No WebView.
