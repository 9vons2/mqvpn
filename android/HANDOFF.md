<!--
  Internal working notes for the android-client feature branch.
  Not shipped anywhere; safe to delete before a squash-merge.
-->
# Android client — handoff notes

Branch: `claude/android-client-changes-omjrcc` (fork `9vons2/mqvpn`, PR #1 → `main`).
Last verified green CI: run for commit `a5eaf78` (android job built + uploaded
`mqvpn-debug-apk`). Working tree clean, in sync with origin.

## What this branch adds (all on top of upstream v0.9.0)
- Hybrid TCP-lane enabled for Android (CMake/JNI/SDK/UI + per-lane stats)
- Ukrainian localization (`values-uk/`), all strings externalized
- Persisted settings + boot auto-start (`BootReceiver`, `SettingsRepository`)
- Setting tooltips (ⓘ), battery-optimization prompt, notification "Disconnect",
  Quick Settings tile (`MqvpnTileService`)
- Split tunneling (`excludedApps` → `addDisallowedApplication`) with an
  icon+search app picker
- Server profiles (save/switch/delete)
- Trusted Wi-Fi **pause/resume** (join trusted SSID → pause; leave → resume)
- QR export / paste import of config
- SNI camouflage field (`tlsServerName`)

## Guardrails — READ BEFORE ACTING
1. **Do NOT mass-trigger CI.** One `workflow_dispatch` is enough per change,
   and because PR #1 is open every dispatch already spawns a duplicate
   `pull_request` run — so one dispatch = two runs. Never trigger in a loop.
   Confirm a build passed by checking whether the run produced the
   `mqvpn-debug-apk` artifact, not by re-running.
2. **Do NOT force-push or reset this branch.** It carries unmerged work.
   Only fast-forward/normal pushes. (Force-with-lease is allowed *only* if the
   PR has already merged and you are restarting from `origin/main`.)
3. **Do NOT open a new PR** — PR #1 already tracks this branch.
4. **No self-scheduled wakeup loops.** If you must wait on CI, check once; do
   not re-arm a trigger that itself says "restart workflow and re-check".
5. Kotlin can't be compiled in this container (no Android SDK/NDK — the proxy
   blocks the Google download). Validate via: `xmllint --noout` on the two
   `strings.xml`, import/usage cross-checks, and CI. Don't try to install SDK.

## Known-safe verification recipe
- `git status` clean + `git rev-list --left-right --count origin/<branch>...HEAD`
  should be `0  0`.
- After a push, one `run_workflow` on `ci.yml`; then check
  `list_workflow_run_artifacts` for `mqvpn-debug-apk`.

## Open follow-ups (not started; ask the user first)
- Hide the home server IP: needs infra (VPS relay or CDN in front), not an app
  change. User is interested — offer a `socat`/nftables DNAT or thin QUIC relay
  guide, don't build silently.
- Optional: surface a distinct "Paused (trusted Wi-Fi)" state in the UI (today
  only the notification shows it; the button still reads "Connect").
