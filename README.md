# 5G / 4G Switch — Moto Edge 40 (no ADB, no root, no PC)

## Install
1. Open `Moto-5G-4G-Switch.apk` on your Moto Edge 40.
2. If asked, allow "Install unknown apps" for your browser / Files app (one-time Android prompt for sideloading).
3. Install and open **5G 4G Switch**.

The app asks for **no permissions**, has no internet access, no ads, and is only 21 KB.

## Use — fully automatic (v3)
1. Open the app and tap **ENABLE AUTO-TAP (ONE-TIME)**.
2. In Accessibility settings: **Installed apps → 5G / 4G Auto-Tap → ON → Allow**.
3. Come back to the app — the status should say **AUTO-TAP: ON**.

From now on:
- Tap **5G ONLY** → the app opens Phone Information, opens the dropdown and selects **NR only** by itself. You'll see a "Done: 5G Only selected" toast.
- Tap **4G ONLY** → same, selects **LTE only**.
- Tap **AUTO (DEFAULT)** → same, selects the default NR/LTE/... auto mode.

The auto-tap service only acts within 60 seconds of you tapping a mode button, and only on the Phone Information screen. It does nothing anywhere else. If auto-tap ever can't find the dropdown (e.g. after a firmware update changes the screen), the screen stays open and you can still select manually as in v1.

## Use — manual fallback (Auto-Tap off)
- Tap **5G ONLY** → tap **Open Phone Info >** → scroll to **Set preferred network type** → select **NR only**
- Tap **4G ONLY** → tap **Open Phone Info >** → scroll to **Set preferred network type** → select **LTE only**
- Tap **AUTO (DEFAULT)** to undo → select the long option starting **NR/LTE/...**

### Make it 1 tap to open
- **Widget:** Long-press home screen → Widgets → *5G / 4G Switch* → drag it to your home screen. Its buttons open the app straight in the right mode.
- **Quick Settings tile:** Swipe down twice → pencil/edit icon → add the *5G / 4G Switch* tile.

### Dual SIM
On the Phone Information screen, use the SIM / phone-index selector at the top if present. If your firmware instead shows a "Testing" list, open **Phone information1** or **Phone information2** for the SIM you want.

## Why can't an app do it in exactly 1 tap with no second screen?
Android reserves changing the preferred network type for system apps (`MODIFY_PHONE_STATE` signature permission). Without root or ADB/Shizuku, no normal app — this one or any Play Store "Force LTE" app — can set NR only / LTE only silently. They all open this same hidden Phone Information screen. This app removes everything else: no dialing `*#*#4636#*#*`, no Testing menu, no ads.

## Warnings
- **5G Only (NR only):** if 5G coverage drops, you get *no signal* instead of falling back to 4G. Calls need VoNR — Jio True5G supports it in most areas; on Airtel, incoming/outgoing calls may fail in NR-only mode. Switch back to AUTO if people say you're unreachable.
- **4G Only (LTE only):** calls keep working on VoLTE; you just lose 5G speeds.
- A reboot or SIM change can reset the setting on some builds — just re-apply it.

## No-install fallback (same thing, by hand)
Dial `*#*#4636#*#*` in the stock Phone app → **Phone information** → **Set preferred network type** → **NR only** / **LTE only**.
