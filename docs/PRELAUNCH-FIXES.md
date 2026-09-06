# hermes-webui-mobile — required fixes

**Repo:** `/home/alex/workspace/hermes-webui-mobile` → `Alextechgamer/hermes-webui-mobile` public  
**Branch:** `main` clean · tag **v1.0.0** (2026-08-31)  
**Not a money product.** Stay 1:1 with WebUI. Do not fold in `:9119` Dashboard.

PORTFOLIO-REVIEW.md §6 is **stale** (still says v0.9.14, Insights=:8790, Dashboard=:9119).

---

## Do not do

- Re-add official Dashboard `:9119` (removed v0.9.16; own app later)
- Wire Insights back to `loadConsole()` / `:8790`
- Put personal IPs, Tailscale, `/home/alex`, passwords in git
- Claim a store/IPA release that is not on the tag
- Mutate this repo into a custom-design Android app (separate product)

---

## High

### H1 — Insights 4s poll hits Console · agent
`AppVm.kt` Insights **loader** is correct (`c.insights(days)` → `GET /api/insights`, ~296–299).  
Poll loop `AppVm.kt:1409`: `if (panel.value == Panel.Insights) loadConsole()` — wrong. Console is Settings → System (`:8790`).

**Fix:** poll Insights with `c.insights(insightsDays)`, not `loadConsole()`. Confirm iOS poll does not have the same swap (`loadConsole` only when `panel == .console`).

### H2 — v1.0.0 asset is a debug APK · owner
GitHub release v1.0.0 asset: `hermes-webui-1.0.0-debug.apk` only. **No IPA.** Debug ≠ store.

**Fix:** if 1.0.0 is the public claim: ship a non-debug APK on that tag (or a new tag) and Mac/Grok IPA **same tag**. Owner rule: always release (tag+APK; IPA same tag).

---

## Already done (do not redo)

- Insights contract in README/API.md = `GET /api/insights` (not :8790)
- Console = Settings → System `:8790`
- `:9119` panel removed; `docs/RELEASING.md` checks OfficialDashView absent
- Public 1.0.0 tag exists

## Next action

**Agent (later):** H1 poll. **Owner:** H2 whether debug APK is the intended public artifact until a Mac IPA exists.
