# Security

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private
vulnerability reporting instead: **Security → Report a vulnerability** on this repository.
I'll acknowledge reports as soon as I can.

## Known limitations

UniVPN is designed for a home TV on a trusted network. Know these before relying on it:

| Area | Limitation |
|------|------------|
| **Switching** | No kill switch. Traffic can briefly leave unprotected while one tunnel goes down and the next comes up. |
| **Device-wide tunnel** | Android allows one VPN at a time. The foreground app's route applies to all traffic on the device, including background apps. |
| **Key storage** | WireGuard configs, including private keys, are stored unencrypted in the app's private Room database. Provider passwords are stored separately using `EncryptedSharedPreferences`. A rooted device or a debuggable build exposes both. |
| **Web import server** | While the **Profiles** screen is open, UniVPN serves plain HTTP on port 8080. Uploads and credential submissions require a 6-digit PIN shown on the TV (new each time the screen opens; five wrong guesses lock the server for a minute), requests are limited to 64 KB, and configs must parse as WireGuard. Traffic is still unencrypted, so configs and credentials typed into it can be read by anyone able to watch your LAN traffic. Close the Profiles screen when you're done importing. |
| **Provider APIs** | Provider API calls trust the device's CA store without certificate pinning (on Android 6 that includes user-installed CAs). The PIA key-registration call is the exception: it trusts only PIA's own CA and checks the server hostname. |
| **Logging** | The app logs tunnel and provider activity to logcat, including server addresses, in all build types. Anyone with ADB access to the device can read it. |

Fixes for the key storage and pinning items are on the roadmap.
