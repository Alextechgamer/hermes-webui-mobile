# Security Policy

## Supported versions

Only the **latest GitHub release** is supported.

## Reporting a vulnerability

Please use [GitHub Security Advisories](https://github.com/Alextechgamer/hermes-webui-mobile/security/advisories/new) on this repository.

Do **not** open a public issue for:

- credential leaks
- auth bypass against a user’s hermes-webui
- remote code execution, path traversal, or CSRF issues in how this client talks to the server

Include the platform (Android / iOS), app version, and a minimal reproduction. Do not attach `.env` files, passwords, cookies, or hostnames you do not want public.

We will acknowledge the report and ship a fix in a new tagged release when one is needed.

## What this app stores (on the device only)

| Data | Android | iOS |
|---|---|---|
| WebUI URL | EncryptedSharedPreferences (falls back to private prefs) | App settings |
| WebUI password (optional, for re-login) | EncryptedSharedPreferences | Keychain |
| Session cookies | EncryptedSharedPreferences | URLSession cookie store |

Backup is disabled on Android (`android:allowBackup="false"`).

## What this app does not do

- No analytics, crash reporters, or advertising SDKs
- No hardcoded servers, IPs, or passwords
- No proxying of Hermes traffic through a third party
- Connects only to the hermes-webui URL **you** enter
- `POST /api/chat/cancel` is sent only when the user taps Stop — not when the app backgrounds

## Network

Cleartext HTTP is allowed so a LAN or VPN WebUI on `http://` works. Prefer `https://` when you expose the server beyond your own network. iOS App Transport Security uses `NSAllowsArbitraryLoads` only (required for CGNAT / VPN HTTP). Do not add `NSAllowsLocalNetworking` alongside it.

## Supply chain

Release APKs are **debug-signed sideload builds**, not Play Store artifacts. Verify the asset name and tag on [Releases](https://github.com/Alextechgamer/hermes-webui-mobile/releases) before installing. IPA files are ad-hoc / development signed by whoever archived them on a Mac — inspect the signing team before installing on a personal device.
