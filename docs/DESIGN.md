# UniVPN — UI Design

UniVPN is a 10-foot UI driven entirely by a D-pad remote. This document describes the
navigation model and the visual design system. Layout or style changes should follow it.

## Navigation

`TvMainActivity` hosts a fixed left sidebar with three tabs and swaps the content
fragment on the right:

| Tab        | Fragment            | Contents                                                       |
|------------|---------------------|----------------------------------------------------------------|
| App Routes | `AppRoutesFragment` | Every installed app with its assigned route (profile, No VPN, Passthrough, Default) |
| Profiles   | `ProfilesFragment`  | WireGuard profiles, provider accounts, web import address      |
| Settings   | `SettingsFragment`  | Auto-start on boot, status overlay and its size/position       |

Provider account setup (`ProvidersFragment` → `ProviderSetupFragment`) is reached from
inside Profiles rather than being a top-level tab.

```
┌────────────┬────────────────────────────────────────────┐
│            │                                            │
│ > App      │  Netflix      ──────  UK                   │
│   Routes   │  BBC iPlayer  ──────  UK                   │
│            │  Spotify      ──────  Default              │
│   Profiles │  ...                                       │
│            │                                            │
│   Settings │                                            │
└────────────┴────────────────────────────────────────────┘
   sidebar              content fragment
```

### Entry point

On launch `TvMainActivity` checks for VPN profiles before committing the first fragment:
no profiles → open **Profiles** with an "add your first profile" prompt; otherwise open
**App Routes**. The check runs off the main thread, so there is no flash between states.

### D-pad behaviour

- D-pad left from content → focus moves to the sidebar
- Focusing a sidebar tab switches to it (no Select press needed); D-pad right returns to content
- Selecting an app row opens the route picker for that app

### Switching indicator

While `VpnSwitcherService` is switching tunnels, a status chip in the top-right corner
shows `⟳ Switching…`. When tunneled it shows `{profile} — {latency}ms`. It never takes
D-pad focus.

---

# Visual Design System

> **Aesthetic direction:** Industrial / Utilitarian.
> Motto: *"looks like it was built to last, not to be shown off."*
> The interface disappears. Nothing competes with the app list. Status reads in under 2 seconds.

---

## Color

### Background system
| Role             | Hex       | Usage                                      |
|------------------|-----------|--------------------------------------------|
| Background       | `#111318` | Activity/screen background. Cold blue-shifted near-black (cooler than generic `#1a1a1a`, reads as precision). |
| Surface          | `#1A1D24` | Rows, cards. Lifted from background.       |
| Surface focused  | `#21252E` | Focused row background — subtle, no jarring jump. |
| Surface elevated | `#2A2F3B` | Dialogs, tooltips, elevated surfaces.      |

### Text
| Role           | Hex       | Usage                                      |
|----------------|-----------|--------------------------------------------|
| Primary        | `#F1F2F5` | App names, nav labels, headings. Slightly cool-neutral, less eye strain than pure white on TV. |
| Secondary      | `#5B6270` | Neutral badge states (Passthrough, No VPN, Default). Recede — don't compete with app icons. |
| Tertiary       | `#3D4250` | Hint text, metadata, disabled states.      |

### Accent
| Role           | Hex       | Usage                                      |
|----------------|-----------|--------------------------------------------|
| Accent         | `#22C9B0` | Operational teal. Used **only** for: focus ring, active profile badge text, status chip (tunneled state). Nowhere else. |
| Accent dim     | `rgba(34,201,176,0.12)` | Status chip background, nav selected item background. |
| Accent border  | `rgba(34,201,176,0.30)` | Status chip border, focus ring border.     |

### Semantic (status chip latency only)
| State   | Color     | Threshold  |
|---------|-----------|------------|
| Good    | `#22C9B0` | < 50ms     |
| Warning | `#F59E0B` | 50–150ms   |
| Bad     | `#EF4444` | > 150ms    |

---

## Typography

**Two-font system:**
- **DM Sans** — UI labels, app names, navigation, headings. Geometric, clean. Bundled in `res/font/`. Not Inter, not Roboto.
- **JetBrains Mono** — All badge/status text, latency readouts, hint text. Reads as a terminal instrument readout, not a widget. Bundled in `res/font/`.

### Scale (TV 10-foot UI)
| Role              | Font              | Size | Weight | Color          |
|-------------------|-------------------|------|--------|----------------|
| Section header    | DM Sans           | 24sp | 500    | Primary        |
| Row label (body)  | DM Sans           | 18sp | 400    | Primary        |
| Nav item          | DM Sans           | 16sp | 400/500| Secondary/Primary |
| Badge / status    | JetBrains Mono    | 14sp | 300    | Secondary or Accent |
| Status chip       | JetBrains Mono    | 14sp | 400    | Accent         |
| Hint text         | JetBrains Mono    | 12sp | 300    | Tertiary       |
| Button label      | JetBrains Mono    | 11sp | 400    | (uppercase, 0.06em tracking) |

