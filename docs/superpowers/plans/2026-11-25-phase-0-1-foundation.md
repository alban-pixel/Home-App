# Phase 0–1: Probe & Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Talk to the Tuya IR blaster locally from an Android app: discover it, set it up with its local key, toggle lamp and projector with real on/off switches, and show live temperature/humidity.

**Architecture:** Pure-Kotlin `:tuya` library (framing, crypto v3.3/3.4/3.5, TCP session, UDP discovery) tested against `tinytuya`-generated fixtures; Android `:app` module (Compose + M3, Hilt, Room, DataStore) with a single `BlasterClient` wrapping the library. The hardware's DP map comes from a Phase 0 probe on the Mac.

**Tech Stack:** Kotlin 2.x, Gradle Kotlin DSL + version catalog, AGP (latest stable), Jetpack Compose + Material 3 (dynamic colour), Hilt, Room, DataStore + Tink (encryption), kotlinx.coroutines, kotlinx.serialization, JUnit 5 (`:tuya`) / JUnit 4 + Robolectric + Compose UI test (`:app`), Python 3 + `tinytuya` (probe & fixtures only).

**Spec:** `docs/superpowers/specs/2026-11-25-home-app-design.md`

## Global Constraints

- `minSdk 34`; `compileSdk`/`targetSdk` = highest platform installed in `~/Library/Android/sdk/platforms` (36 or 37).
- Package / applicationId: `io.github.albanpixel.homeapp`. Library package: `io.github.albanpixel.tuya`.
- Build JDK: Android Studio JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`); `jvmToolchain(17)`.
- All versions pinned in `gradle/libs.versions.toml`. No ads, analytics, crash reporters or tracking SDKs.
- UI copy in French; code, identifiers, commits, PRs in English.
- Never commit or log the local key or device ID. Fixtures use the fake key `0123456789abcdef` and device ID `fakedeviceid0000000000`.
- Git: one issue per task, branch `<type>/<issue>-<desc>`, Conventional Commits, PR `Closes #N`, squash-merge. No `Co-Authored-By` lines.
- `:tuya` must not depend on Android.

## Review Focus

1. **Blaster IP changed (DHCP)** → `BlasterClient` rediscovers by device ID via UDP before failing; switch shows a snackbar and does **not** flip state on failure. Tests in Task 6 (rediscovery) and Task 9 (state kept).
2. **Wrong local key** → setup "Tester" shows « Clé locale incorrecte », no crash. Tests in Task 2 (`DecryptionException`), Task 4 (`BadKey`) and Task 8 (copy).
3. **Rapid double tap on a switch** → commands are serialized; final stored state equals the last tap. Test in Task 9.
4. **Single-connection devices** (Tuya accepts ~1 client) → one shared session guarded by a mutex; idle session closed after 30 s. Test in Task 4.
5. **Learn timeout** (user never presses the remote) → `learnIr` fails after 20 s and sends the dialect's study-exit command. Test in Task 6.

## Execution waves (parallel subagents)

| Wave | Tasks (parallel) | Model |
|---|---|---|
| 1 | T0 Probe (main session + owner), T1 Scaffold | main / sonnet |
| 2 | T2 Codec, T5 Discovery, T7 Data layer | opus / sonnet / sonnet |
| 3 | T3 Crypto session, then T4 TCP session (same agent, sequential) | opus |
| 4 | T6 BlasterClient, T8 Setup wizard | sonnet |
| 5 | T9 Home screen | sonnet |
| 6 | T10 On-device verification | main |

Each task runs in its own git worktree off `main`; merge order follows waves.

---

### Task 0: Hardware probe (main session, owner present)

**Files:**
- Create: `tools/probe/README.md` (how to get the local key on iot.tuya.com, how to run the probe)
- Create: `tools/probe/probe.py`
- Create: `docs/hardware/probe-report.md` (no secrets)

