# Privacy Policy

**UniVPN** is a free, open-source (GPLv3) app that switches WireGuard VPN tunnels per app on
Android TV. Last updated: 16 October 2026.

**Short version:** UniVPN has no servers, no analytics, no advertising and no tracking. The
developer receives no data from your device.

## What the app stores on your device

- Your VPN profiles (WireGuard configurations, including private keys).
- Your per-app routing choices.
- Credentials for VPN provider accounts you add (a Mullvad account number or a Private Internet
  Access login) and the tokens those providers return. Credentials are kept in Android's
  encrypted storage.

This data stays on your device. Clearing the app's data or uninstalling the app deletes it.
The app does not back up to the cloud (`allowBackup` is off).

## What the app sends over the network

UniVPN contacts only the following, and only because you asked it to:

- **Your VPN provider** (Mullvad or Private Internet Access, if you add an account): your
  credentials, to sign in, register a WireGuard key and fetch server lists. Their handling of
  that data is covered by their own privacy policies.
- **The VPN server you connect to:** your normal traffic goes through the WireGuard tunnel you
  configured.
- **api.ipify.org:** a plain request to look up your public IP address, used to show whether
  the tunnel is working. It sends no account or device identifiers.
- **Your local network:** the optional web-import page runs a small web server on your TV,
  protected by a one-time PIN, and is reachable only from your local network.

UniVPN does not sell or share your data, and the developer has no access to it.

## Permissions

- **Usage access, and the list of installed apps:** to know which app is in the foreground and
  to let you choose a route for each app. This information is not sent anywhere.
- **Display over other apps:** for the optional status overlay.
- **VPN service:** to create the VPN tunnel.
- **Notifications and run on boot:** for the foreground service and the optional start-on-boot
  setting.

## Children

UniVPN is not directed at children under 18.

## Changes and contact

Changes to this policy will be published in this file and recorded in the repository history.
Questions or concerns: open an issue at <https://github.com/grifmo/univpn/issues>.
