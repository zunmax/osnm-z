# Fork notes — Android port

This fork is `alawymuhsin/osnm-z`, forked from `zunmax/osnm-z`.
Credit for the original CLI remains with its author (see upstream
`README.md` and `LICENSE` at the repo root, untouched by this branch).

## What this branch adds

- `android/` — a native Kotlin Android port of the osnm-z CLI, with
  multi-job support: one ForegroundService manages many jobs, each job
  has its own config, wallet, RPC and gas settings.
- The upstream Python CLI files at the repo root are NOT modified by
  this branch. The Android code lives only under `android/`.

## Build (from the `android/` folder)

Normal machine (Gradle):

```bash
export ANDROID_HOME=$HOME/Android/Sdk
cd android
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Security

- No private key, `.env`, keystore or APK binary is committed here.
- Private keys are only entered on the phone itself and stored
  encrypted with Android Keystore. See `android/README.md`.
- APKs are distributed separately (renamed per update, e.g.
  `osnm-z-multijob-debug-0.1.1.apk`), not committed to this repo.

## Status

- Lookup + eligibility (SIWE) verified on a real phone 2026-11-03.
- Real mint execution had not yet been tested at the time of this push.
