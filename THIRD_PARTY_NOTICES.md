# Third-party notices

UniVPN is licensed under the GNU General Public License, version 3 or (at your option) any later version (`GPL-3.0-or-later`; see [LICENSE](LICENSE)).
It bundles or links the following third-party components, each under its own licence.

## Bundled in the source tree

| Component | Files | Licence |
|-----------|-------|---------|
| DM Sans | `univpn/src/main/res/font/dm_sans_*.ttf` | SIL Open Font License 1.1 — [LICENSES/OFL-1.1-DMSans.txt](LICENSES/OFL-1.1-DMSans.txt) |
| JetBrains Mono | `univpn/src/main/res/font/jetbrains_mono_regular.ttf` | SIL Open Font License 1.1 — [LICENSES/OFL-1.1-JetBrainsMono.txt](LICENSES/OFL-1.1-JetBrainsMono.txt) |
| PIA root CA certificate | `univpn/src/main/resources/pia/ca.rsa.4096.crt` | Public certificate published by Private Internet Access in [pia-foss/manual-connections](https://github.com/pia-foss/manual-connections) (MIT) |

Font copyright notices:

- DM Sans — Copyright 2014 The DM Sans Project Authors (https://github.com/googlefonts/dm-fonts).
  DM Sans is derived from Poppins: Copyright 2014-2017 Indian Type Foundry
  (info@indiantypefoundry.com) with Reserved Font Name 'Poppins'. Copyright 2019 Google LLC.
- JetBrains Mono — Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono).

The font files are redistributed unmodified; only the file names were changed to satisfy
Android resource naming rules.

## Build dependencies (fetched by Gradle, not stored in this repository)

| Dependency | Licence |
|------------|---------|
| `com.wireguard.android:tunnel` (WireGuard for Android tunnel library) | Apache-2.0 |
| `org.nanohttpd:nanohttpd` | BSD-3-Clause |
| `com.google.zxing:core` | Apache-2.0 |
| AndroidX (AppCompat, Leanback, RecyclerView, Room, Lifecycle, Fragment, Activity, Security Crypto, Test) | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 |
| `com.android.tools:desugar_jdk_libs` | GPL-2.0 with Classpath Exception |
| JUnit 4 (tests only) | EPL-1.0 |

All of the above are compatible with distribution under GPLv3.
