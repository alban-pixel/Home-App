# Home App — Design

Date: 2026-11-25 · Status: approved in conversation (brainstorm), tech choices are Claude's calls.

## Intent

The owner controls a lamp and a projector through a cheap Wi-Fi Tuya IR blaster
(AliExpress "S09 Pro" style, with built-in temperature + humidity sensor) using Tuya's
Smart Life app on a Google Pixel 9. Smart Life is slow, shows ads and is restrictive.
Home App replaces it: an ad-free, fast, clean Material Design 3 Android app that talks to
the blaster directly over the home Wi-Fi.

Success = the owner deletes Smart Life and does everything daily from Home App, faster.

## Goals

1. **Device switches.** One real on/off switch per device (lamp, projector) instead of the
   4 separate Smart Life macros (lamp on, lamp off, projector on, projector off).
2. **IR learning.** Record new IR codes through the blaster (point the original remote, press).
3. **Projector remote.** A "Remote" screen with at minimum a D-pad (up/down/left/right/OK) and
   volume (+, −, mute). More buttons can be added later. Elements can be moved on this screen.
4. **Lamp colours.** A row of predefined colour swatches, each bound to a learned IR code;
   add / remove / reorder.
5. **Macros.** Ordered steps: device state (e.g. "Lamp → on"), a specific IR button, or a
   delay ("wait 3 s"). Launchable from home screen, home-screen widget, Quick Settings tile.
