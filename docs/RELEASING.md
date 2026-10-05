# Releasing

Releases are built and published by [`.github/workflows/release.yml`](../.github/workflows/release.yml)
when a version tag is pushed.

## Cutting a release

```sh
git checkout main && git pull
git tag -a v1.2.3 -m "UniVPN 1.2.3"
git push origin v1.2.3
```

The workflow builds a signed APK, checks that it was signed with the release certificate,
and publishes a GitHub Release with `univpn-1.2.3.apk`, `SHA256SUMS.txt` and notes generated
from the commits since the previous tag.

## Version rules

The tag is the only place a version is set; there is nothing to edit in Gradle.

| Tag | versionName | versionCode | GitHub Release |
|-----|-------------|-------------|----------------|
| `v1.2.3` | `1.2.3` | `1020399` | normal |
| `v1.3.0-beta.1` | `1.3.0-beta.1` | `1030001` | pre-release |
| `v1.3.0-beta.2` | `1.3.0-beta.2` | `1030002` | pre-release |
| `v1.3.0` | `1.3.0` | `1030099` | normal |

- Format: `vMAJOR.MINOR.PATCH`, optionally `-label.N` (`beta.1`, `rc.2`, ...).
- `MINOR` and `PATCH` must be below 100; the pre-release number `N` must be 1–98.
- Pre-release numbers must keep increasing within a version regardless of label
  (`beta.1`, `beta.2`, `rc.3`, not `rc.1`), because only `N` goes into the versionCode.
- Android refuses to install a lower versionCode over a higher one, so never reuse or
  move a published tag.

## Signing

The release key never lives in the repository. The workflow reads it from these
repository secrets (Settings → Secrets and variables → Actions):

| Secret | Contents |
|--------|----------|
| `RELEASE_KEYSTORE_BASE64` | The PKCS12 keystore, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password (PKCS12 uses it for the key too) |
| `RELEASE_KEY_ALIAS` | Key alias |

Losing the keystore means existing installs can never be updated, so keep an offline backup.

## Signed build on your own machine

```powershell
$env:RELEASE_KEYSTORE_PATH = "$HOME\keys\univpn-release.jks"
$env:RELEASE_KEYSTORE_PASSWORD = Read-Host -MaskInput "Keystore password"
$env:RELEASE_KEY_ALIAS = "univpn"
.\gradlew.bat :univpn:assembleRelease "-PreleaseVersion=1.2.3"   # keep the quotes in PowerShell
```

Without `RELEASE_KEYSTORE_PATH`, `assembleRelease` produces an unsigned APK.
