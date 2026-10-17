# Play Store listing text (en-US)

## App name (30 max)
UniVPN

## Short description (80 max)
Automatic per-app VPN switching for Android TV. Set a location per app.

## Full description (4000 max)
Pick a VPN location for each app once (BBC iPlayer → UK, Netflix → US, YouTube → no VPN), and UniVPN switches your WireGuard tunnel whenever that app comes to the foreground. No more opening a VPN app and changing servers every time you change what you're watching.

• Per-app routing: give each app a VPN profile, no VPN, or passthrough (keep whatever tunnel is already up)
• Mullvad and Private Internet Access accounts: UniVPN generates WireGuard configs for the servers you choose and refreshes them automatically
• Any WireGuard config: import .conf files from any provider or your own server
• Easy setup with a remote: open the Profiles screen, then visit the address shown from a phone or laptop on your network and enter the one-time PIN to upload configs or sign in
• Optional status overlay showing the active profile and latency
• Optional start on boot; routing resumes after standby
• Built for D-pad remotes and tested on the NVIDIA Shield TV
• Free and open source (GPLv3), with no ads, analytics or tracking

UniVPN needs usage access to see which app is in the foreground, and the installed-apps list so you can choose a route for each app. This information never leaves your device.

UniVPN is not affiliated with Mullvad or Private Internet Access.

## Release notes (internal testing, 500 max)
<en-US>
First Google Play test build of UniVPN, a per-app VPN for Android TV.

- Choose which apps use the VPN and which bypass it
- WireGuard tunnels, with Mullvad and Private Internet Access (PIA) support
- Import configs from your phone or computer via a web page
- Designed for D-pad remotes

Please report problems: connection drops, apps that bypass the VPN when they shouldn't, and anything hard to use with a remote.
</en-US>

## Graphics in this folder
- icon-512.png: app icon, 512x512
- feature-graphic-1024x500.png: feature graphic
- tv-banner-1280x720.png: Android TV banner
- screenshots/: five 1920x1080 TV screenshots captured from an NVIDIA Shield (the web-import
  address and PIN on the Profiles screen are replaced with placeholder values)