6. **Timers.** Countdown ("projector off in 2 h") and recurring schedules ("lamp on 07:00
   Mon–Fri"), targeting a device action or a macro.
7. **Temperature & humidity.** Current value + history curve like Smart Life: Day / Week /
   Month views, min / max / average, tap a point for its exact value.
8. **Home-screen widgets.** Device switches, macros, current temp/humidity.

## Non-goals

- No cloud dependency after setup. No account, no backend, no server.
- Timers only need to work when the phone is at home on the home Wi-Fi. Away = skipped.
- No sensor history before the app was installed; no backfill from Tuya cloud (maybe later).
- No free-form colour wheel (an IR lamp only knows its remote's colours).
- No support for other Tuya device types, other users, iOS, or multiple homes.

## Anti-goals (failure even if it "works")

- Any ads, tracking, analytics or nag screens.
- Slower than Smart Life to toggle a device (target: switch tap → IR fired < 500 ms on LAN
  with a warm connection).
- Secrets (local key, device ID) committed to the public repo or logged.

## Constraints

- Target device: Google Pixel 9 (Android 15+). `minSdk 34`, `targetSdk`/`compileSdk` = latest
  installed platform (36 or 37).
- Hardware: Tuya Wi-Fi IR blaster, category `wnykq`, protocol version unknown (3.3/3.4/3.5) —
  must be discovered by the Phase 0 probe. DP map (send IR, learn IR, temperature, humidity)
  unknown — discovered by the probe.
- IR is one-way: the app cannot know a device's true state. State is optimistic (last command
  sent). Long-press on a switch sets the state without sending anything (resync when someone
  used the physical remote).
- Local control needs the device's **local key**, obtained once from the Tuya IoT developer
  platform (free account, linked to the Smart Life account). The owner does this once;
  the app has a guided setup to paste it.
- Android background work: periodic WorkManager minimum 15 min; may be deferred in Doze.
- Repo is public: local key / device ID stored only on the phone (encrypted DataStore), never
  in git, never logged.

## Approaches considered

| | Approach | Trade-off | Verdict |
|---|---|---|---|
| A | **Native Kotlin + Jetpack Compose + Material 3**, own Tuya local-protocol client | Best M3/Material You fit, Glance widgets + QS tile native, fastest; we must implement the Tuya LAN protocol ourselves | **Chosen** |
| B | Flutter | Cross-platform not needed (Pixel only); widgets / QS tile / background need native code anyway | Rejected |
| C | Home Assistant + LocalTuya on an always-on box, app as a client | Timers would fire when away (not needed), but adds a server to buy/maintain | Rejected |

Sensor history: **phone records locally** (chosen) vs Tuya cloud history vs hybrid. Owner only
glances at it occasionally, so local-only is enough.

Timers: **run on the phone** (AlarmManager exact alarms → send if on home Wi-Fi, otherwise
skip and log). Owner confirmed timers only matter at home.

## Design

### Architecture (single app module + one pure-Kotlin library)

- `:tuya` — pure Kotlin/JVM library, no Android deps: message framing, crypto (v3.3/3.4/3.5),
  TCP session, UDP discovery. Unit-tested against fixtures generated with `tinytuya` (Python).
- `:app` — Android app: Compose UI, Room DB, DataStore, WorkManager, AlarmManager, Glance
  widgets, QS tile. Hilt for DI. Kotlin coroutines/Flow throughout.

`BlasterClient` (in `:app`, wraps `:tuya`) is the only thing that talks to the hardware:
`sendIr(code)`, `learnIr(): IrCode`, `readSensor(): SensorReading`, and a `connection` state
flow. It keeps one warm TCP session while the app is in foreground (heartbeat), reconnects on
demand otherwise.

### Data model (Room)

- `Device(id, name, icon, kind: LAMP|PROJECTOR|OTHER, onCodeId, offCodeId, toggleCodeId?, isOn)`
  — `toggleCodeId` for remotes with a single power button.
- `IrCode(id, deviceId, label, payload: String /* blaster's base64 code */, createdAt)`
- `RemoteElement(id, deviceId, type: DPAD|VOLUME|BUTTON|COLOR_SWATCH, gridX, gridY, w, h,
  label?, icon?, color?, codeIds: List<Long>)` — D-pad holds 5 code ids, volume 3.
- `Macro(id, name, icon)` + `MacroStep(macroId, order, type: DEVICE_STATE|IR_CODE|DELAY,
  deviceId?, targetOn?, codeId?, delayMs?)`
- `Timer(id, type: COUNTDOWN|RECURRING, fireAt? , hour?, minute?, daysMask?, target: device
  action or macro id, enabled)`
- `SensorReading(timestamp, temperatureC: Float, humidityPct: Int)` — kept 1 year.
- `ActivityLog(timestamp, message)` — skipped timers, failures.

### Screens

1. **Setup wizard** (first run): explains getting the local key (link to a guide in repo docs),
   UDP-discovers the blaster (shows IP, device ID, version), paste local key, "Test" reads the
   sensor. Edit later from Settings.
2. **Home**: sensor card (`21,4 °C · 48 %`, tap → history), device cards with switch
   (tap = send, long-press = set state without sending), macro chips. Tap a device card body →
   its control screen.
3. **Device screen**: switch + its remote grid (projector: D-pad + volume; lamp: colour
   swatches). "Edit" mode: drag elements on a snapping grid, delete, "+" adds an element.
   Adding a button/swatch starts learning: blaster listens, user presses the real remote,
   code is captured, test-sent, then named.
4. **Macros**: list, editor (add/reorder/delete steps), run.
5. **Timers**: list, create countdown or recurring, enable/disable, activity log.
6. **Sensor history**: Day / Week / Month tabs, line chart (temp and humidity), min/max/avg,
   tap point → value. Gaps shown as gaps (no interpolation).
7. **Settings**: connection, local key, data retention, about. Material You dynamic colour,
   light/dark following system.

### Background

- `SensorWorker` (WorkManager, periodic 15 min, network constraint): reads sensor if on home
  Wi-Fi, writes `SensorReading`, prunes > 1 year. Also read on app open.
- `TimerScheduler`: AlarmManager `setExactAndAllowWhileIdle` per next occurrence;
  receiver runs the target via `BlasterClient`; if not reachable → `ActivityLog` entry; then
  schedules next occurrence. Re-schedule on boot and on time change.
- "Home Wi-Fi" = blaster reachable on LAN (simple TCP connect with 2 s timeout), not SSID
  matching (avoids location permission).

### Widgets & tile

Glance widgets: device switch (1×1 / 2×1), macro button, sensor (2×1). QS tile: one
configurable macro or device toggle.

## Phases (each its own plan, each shippable)

0. **Probe** — on the Mac, with `tinytuya`: find IP/version, read DP map, test send/learn,
   produce test fixtures. Owner provides the local key.
1. **Foundation** — scaffold + CI, `:tuya` library, setup wizard, home with working switches
   (codes learned in a minimal "learn on/off" flow), sensor card.
2. **Remote & colours** — grid editor, D-pad/volume/button/swatch elements, learn flow.
3. **Macros & timers** — editor, runner, scheduling, activity log.
4. **Sensor history** — worker, Room, charts.
5. **Widgets & QS tile.**

## Left to the builder

Exact library versions (latest stable, pinned in `gradle/libs.versions.toml`), chart library
(Vico preferred), icons set (Material Symbols), exact grid size (suggest 4 columns), copy text
(French UI).
