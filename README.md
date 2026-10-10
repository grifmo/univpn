# UniVPN

**Automatic per-app VPN switching for Android TV.**

Pick a VPN location for each app once (BBC iPlayer → UK, Netflix → US, YouTube → no VPN), and
UniVPN switches the WireGuard tunnel whenever that app comes to the foreground. No more
opening a VPN app and changing servers every time you change what you're watching.

Built for and tested on the NVIDIA Shield TV. It targets Android TV devices running
Android 6.0 or later; the UI is designed for a D-pad remote.

## Features

- **Per-app routing.** Each app gets a route:
  - **a VPN profile**: switch to that tunnel when the app opens
  - **No VPN**: drop the tunnel
  - **Passthrough**: keep whatever tunnel is already up (useful for launchers, keyboards, settings)
- **Sensible defaults.** New streaming/audio apps start on *No VPN* and everything else on
  *Passthrough*, so the tunnel isn't torn down every time you visit the home screen.
- **Provider accounts.** Sign in with **Mullvad** or **Private Internet Access** and UniVPN
  generates WireGuard configs for the servers you choose. Configs older than 7 days are
  refreshed automatically.
- **Any WireGuard config.** Import `.conf` files from any WireGuard provider or your own server.
- **Web import.** No typing passwords with a remote: open the Profiles screen and UniVPN
  shows an address (e.g. `http://192.168.1.20:8080`) you can visit from a phone or laptop
  on the same network to upload configs or enter provider credentials.
- **Status overlay.** Optional on-screen chip showing the active profile and latency,
  toggleable from the notification.
- **Survives reboots and standby.** Optional start on boot; routing resumes when the
  device wakes up.

## How it works

`VpnSwitcherService` polls `UsageStatsManager` for foreground app changes. When the
foreground app's route needs a different tunnel, it tears down the current WireGuard
tunnel and brings up the new one using the official WireGuard Go backend.

Things to know:

- **The tunnel is device-wide.** Android allows one VPN at a time, so while an app is in the
  foreground, *all* traffic on the device, including background apps, uses that app's route.
- **There is no kill switch.** While one tunnel is going down and the next is coming up,
  traffic can briefly leave without a VPN. Don't rely on UniVPN where that matters.

## Install

There is no Play Store release yet. Download the latest APK from
[Releases](https://github.com/grifmo/univpn/releases) (or [build it yourself](#build)),
then sideload it:

```sh
adb connect <device-ip>:5555          # or connect over USB
adb install univpn-<version>.apk
```

To get updates automatically, add `https://github.com/grifmo/univpn` to
[Obtainium](https://github.com/ImranR98/Obtainium).

### Verifying downloads

Every release APK is signed with the same key. Its certificate's SHA-256 fingerprint is:

```
60:3B:11:16:39:BC:93:88:C2:EB:AC:2B:4D:6B:4C:BB:1E:8F:E7:86:1D:6C:E4:78:C3:0C:05:7D:15:6D:52:5D
```

Check an APK with `apksigner` from the Android SDK build-tools:

```sh
apksigner verify --print-certs univpn-<version>.apk
# Signer #1 certificate SHA-256 digest: 603b111639bc9388c2ebac2b4d6b4cbb1e8fe7861d6ce478c30c057d156d525d
```

Each release also includes `SHA256SUMS.txt` for checking the download itself.

### Grant permissions

UniVPN needs **usage access** to see which app is in the foreground. Android TV often hides
this setting, so grant it with ADB:

```sh
adb shell appops set com.univpn.app GET_USAGE_STATS allow
```

On the Shield you can also use *Settings → Device Preferences → Security & restrictions →
Usage access*. For the optional status overlay:

```sh
adb shell appops set com.univpn.app SYSTEM_ALERT_WINDOW allow
```

On first launch Android asks you to approve UniVPN as a VPN. Accept it.

## Quick start

1. Open **Profiles**. Add a provider account, or note the web import address and upload a
   WireGuard `.conf` from another device.
2. Open **App Routes**, select an app, and pick the profile it should use.
3. Launch that app. The tunnel switches automatically.

## Build

Requirements: the Android SDK (platform 34) and an internet connection. Gradle downloads
the JDKs it needs (21 for the daemon, 17 for compilation) if you don't have them.

```sh
# Point Gradle at your SDK, either via ANDROID_HOME or local.properties:
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :univpn:assembleDebug
# → univpn/build/outputs/apk/debug/univpn-debug.apk
```

Instrumented tests (needs a connected device or emulator):

```sh
./gradlew :univpn:connectedDebugAndroidTest
```

## Project layout

```
univpn/src/main/kotlin/com/univpn/app/
├── service/    VpnSwitcherService (foreground watcher + tunnel switching), status overlay
├── tunnel/     TunnelManager interface, WireGuard implementation
├── provider/   Mullvad / PIA connectors, credential store, config freshness
├── data/       Room database, app routes, default-route seeding
├── server/     LAN web import server (NanoHTTPD)
├── receiver/   Boot, package-removed, overlay toggle
└── ui/         Leanback TV UI
```

UI design notes and the visual design system live in [docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

- Certificate pinning for provider APIs
- More providers (NordVPN connector exists but is disabled; Surfshark)
- Play Store release

Contributions are welcome. Please open an issue to discuss larger changes first.

## Security

See [SECURITY.md](SECURITY.md) for known limitations and how to report vulnerabilities.

## Licence

UniVPN is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version (SPDX: `GPL-3.0-or-later`).

UniVPN is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

Bundled fonts and other third-party components are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

UniVPN is not affiliated with WireGuard, Mullvad, Private Internet Access or NVIDIA.
WireGuard is a registered trademark of Jason A. Donenfeld.

## Known issues (v0.9.x)

- **Mullvad profiles generated in the app stop working after 7 days.** The automatic refresh writes a server address that can't be resolved, so the tunnel fails to start.
- **A failed connection still shows the profile as active.** If a tunnel can't start, UniVPN keeps displaying the profile as active and doesn't retry. Traffic then leaves the device unprotected.

Together, these mean a Mullvad profile can look connected after a week while nothing is protected.

**Workaround:** Download a WireGuard `.conf` from your Mullvad account and import it instead of generating the profile in UniVPN. Imported configs are never refreshed. With any provider, check your IP address after switching apps if it matters. The optional overlay is helpful for this - showing as it does the currently selected profile and the external IP address assigned.

Other open issues from a community review (undetected tunnel drops, the 10-second recovery window, the "Default" route disconnecting, and the unauthenticated import server) are tracked for upcoming releases.