### Android font loading
```xml
<!-- res/font/dm_sans.xml — font-family over bundled dm_sans_regular/medium.ttf -->
<!-- res/font/jetbrains_mono.xml — font-family over bundled jetbrains_mono_regular.ttf -->
```
Fallback: `sans-serif` (DM Sans), `monospace` (JetBrains Mono).

---

## Spacing

Base unit: **8dp**

| Token | Value | Usage                          |
|-------|-------|--------------------------------|
| 2xs   | 2dp   | Focus border width             |
| xs    | 4dp   | Icon inner padding             |
| sm    | 8dp   | Gap between icon and label     |
| md    | 12dp  | Row horizontal padding (inner) |
| lg    | 16dp  | Row horizontal padding, icon margin |
| xl    | 24dp  | Header vertical padding        |
| 2xl   | 32dp  | Section gap                    |

**Row height:** 72dp (reduced from the 80dp default — tighter, shows more content without sacrificing legibility).

---

## Focus States

Replace Leanback's default focus highlight with a custom drawable:
- **Left bar:** 2dp `#22C9B0` inset-left border
- **Background:** shifts `#1A1D24` → `#21252E`
- **Outer ring:** 1px border `rgba(34,201,176,0.35)`
- **Transition:** 0ms — instant. No animation on D-pad navigation. TV remotes already feel laggy; any delay compounds it.

```xml
<!-- res/drawable/tv_list_item_background.xml -->
<!-- Selector: state_focused → #21252E + left border; default → transparent -->
```

The eye `ImageButton` at row-right uses the same focus treatment, scoped to its 64dp touch target.

---

## Border Radius

| Element       | Radius  |
|---------------|---------|
| App row       | 8dp     |
| Button        | 6dp     |
| App icon      | 8dp     |
| Status chip   | 20dp (pill) |
| Dialog        | 12dp    |
| Toggle        | 12dp (pill) |

---

## Motion

**Guiding principle:** motion should confirm actions, not decorate navigation.

| Event                      | Duration | Easing     |
|----------------------------|----------|------------|
| D-pad focus change         | 0ms      | none       |
| Status chip appear         | 150ms    | ease-out   |
| Status chip disappear      | 150ms    | ease-in    |
| Dialog enter               | 200ms    | ease-out   |
| Dialog exit                | 150ms    | ease-in    |
| Toggle state change        | 150ms    | ease-in-out|
| Everything else            | 100ms max| ease-out   |

---

## Component Specs

### App row
```
[icon 40dp] [16dp gap] [app name, DM Sans 18sp] [flex] [badge, JetBrains Mono 14sp] [16dp] [eye 32dp]
Height: 72dp · Padding: 12dp horizontal · Radius: 8dp
```

Badge rules:
- Active profile assigned → `#22C9B0` (accent)
- No VPN / Passthrough / Default → `#5B6270` (secondary, recedes)
- No pill background. Text only.

### Status chip
```
Pill, 20dp radius, 6px/14px padding
Background: rgba(34,201,176,0.12)
Border: 1px rgba(34,201,176,0.30)
Text: JetBrains Mono 14sp 400, #22C9B0 (tunneled) or #F59E0B/#EF4444 (by latency)
Position: top-right corner of TvMainActivity, above all fragments
```

Format: `{profileName} — {latency}ms` when tunneled, `⟳ Switching…` when switching.

### Buttons
- **Primary action** (rare): accent background `#22C9B0`, dark text `#0a1410`, 6dp radius, JetBrains Mono 11sp uppercase
- **Ghost / secondary**: transparent bg, `#2A2F3B` border, `#5B6270` text → on focus: `#22C9B0` border + 1px outer ring
- No default Android Material blue buttons anywhere

### Navigation sidebar
- Selected nav item: `rgba(34,201,176,0.08)` background + 2dp `#22C9B0` left bar
- Unselected: `#5B6270` text, no background
- Wordmark: DM Sans 20sp 600, `VPN` suffix in `#22C9B0`

---

## Decisions Log

| Date       | Decision                                    | Rationale                                                      |
|------------|---------------------------------------------|----------------------------------------------------------------|
| 2026-05-08 | Cold blue-shifted background `#111318`      | Cooler than generic `#1a1a1a`, reads as precision instrument   |
| 2026-05-08 | DM Sans for UI, JetBrains Mono for badges   | Two-font system: human labels + machine readouts               |
| 2026-05-08 | Single accent `#22C9B0`, used sparingly     | Avoids noise; accent = "operational", nothing else             |
| 2026-05-08 | Badge text only, no pill backgrounds        | Radical restraint — app icons are the only color in the list   |
| 2026-05-08 | 0ms focus transition                        | TV remote latency compounds with any animation delay           |
| 2026-05-08 | Row height 72dp (from 80dp)                 | Tighter but comfortable at 10 feet; shows more content         |
