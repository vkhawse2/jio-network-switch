# 5G / 4G Switch — Moto Edge 40 (no ADB, no root, no PC)

One-tap network switching for the Motorola Edge 40, tested on-device.
Version 22. No permissions, no internet access, no ads.

## What it does

- **5G ONLY** — sets the preferred network type to NR only.
- **4G ONLY** — sets it to LTE only.
- **AUTO (NORMAL)** — restores the normal auto mode.
- **TURN ON n78 BAND** — first sets 5G Only, then disables band 28 and enables band 78, for both SA and NSA (the Jio / Airtel 5G band).
- **TURN ON n28 BAND** — the reverse: first sets 5G Only, then disables band 78 and enables band 28, for both SA and NSA.
- **RESET EVERYTHING** — enables bands 28 and 78 (SA and NSA), then switches the network back to Auto.

Every workflow ends back on the home screen once its last step is confirmed.
Quick Settings tiles are available for 5G/4G, n78, n28, Reset All and the
Testing Menu (use **ADD SHORTCUT TILES** in the app), plus a home-screen widget.

## Install (from the APK)

1. Open the built `Moto-5G-4G-Switch.apk` on your Moto Edge 40.
   (The APK is a build output — it is not stored in this repo.)
2. If asked, allow "Install unknown apps" for your browser / Files app
   (one-time Android prompt for sideloading).
3. Install and open **5G 4G Switch**.

## One-time setup

1. Open the app and tap **ONE-TIME SETUP**.
2. In Accessibility settings: **Installed apps → 5G / 4G Auto-Tap → ON → Allow**.
3. Come back to the app — the status should say the auto-tap is on.

From then on, just tap a button and wait a few seconds for the "Done" toast.
If a run stalls, tap **COPY LOG** in Help and share the log — it records
every step the auto-tap took.

## How it works

Android only lets system apps change the preferred network type silently,
so the app opens the hidden Phone Information screen and selects the mode
for you with an accessibility auto-tap. The band flows open the Testing /
Debugging screens the same way (the app types `*#*#4636#*#*` in the dialer
itself — it never places a call). The auto-tap only acts on the Phone
Information / Testing screens, and stops the moment you leave them.
There is a **STOP** button in the app to cancel a run.

## Warnings

- **5G Only (NR only):** if 5G coverage drops, you get *no signal* instead
  of falling back to 4G. Calls need VoNR — Jio True5G supports it in most
  areas; on Airtel, calls may fail in NR-only mode. Switch back to AUTO if
  people say you're unreachable.
- **4G Only (LTE only):** calls keep working on VoLTE; you just lose 5G speeds.
- A reboot or SIM change can reset the setting on some builds — just re-apply it.

## Build from source

`build.sh` is a plain script (no Gradle): it compiles the resources and
Java with `aapt2` / `javac` / `d8`, then aligns and signs the APK
(minSdk 26, targetSdk 34, versionCode 22).

It expects a JDK at `tools/jdk` and an Android SDK at `tools/sdk`
(build-tools 34.0.0, platform android-34). Those are downloaded toolchains
and are **not** in this repo (see `.gitignore`) — place them under `tools/`
first, then run:

```
./build.sh
```

Signing: if `keystore.jks` is missing, `build.sh` creates one. The keystore
is git-ignored and must never be committed — keep it private, or updates
signed with a different key won't install over the old app.

## No-install fallback (same thing, by hand)

Dial `*#*#4636#*#*` in the stock Phone app → **Phone information** →
**Set preferred network type** → **NR only** / **LTE only**.