**Interfaces:**
- Produces: `docs/hardware/probe-report.md` containing: protocol version, DP ids + types + sample values for status, the exact send-IR and learn-IR payload shapes (which DPs, JSON/string format), learned-code format (base64 length), temperature/humidity DP ids and scale factor. Task 6 implements `IrDialect` from this.

- [ ] **Step 1:** Owner creates a Tuya IoT account, links the Smart Life app, and gets device ID + local key (README walks through it). Owner keeps them in `tools/probe/.env` (git-ignored by `*.env`).
- [ ] **Step 2:** `python3 -m venv tools/probe/.venv && tools/probe/.venv/bin/pip install tinytuya` then `tools/probe/.venv/bin/python -m tinytuya scan` → note IP + version.
- [ ] **Step 3:** `probe.py status` prints `d.status()`; `probe.py learn` enters learn mode, waits 20 s, prints the captured code; `probe.py send <code>` sends it. Try both dialects: DP `201` JSON (`{"control":"study"}` / `{"control":"send_ir","head":"","key1":"<code>","type":0,"delay":300}`, code read from DP `202`) and legacy DP `1` (`"study"` / `"send_ir"`, code in DP `2`, send via DP `7`).
- [ ] **Step 4:** Learn and test-send lamp on, lamp off, projector power. Confirm each works physically.
- [ ] **Step 5:** Write `docs/hardware/probe-report.md`. Grep it for the device ID and key: `grep -c "<key>" docs/hardware/probe-report.md` → `0`.
- [ ] **Step 6:** Commit on `docs/<issue>-hardware-probe`, PR, merge.

### Task 1: Project scaffold + CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper
- Create: `tuya/build.gradle.kts` (kotlin-jvm, JUnit 5), `tuya/src/main/kotlin/io/github/albanpixel/tuya/Tuya.kt` (placeholder `object Tuya { const val NAME = "tuya" }`)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `HomeApplication.kt` (`@HiltAndroidApp`), `MainActivity.kt`, `ui/theme/Theme.kt` (M3, `dynamicLightColorScheme`/`dynamicDarkColorScheme`)
- Create: `.github/workflows/ci.yml` (ubuntu, JDK 17, `./gradlew :tuya:test :app:testDebugUnitTest :app:assembleDebug lint`)
- Modify: `CLAUDE.md` — fill "Build & test" section.

**Interfaces:**
- Produces: modules `:tuya`, `:app`; `HomeTheme { }` composable; `MainActivity` hosting a `NavHost` with a single route `home` showing « Home App ».

