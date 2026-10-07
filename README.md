# ZAY WiFi Android

Single-APK port of the Termux/root captive-portal project.

## What is implemented
- Native Android admin UI.
- Root request through `su` (Magisk/SuperSU compatible when `su` is exposed).
- Captive portal HTTP server on port 8080, replacing the PHP runtime.
- Original ZAY WiFi portal visual design retained from the supplied project.
- Voucher generation: 6-character automatic codes using an unambiguous alphabet.
- Custom voucher codes: 4-32 chars, A-Z/0-9/-.
- Voucher list, delete, reset-capable data model.
- Authorized-client list and disconnect/revoke.
- iptables captive firewall setup/cleanup.
- One-voucher-to-one-IP binding.
- Android/iOS/Windows/Firefox connectivity probe endpoints.

## Important Android limitation
The app does not rely on Termux. It assumes the phone's Android hotspot/tethering is already enabled and that the rooted device exposes `su` and `iptables`. Android OEMs differ in how tethering is controlled; therefore this build does not pretend that one universal hidden command can safely turn hotspot on across every ROM.

## Build
Open this folder in Android Studio and sync Gradle. Build the debug APK from `app`.

The environment used to prepare this source did not contain an Android SDK/Gradle installation, so an APK binary could not be truthfully claimed as compiled here.