- [ ] **Step 1:** Write `app/src/test/.../SmokeTest.kt` (Robolectric): launch `MainActivity`, assert node with text `Home App` is displayed.
- [ ] **Step 2:** Create the build files and code above.
- [ ] **Step 3:** Run `JAVA_HOME=<JBR> ./gradlew :tuya:test :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Commit, push, PR; CI green; merge.

### Task 2: `:tuya` message codec (framing v3.3/3.4/3.5)

**Files:**
- Create: `tools/fixtures/gen_fixtures.py` (uses `tinytuya.MessagePayload`/`pack_message` with the fake key; writes JSON)
- Create: `tuya/src/test/resources/fixtures/messages.json`
- Create: `tuya/src/main/kotlin/io/github/albanpixel/tuya/protocol/{Command.kt,TuyaMessage.kt,MessageCodec.kt}`
- Test: `tuya/src/test/kotlin/io/github/albanpixel/tuya/protocol/MessageCodecTest.kt`

**Interfaces:**
- Produces:
  - `enum class ProtocolVersion(val label: String) { V3_3("3.3"), V3_4("3.4"), V3_5("3.5") }`
  - `enum class Command(val code: Int)`: `SESS_KEY_NEG_START(3)`, `SESS_KEY_NEG_RESP(4)`, `SESS_KEY_NEG_FINISH(5)`, `CONTROL(7)`, `STATUS(8)`, `HEART_BEAT(9)`, `DP_QUERY(10)`, `CONTROL_NEW(13)`, `DP_QUERY_NEW(16)`
  - `data class TuyaMessage(val seq: Int, val command: Command, val payload: ByteArray, val returnCode: Int? = null)`
  - `class MessageCodec(val version: ProtocolVersion) { fun encode(msg: TuyaMessage, key: ByteArray): ByteArray; fun decode(bytes: ByteArray, key: ByteArray): TuyaMessage }` — `decode` throws `DecryptionException` (bad key/HMAC/tag) and `MalformedMessageException`.
  - `fun MessageCodec.frameLength(header: ByteArray): Int` (for stream reading)

Protocol facts (verify against fixtures, fixtures win):
- v3.3/3.4: prefix `0x000055AA`, `seq u32`, `cmd u32`, `len u32` (= rest incl. trailer), [`retcode u32` on device→client], payload, check (`CRC32` u32 for 3.3, `HMAC-SHA256(key)` 32 bytes for 3.4), suffix `0x0000AA55`. Payload AES-128-ECB PKCS7. 3.3 `CONTROL` payload prefixed with `"3.3"` + 12 zero bytes; 3.4 prefixes `"3.4"` + 12 zero bytes inside the encryption for non-query commands.
- v3.5: prefix `0x00006699`, `u16 0`, `seq u32`, `cmd u32`, `len u32`, `IV(12)`, AES-128-GCM ciphertext, `tag(16)`, suffix `0x00009966`. AAD = the 14 header bytes after the prefix.

- [ ] **Step 1:** Write `gen_fixtures.py`; run it → `messages.json` with ≥ 3 cases per version (client→device `DP_QUERY(_NEW)`, `CONTROL(_NEW)` with `{"dps":{"201":"…"}}`, device→client `STATUS` with retcode), each as `{version, seq, cmd, key_hex, payload_utf8, bytes_hex}`. v3.5 cases include fixed IV.
- [ ] **Step 2:** Write tests: `encode matches tinytuya bytes for every fixture`, `decode roundtrips every fixture`, `decode with wrong key throws DecryptionException`, `decode truncated frame throws MalformedMessageException`.
- [ ] **Step 3:** Run `./gradlew :tuya:test` → FAIL (classes missing).
- [ ] **Step 4:** Implement with `javax.crypto` (AES/ECB/PKCS5Padding, AES/GCM/NoPadding), `java.util.zip.CRC32`, `HmacSHA256`. `encode` takes an injectable `ivProvider: () -> ByteArray` (constructor param, default `SecureRandom`) so v3.5 fixtures are deterministic.
- [ ] **Step 5:** `./gradlew :tuya:test` → PASS.
- [ ] **Step 6:** Commit, PR, merge.

### Task 3: `:tuya` session-key negotiation (v3.4/3.5)

**Files:**
- Create: `tuya/src/main/kotlin/io/github/albanpixel/tuya/protocol/SessionKey.kt`
- Modify: `tools/fixtures/gen_fixtures.py` → also emit `session.json` (local_nonce, remote_nonce, local_key, expected session key, expected step-3 HMAC) per version
- Test: `tuya/src/test/kotlin/io/github/albanpixel/tuya/protocol/SessionKeyTest.kt`

**Interfaces:**
- Consumes: `MessageCodec`, `Command`, `ProtocolVersion` (Task 2)
- Produces: `object SessionKey { fun derive(version: ProtocolVersion, localKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray; fun verifyResponse(localKey: ByteArray, localNonce: ByteArray, respPayload: ByteArray): ByteArray /* returns remoteNonce, throws DecryptionException */; fun finishPayload(localKey: ByteArray, remoteNonce: ByteArray): ByteArray }`

Facts: response payload = `remoteNonce(16) + HMAC-SHA256(localKey, localNonce)(32)`; finish = `HMAC-SHA256(localKey, remoteNonce)`; key = `xor(localNonce, remoteNonce)` encrypted with localKey — AES-ECB for 3.4, AES-GCM with IV = `localNonce[0..12]` taking the first 16 ciphertext bytes for 3.5.

- [ ] **Step 1:** Extend fixture script; run it.
- [ ] **Step 2:** Tests: `derive matches tinytuya for 3.4`, `derive matches tinytuya for 3.5`, `verifyResponse rejects tampered HMAC`, `finishPayload matches fixture`.
- [ ] **Step 3:** Run → FAIL. **Step 4:** Implement. **Step 5:** Run → PASS. **Step 6:** Commit (same PR as Task 4 is fine — same agent).

### Task 4: `:tuya` TCP device session

**Files:**
- Create: `tuya/src/main/kotlin/io/github/albanpixel/tuya/TuyaDevice.kt`, `TuyaConfig.kt`, `TuyaException.kt`
- Test: `tuya/src/test/kotlin/io/github/albanpixel/tuya/TuyaDeviceTest.kt`, `FakeTuyaServer.kt` (in-process `ServerSocket` speaking the codec, scripted replies)

**Interfaces:**
- Consumes: Tasks 2–3
- Produces:
  - `data class TuyaConfig(val host: String, val port: Int = 6668, val deviceId: String, val localKey: String, val version: ProtocolVersion)`
  - `class TuyaDevice(config: TuyaConfig, scope: CoroutineScope, idleTimeoutMs: Long = 30_000, heartbeatMs: Long = 10_000) { suspend fun status(): Map<String, Any?>; suspend fun setDps(dps: Map<String, Any?>): Unit; val updates: SharedFlow<Map<String, Any?>> /* unsolicited STATUS pushes */; fun close() }`
  - `sealed class TuyaException : Exception` with `Unreachable`, `BadKey`, `Timeout`, `Protocol`.

Behaviour: lazy connect (3 s connect timeout, 5 s reply timeout); negotiate session for 3.4/3.5; all requests go through one `Mutex` on one socket; heartbeat while open; close after `idleTimeoutMs` without requests; auto-reconnect once on broken pipe. Query payload JSON: `{"gwId","devId","uid","t"}` (3.3) / `{}`-style per tinytuya for 3.4/3.5; control JSON `{"devId","uid","t","dps"}` (3.3) / `{"protocol":5,"t","data":{"dps"}}` (3.4/3.5). `t` = epoch seconds.

- [ ] **Step 1:** Tests against `FakeTuyaServer` for each version: `status returns dps`, `setDps sends expected json`, `concurrent calls are serialized on one socket` (launch 10 `setDps`, server sees 1 connection, 10 ordered frames), `idle session closes after timeout` (virtual time), `wrong key raises BadKey`, `closed port raises Unreachable`, `unsolicited STATUS emitted on updates`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit, PR (Tasks 3+4), merge.

### Task 5: `:tuya` UDP discovery

**Files:**
- Create: `tuya/src/main/kotlin/io/github/albanpixel/tuya/Discovery.kt`
- Modify: `tools/fixtures/gen_fixtures.py` → `discovery.json` (broadcast packets for 6667 ECB and 7000 GCM)
- Test: `tuya/src/test/kotlin/io/github/albanpixel/tuya/DiscoveryTest.kt`

**Interfaces:**
- Produces: `data class DiscoveredDevice(val ip: String, val deviceId: String, val productKey: String?, val version: ProtocolVersion)`; `object Discovery { fun parse(packet: ByteArray, port: Int): DiscoveredDevice?; fun listen(timeoutMs: Long = 6_000): Flow<DiscoveredDevice> /* binds 6666, 6667, 7000, dedups by deviceId */ }`

Facts: UDP key = `MD5("yGAdlopoPVldABfn")`; 6666 plaintext JSON, 6667 v3.3 framing ECB, 7000 v3.5 framing GCM. JSON fields `ip`, `gwId`, `productKey`, `version`.

- [ ] **Step 1:** Tests: `parse 6667 fixture`, `parse 7000 fixture`, `parse garbage returns null`. **Step 2:** FAIL. **Step 3:** Implement (`parse` reuses `MessageCodec`). **Step 4:** PASS. **Step 5:** Commit, PR, merge.

### Task 6: `BlasterClient` + `IrDialect` (app)

**Files:**
- Create: `app/src/main/java/io/github/albanpixel/homeapp/blaster/{IrDialect.kt,BlasterClient.kt,BlasterConfigStore.kt,BlasterModule.kt}`
- Test: `app/src/test/java/io/github/albanpixel/homeapp/blaster/{IrDialectTest.kt,BlasterClientTest.kt}`

**Interfaces:**
- Consumes: `TuyaDevice`, `TuyaConfig`, `Discovery` (Tasks 4–5), probe report (Task 0)
- Produces:
  - `interface IrDialect { fun sendDps(code: String): Map<String, Any?>; fun startLearnDps(): Map<String, Any?>; fun exitLearnDps(): Map<String, Any?>; fun learnedCode(update: Map<String, Any?>): String?; fun sensor(status: Map<String, Any?>): SensorValue? }` with the implementation the probe validated (and only that one — YAGNI).
  - `data class SensorValue(val temperatureC: Float, val humidityPct: Int)`
  - `class BlasterConfigStore` (DataStore + Tink AEAD): `val config: Flow<TuyaConfig?>`; `suspend fun save(c: TuyaConfig)`
  - `sealed interface ConnectionState { Unconfigured; Connecting; Connected; Error(val reason: TuyaException) }`
  - `class BlasterClient @Inject constructor(...) { val state: StateFlow<ConnectionState>; suspend fun sendIr(code: String): Result<Unit>; suspend fun learnIr(timeoutMs: Long = 20_000): Result<String>; suspend fun readSensor(): Result<SensorValue>; suspend fun test(config: TuyaConfig): Result<SensorValue> }`

Behaviour: on `Unreachable`, run `Discovery.listen()` for the stored device ID; if found at a new IP, save it and retry once. `learnIr` sends start, awaits `learnedCode` on `updates`, always sends exit (finally), fails with `Timeout` after `timeoutMs`.

- [ ] **Step 1:** Tests with a fake `TuyaDevice` seam (`interface DeviceSession` extracted from `TuyaDevice`, injected via factory): `sendIr builds dialect dps`, `learnIr returns code from update`, `learnIr times out after 20s and sends exit` (virtual time), `unreachable triggers rediscovery and retry with new ip`, `sensor parses probe sample values`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit, PR, merge.

### Task 7: Data layer (Room) for devices and IR codes

**Files:**
- Create: `app/src/main/java/io/github/albanpixel/homeapp/data/{AppDatabase.kt,DeviceEntity.kt,IrCodeEntity.kt,DeviceDao.kt,IrCodeDao.kt,DeviceRepository.kt,DataModule.kt}`
- Test: `app/src/test/java/io/github/albanpixel/homeapp/data/DeviceRepositoryTest.kt` (Robolectric, in-memory Room)

**Interfaces:**
- Produces:
  - Entities per spec: `DeviceEntity(id: Long, name: String, icon: String, kind: DeviceKind, onCodeId: Long?, offCodeId: Long?, toggleCodeId: Long?, isOn: Boolean)`, `enum DeviceKind { LAMP, PROJECTOR, OTHER }`, `IrCodeEntity(id: Long, deviceId: Long, label: String, payload: String, createdAt: Long)`.
  - `class DeviceRepository { fun devices(): Flow<List<DeviceEntity>>; suspend fun addCode(deviceId: Long, label: String, payload: String): Long; suspend fun setPowerCode(deviceId: Long, on: Boolean, codeId: Long); suspend fun setOn(deviceId: Long, isOn: Boolean); suspend fun code(id: Long): IrCodeEntity? }`
  - DB seeded on create with « Lampe » (LAMP) and « Projecteur » (PROJECTOR). Schema exported to `app/schemas/` (version 1).

- [ ] **Step 1:** Tests: `seed creates lamp and projector`, `setPowerCode then code returns payload`, `setOn updates flow`. **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit, PR, merge.

### Task 8: Setup wizard

**Files:**
- Create: `app/src/main/java/io/github/albanpixel/homeapp/ui/setup/{SetupScreen.kt,SetupViewModel.kt}`
- Modify: `MainActivity.kt` nav — start at `setup` when `BlasterConfigStore.config` is null, else `home`.
- Test: `app/src/test/java/.../ui/setup/SetupViewModelTest.kt`

**Interfaces:**
- Consumes: `Discovery`, `BlasterClient.test`, `BlasterConfigStore` (Tasks 5–6)
- Produces: route `setup`; `SetupViewModel.uiState: StateFlow<SetupUiState>` with steps `Intro → Discovering → PickDevice(list) → EnterKey → Testing → Done | Failed(message)`.

Copy: errors map `BadKey` → « Clé locale incorrecte », `Unreachable` → « Blaster introuvable sur le Wi-Fi », `Timeout` → « Le blaster ne répond pas ». Intro links to `tools/probe/README.md` on GitHub for getting the key. Key field accepts exactly 16 printable chars, otherwise « La clé fait 16 caractères ».

- [ ] **Step 1:** Tests: `bad key maps to French message`, `16-char validation`, `success saves config and emits Done`. **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit, PR, merge.

### Task 9: Home screen — switches, sensor card, learn power codes

**Files:**
- Create: `app/src/main/java/io/github/albanpixel/homeapp/ui/home/{HomeScreen.kt,HomeViewModel.kt,DeviceCard.kt,SensorCard.kt,LearnPowerSheet.kt}`
- Test: `app/src/test/java/.../ui/home/HomeViewModelTest.kt`, `HomeScreenTest.kt` (Compose)

**Interfaces:**
- Consumes: `DeviceRepository` (T7), `BlasterClient` (T6)
- Produces: route `home`; `HomeViewModel { fun toggle(deviceId: Long, on: Boolean); fun forceState(deviceId: Long, on: Boolean); fun learnPower(deviceId: Long, on: Boolean) }`

Behaviour: `toggle` sends the on/off code (or `toggleCodeId`), sets `isOn` **only on success**, else snackbar « Échec : <reason> ». Toggles for one device go through a per-device `Mutex` (serialized, last tap wins). Long-press → `forceState` (no IR) with snackbar « État corrigé ». A device without codes shows « Configurer » opening `LearnPowerSheet` (learn « Allumer », then « Éteindre », test-send each). Sensor card shows `21,4 °C · 48 %` (French decimal comma), refreshed on resume.

- [ ] **Step 1:** Tests: `toggle success flips state`, `toggle failure keeps state and emits error`, `two rapid toggles end in last requested state`, `forceState sends nothing`, `sensor formats with comma`, Compose: `device without codes shows Configurer`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit, PR, merge.

### Task 10: On-device verification (main session)

- [ ] **Step 1:** Owner enables USB debugging on the Pixel 9; `~/Library/Android/sdk/platform-tools/adb devices` lists it.
- [ ] **Step 2:** `./gradlew :app:installDebug`; run setup with the real key.
- [ ] **Step 3:** Toggle lamp and projector 5× each: physical device reacts every time; measure tap → IR < 500 ms (warm).
- [ ] **Step 4:** Change nothing in-app, unplug/replug the blaster (new DHCP lease possible) → next toggle still works (rediscovery).
- [ ] **Step 5:** Record results in the Phase 1 tracking issue; close it.
