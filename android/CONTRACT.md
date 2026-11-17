# Nekopost Android — module contract

This file is the contract between the three Gradle modules. Change it in the
same commit as the code it describes.

```
android/
├─ core/       design tokens, theme, fold model + layouts, shared components,
│              EnrollmentLink parser, forwarder API (interfaces + plain data),
│              FakeForwarderController
├─ forwarder/  AndroidForwarderController (implements the API); no Compose code
└─ app/        Application + AppContainer (manual DI), MainActivity, adaptive shell, screens
```

Dependencies: `app → core, forwarder`; `forwarder → core`. `forwarder` uses
only `com.drinkcat.nekopost.core.forwarder.*` and `core.enroll.*` (plain
Kotlin, no Android or Compose types).

Build: AGP 9.5.1 (built-in Kotlin) · Kotlin 2.4.21 · Gradle 9.8.2 (wrapper,
checksum pinned) · JDK 21 (CI only) · compileSdk 37 · **targetSdk 36** (37
would let Android 17 hold OTP SMS for 3 h) · minSdk 30. Versions live in
`gradle/libs.versions.toml`. App version: `android/VERSION`
(`versionCode = major*1_000_000 + minor*1_000 + patch`).

## 1. Forwarder API (`:core`, package `com.drinkcat.nekopost.core.forwarder`)

```kotlin
interface ForwarderController {
    val state: StateFlow<ForwarderState>
    suspend fun enroll(link: EnrollmentLink): EnrollResult
    fun unenroll()
    fun setSmsEnabled(enabled: Boolean)
    fun setNotificationsEnabled(enabled: Boolean)
    fun setNotificationApps(packages: Set<String>)   // NotificationAppPolicy-blocked packages are dropped
    fun setNotificationRule(packageName: String, rule: NotificationRule?)  // M1; null = NotificationRule.Default
    fun resolveSmsBacklog(upload: Boolean)           // M1; answers ForwarderState.smsBacklog ("补交" / "全部跳过")
    fun setSmsSenderRules(rules: List<SmsSenderRule>) // M1; core.rules, first match wins, no match = forward
    fun reportPermissions(status: PermissionStatus)  // the UI detects permissions and reports them
    fun observeLog(): Flow<List<ForwardLogItem>>     // latest 50, newest first, metadata only
    fun otpHealth(): Flow<OtpHealth>
    suspend fun sendTest(): SendTestResult
    fun retryNow()
    fun clearFailed()
}
```

All members are main-safe; setters return at once and persist asynchronously,
their effect is visible through `state`.

```kotlin
data class ForwarderState(
    val enrollment: Enrollment? = null,          // null = not enrolled
    val smsEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val notificationApps: Set<String> = emptySet(),
    val permissions: PermissionStatus = PermissionStatus.None,
    val queue: QueueStats = QueueStats(),
    val lastSuccess: DeliveryEvent? = null,
    val lastFailure: FailureEvent? = null,
    val paused: PauseReason? = null,
    val notificationRules: Map<String, NotificationRule> = emptyMap(),  // M1
    val smsBacklog: SmsBacklog? = null,                                 // M1: > 50 unsent SMS wait for the user
    val smsSenderRules: List<SmsSenderRule> = emptyList(),              // M1: core.rules.SmsSenderRule
) { val isEnrolled: Boolean }
fun ForwarderState.health(): ForwarderHealth   // NotEnrolled, Idle, NeedsPermission, Paused, Backlog (queue or smsBacklog), Healthy

data class Enrollment(val serverUrl: String, val sourceId: String, val sourceName: String, val enrolledAt: Instant) { val serverHost: String }
data class PermissionStatus(val receiveSms: Boolean, val readSms: Boolean, val notificationListener: Boolean,
                            val postNotifications: Boolean, val ignoringBatteryOptimizations: Boolean) { val smsReady; val allGranted }
data class QueueStats(val pending: Int = 0, val failed: Int = 0, val oldestPendingAt: Instant? = null)
data class DeliveryEvent(val at: Instant, val kind: ForwardKind)
data class FailureEvent(val at: Instant, val reason: FailureReason, val httpStatus: Int? = null, val detail: String? = null)
enum class FailureReason { Network, CertificatePinning, Unauthorized, SourceDisabled, Rejected, RateLimited, Server }
enum class PauseReason { Unauthorized, SourceDisabled }

data class SmsBacklog(val count: Int, val oldestAt: Instant?, val detectedAt: Instant) { companion object { const val Threshold = 50 } }
enum class MessagePriority(val wire: String) { Low("low"), Normal("normal"), High("high") }
data class NotificationRule(val mode: Mode = Mode.All, val keywords: List<String> = emptyList(),
                            val includeTitle: Boolean = true, val priority: MessagePriority = MessagePriority.Normal) {
    enum class Mode { All, IncludeKeywords, ExcludeKeywords }
    fun accepts(title: String?, text: String?): Boolean   // case-insensitive substring match; blank keywords ignored
}

enum class ForwardKind { Sms, Notification, Test }
enum class ForwardStatus { Queued, Sending, Retrying, Accepted, Duplicate, Failed, Paused, Skipped }
data class ForwardLogItem(val id: Long, val kind: ForwardKind, val title: String, val sourcePackage: String? = null,
                          val createdAt: Instant, val status: ForwardStatus, val attempts: Int = 0,
                          val lastHttpStatus: Int? = null, val lastError: String? = null,
                          val serverMessageId: String? = null, val updatedAt: Instant = createdAt)

data class OtpHealth(val windowDays: Int = 7, val realtimeCount: Int = 0, val reconciledCount: Int = 0,
                     val explainedCount: Int = 0,
                     val medianDelay: Duration? = null, val maxDelay: Duration? = null, val lastCodeAt: Instant? = null) {
    val verdict: OtpVerdict   // NoData, NoDelay, DelayDetected (a code SMS found only by the reconciler, not explained by a reboot/update)
}

sealed interface EnrollResult { Success(enrollment) ; Failure(reason: EnrollFailure, httpStatus: Int?) }
enum class EnrollFailure { Network, CertificatePinning, Unauthorized, SourceDisabled, Server, AlreadyEnrolled, SecureStorage }
sealed interface SendTestResult { Accepted(serverMessageId: String?, duplicate: Boolean); Queued(reason); Rejected(reason, httpStatus); NotEnrolled }
object NotificationAppPolicy { val blockedPackages: Set<String>; fun isSelectable(packageName: String): Boolean
                               fun isTelegramComponent(launcherClassName: String?): Boolean }
```

Semantics the implementation must keep:

| Item | Rule |
|---|---|
| `enroll` | Verify the token with a request that creates no message: `GET /v1/messages/<probe id>` with `Authorization: Bearer mgs_…` → 404 = valid, 401 = `Unauthorized`, 403 = `SourceDisabled`. Store the token encrypted (Tink AEAD, Keystore-wrapped keyset; if the Keystore cannot wrap it, fail with `SecureStorage` and store nothing — never a keyset in the clear). Set the SMS reconciler start point (`last_seen_id = MAX(_id)`, `enabled_at = now`) — never upload old SMS. |
| `unenroll` | Delete token, outbox, log, DataStore and Keystore aliases. Irreversible. Only the SMS sender rules (configuration, no secrets) are kept. Holds the uploader and both capture paths while wiping, so nothing captured meanwhile survives. |
| SMS sender rules | `SmsSenderRules.decide(rules, address)` runs in `SmsIngest` for both the broadcast and the inbox scan. `Skip` → the SMS is marked as seen and logged as `Skipped` (`lastError = "rule"`), never queued; its body still suppresses the SMS app's notification for 2 min. Skipped SMS do not count towards the reconciler's > 50 backlog. |
| `ForwardLogItem.title` | SMS: originating address as-is; notification: app label; never the body. |
| `ForwardStatus` | 202 → `Accepted`; 200 duplicate → `Duplicate`; network/429/5xx → `Retrying`; 413/415/422 → `Failed` (kept 7 days); 401/403 → `Paused` (queue kept, `ForwarderState.paused` set); local rule → `Skipped`. |
| Uploads | `POST /v1/messages`, JSON `{title, body, type: "sms"|"notification"|"test", priority, occurred_at (RFC 3339, whole seconds, local offset), meta{}}`, header `Idempotency-Key`; SMS keys are `"sms:" + hex(HMAC-SHA256(device_secret, subId‖addr‖tsMillis‖body))[:32]` where `‖` is U+0000 and `subId`/`tsMillis` are decimal (tsMillis = SMSC time of the first segment; inbox `date_sent`), notification and test keys a persisted random UUID. Fields are clipped to the server limits (title 256, body 12 000 code points, meta 16 × 256). Honour `Retry-After` (seconds or HTTP date); otherwise back off 30 s × 2ⁿ up to 30 min; the first retryable failure pushes the whole queue back, including items queued later (`retryNow` lifts it). No redirects, no logging interceptor. See `docs/api.md`. |
| `sendTest` | `type: "test"`, sent at once even while paused (doubles as a credential check; success clears `paused`). |
| SMS payload | `title` = originating address as-is (no contact lookup, no READ_CONTACTS), `occurred_at` = SMSC time, `meta.sim` = `"SIM 1"`/`"SIM 2"` on dual-SIM phones (slot index, no READ_PHONE_STATE), `meta.parts` for multipart, `meta.via = "reconciler"` when found by the inbox scan. |
| Reconciler | Start point `last_seen_id` = inbox MAX(_id) at enroll / SMS enable / first READ_SMS grant (never 0). Each scan only considers `date >= max(now − 72 h, enabled_at)`. Scans `_id > last_seen_id OR date > last_seen_date` (the provider reuses the `_id` of a deleted newest row). More than 50 candidates → nothing uploaded, `smsBacklog` set until `resolveSmsBacklog`. Matching against the broadcast is one to one (keyed fingerprint of sender + body, SMSC time ± 1 h): each inbox row pairs with at most one live capture, so a repeated identical SMS is still forwarded; the broadcast path drops only an exact redelivery (same SMSC time) or an SMS the scan found first. The seen record is written in the same transaction as the outbox / Skipped log row. Runs at boot / app update, app start and hourly with network. |
| Notifications | Only selected packages, never `NotificationAppPolicy.blockedPackages` or this app. Skips ongoing, foreground-service, group-summary, `progress`/`transport`/`call`/`service`, media and progress-bar notifications. Title `"<应用名> · <通知标题>"` (rule `includeTitle = false` → app name only), body `EXTRA_BIG_TEXT` → `EXTRA_TEXT` → text lines; MessagingStyle: newest message only (`"sender: text"` in groups). Dedupe (key + content hash) 10 min; 30 per app per minute, the rest merged into one low-priority `"<应用名> · N 条通知"` message (`meta.coalesced = N`). System placeholder text (or no title and no text) → body `系统隐藏了验证码内容`, `meta.redacted = true`. Text equal to an SMS captured in the last 2 min → `Skipped` (`sms_duplicate`). Notifications of SMS apps (default SMS app and known Messages apps) wait 5 s for the SMS broadcast before that check, and a title that hits an SMS sender `Skip` rule → `Skipped` (`sms_rule`). Unlisted Telegram forks (launcher activity in `org.telegram.*`) are never forwarded. `meta.pkg`, `meta.channel`. |
| Retention | Failed rows 7 days, pending rows 30 days or 5 000 rows (oldest dropped, log says `expired` / `queue_full`), accepted-key digests and SMS fingerprints 7 days, log 500 rows. |
| `OtpHealth` | Code SMS over the last 7 days: counted as realtime when delivered by `SMS_RECEIVED`, as reconciled when first seen by the inbox scan, as explained when the scan found one sent before the last `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (+1 min). Delay = first seen − SMSC time; median over broadcast deliveries, max over realtime and reconciled. |
| Alerts | Channel `forwarder_alerts` (needs `POST_NOTIFICATIONS`): one notification while paused (401/403), one "有 N 条消息未能转发" once the oldest pending item is 30 min old. Counts and reasons only, `setLocalOnly`, tap opens the app. |
| `setNotificationApps` | Filter through `NotificationAppPolicy.isSelectable` (Telegram clients and this app would loop). |

`FakeForwarderController` (in `:core`) implements everything in memory;
`FakeForwarderData` holds deterministic sample data (`Now` = 2026-11-16 10:20 JST)
for previews and screenshot tests.

`:forwarder` provides `com.drinkcat.nekopost.forwarder.AndroidForwarderController(context: Context, scope: CoroutineScope)`
(M1). Receivers, the listener and workers share one process-wide
`ForwarderGraph` with it:

| Piece | Implementation |
|---|---|
| `sms.SmsReceiver` | static `SMS_RECEIVED`, `android:permission="android.permission.BROADCAST_SMS"`; parses PDUs, merges by (address, subscription), persists under `goAsync()`, expedited upload |
| `sms.SmsReconciler` | `Telephony.Sms.Inbox` scan (see table above) |
| `notify.NekoNotificationListener` | `BIND_NOTIFICATION_LISTENER_SERVICE`; `requestRebind` on disconnect |
| `BootReceiver` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (protected broadcasts) → scan + flush |
| Outbox | Room `nekopost-forwarder.db` (outbox, log, sent-key digests, SMS fingerprints); payload = request JSON sealed with Tink AES-256-GCM, AAD = `nekopost/outbox/<row id>` |
| Keys | Tink keyset `fwd` in prefs `nekopost_fwd_keyset`, wrapped by Keystore key `nekopost_ks_fwd` (StrongBox when available, no user auth); token and 32-byte device secret sealed in prefs `nekopost_fwd_secrets` |
| Settings | DataStore `nekopost_forwarder` (enrollment, switches, apps, rules, start point, pause, last success/failure, backlog) |
| Uploads | WorkManager: `nekopost-upload` (expedited on API 31+, APPEND_OR_REPLACE, network), `nekopost-upload-later` (Retry-After / backoff), `nekopost-reconcile`, hourly `nekopost-reconcile-hourly`; no foreground service |
| TLS | OkHttp `CertificatePinner` with ISRG Root X1, X2 and Gen-Y Root YE, YR SPKI SHA-256 (`upload.ServerPins`); skipped after `EXPIRES_AT` = 2027-11-16 (fail open) — bump it every release |

Room's compiler runs through KSP; schema export is off until the first migration.

## 2. Enrollment QR link (`core.enroll.EnrollmentLink`)

```
nekopost://enroll?v=1&kind=source&url=<base>&id=<source id>&token=<mgs_…>&name=<display name>
```

Shared with the server (`internal/enroll`). Values are percent-encoded UTF-8
(spaces as `%20`; `+` also decodes to a space). Parameter order on output is
fixed as above.

```kotlin
EnrollmentLink.parse(raw: String, allowInsecureLocalhost: Boolean = false): EnrollmentParseResult
EnrollmentLink.parseOrNull(raw, allowInsecureLocalhost): EnrollmentLink?
EnrollmentLink.create(serverUrl, sourceId, token, displayName: String? = null, allowInsecureLocalhost = false): EnrollmentParseResult
link.serverUrl / sourceId / token / displayName / serverHost / toUriString()   // toString() hides the token
sealed interface EnrollmentParseResult { Valid(link); Invalid(error: EnrollmentError) }
enum class EnrollmentError { TooLong, NotNekopostLink, UnsupportedVersion, UnsupportedKind, MalformedQuery,
    DuplicateParameter, UnknownParameter, MissingParameter, InvalidServerUrl, InsecureServerUrl,
    InvalidSourceId, InvalidToken, InvalidName }
```

Rejected: other scheme/host (case-insensitive match of `nekopost://enroll?`),
fragments, non-printable-ASCII raw text, `v` ≠ `1`, `kind` ≠ `source`, unknown
or repeated parameters, broken percent-encoding or UTF-8, URL not
`https://host[:port]` (no user info, path, query, fragment or trailing slash),
`http` except `10.0.2.2` / `localhost` with `allowInsecureLocalhost = true`
(pass `BuildConfig.DEBUG`), source ID not `^[a-z0-9][a-z0-9_-]{0,31}$`, token
not `^mgs_[A-Za-z0-9_-]{20,}$`, name with control characters or over 128 code
points, links over 2048 characters. Missing/blank `name` falls back to the ID.

`core/src/test/resources/enroll-fixtures.json` is generated by the server's
link builder (`go test ./internal/enroll -run TestSharedFixture
-update-fixture`) and checked on both sides: the Go test compares it byte for
byte, `EnrollmentLinkFixtureTest` parses every server link (same fields,
identical re-encoding) and confirms the rejected inputs marked `appRejects`.
The server is never looser than the app: it also refuses names the app would
reject, links over 2048 characters, hosts that are not ASCII DNS names / IP
literals (punycode for IDN), and ports with leading zeros or out of range.

### 2.1 Device pairing link and pasted input

```
nekopost://enroll?v=1&kind=device&url=<base>&code=<22-char base64url pairing code>
```

Built by the server's `enroll.DeviceLink` (web panel / `POST /v1/admin/pairings`
`qr`, the Telegram bot's 「➕ 添加设备」, `nekopost device pair`). The Telegram bot
sends it as text inside a `<code>` block (tap to copy) next to the QR photo,
because the phone being paired is often the phone showing Telegram. The app's
pairing screen therefore accepts pasted text: either this link, or the manual
short code `XXXX-XXXX-XX` (10 characters of `0-9A-HJKMNP-TV-Z`, hyphens and
case ignored; the server URL is then entered separately or prefilled from the
source enrollment). The setup wizard routes a pasted or scanned `kind=device`
link to device pairing instead of rejecting it as an unsupported kind.

## 3. Fold model and layout (`core.ui.fold`)

```kotlin
enum class Posture { Cover, Flat, Tabletop, Book, Window }
data class FoldState(val posture: Posture, val hinge: Rect? = null /* window px */, val hingeAngle: Float? = null, val isSeparating: Boolean = false) {
    val isHalfOpened: Boolean; val hingeOrientation: HingeOrientation?
    companion object { Window; Cover; Flat; fun tabletop(hinge: Rect? = null); fun book(hinge: Rect? = null) }
}
fun deriveFoldState(feature: FoldFeature?, isFoldableDevice: Boolean, isExpandedWidth: Boolean, hingeAngle: Float? = null): FoldState
@Composable fun rememberFoldState(): FoldState                       // call once, at the top (MainActivity)
@Composable fun HingeAwareSplit(fold, axis: SplitAxis, modifier, fraction = 0.5f, gap = 0.dp, first, second)
```

Every screen takes `fold: FoldState` as a parameter and never calls
`rememberFoldState()` itself. `HingeAwareSplit` splits exactly at a separating
hinge (centre if bounds are unknown) and keeps `NekoTokens.Hinge.keepOut` (24dp)
free on both sides; otherwise it splits at `fraction`.

App shell (`app/ui/shell`): `paneModeFor(fold, contentWidth)` → `Single`
(cover / < 600dp), `TwoPane` (≥ 600dp), `TopBottom` (tabletop);
`navigationTypeFor(fold, windowWidth)` → bottom bar on cover and in tabletop,
collapsed rail on the open inner display. `NekopostShell(state: ShellUiState,
fold, destination, onDestinationChange, actions: ShellActions, memory: ShellMemory)`
is stateless; `ui.NekopostRoot(container: AppContainer, fold)` is the stateful
wrapper (collects the controller, re-checks permissions on every onResume and
reports them via `reportPermissions`, shows the setup wizard or the shell).

- List + detail screens (记录, 设置) use `ui.common.AdaptiveListDetail`, built on
  material3-adaptive's `ListDetailPaneScaffold` with a hand-made
  `PaneScaffoldDirective` (1 or 2 partitions from `PaneMode`, a separating hinge
  passed as `excludedBounds`). It is driven by hoisted state, not a navigator.
- `ShellMemory` (selected log ID, status/kind filters, settings entry, app
  search, log scroll position, debug-panel unlock) lives above every
  posture-dependent layout and is saved with `rememberSaveable` (IDs and enum
  names only). This is what keeps the selection across cover ⇄ inner ⇄ tabletop.
- Tabletop: 转发 and 设置 show the read-only forwarding monitor (health ring +
  conveyor of recent items, sender and category only) above the hinge; 记录
  shows the selected item there (or the monitor). Nothing clickable lies within
  ±24dp of the hinge; `ContinuityTest` checks this on the semantics tree.
- Setup wizard (`ui.setup`): `SetupSnapshot` + `statusOf(step)` are pure logic;
  `SetupWizard` is stateless; `SetupRoute` wires the system (runtime permission
  requests, settings deep links, Google code scanner, clipboard). The pasted or
  scanned enrollment link is kept in memory only and a link pasted from the
  clipboard is cleared from it after a successful `enroll`.
- SMS sender rules are edited in 设置 and stored by the forwarder
  (`setSmsSenderRules`, read back from `ForwarderState.smsSenderRules`).
- The 转发 backlog banner is driven by `ForwarderState.smsBacklog`: "全部补交"
  → `resolveSmsBacklog(true)`, "全部跳过" → `resolveSmsBacklog(false)`, "稍后"
  hides it until the backlog grows or the app restarts.
- Not yet wired in the UI: per-app notification rules (`setNotificationRule`)
  and a live "listener connected" flag (the wizard shows whether access is
  granted).

## 4. Theme and components (`core.ui.theme`, `core.ui.token`, `core.ui.component`)

`NekopostTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) { }`;
`NekopostTheme.colors.tone(StatusTone)` gives fixed status colours.
Tokens: `NekoTokens.{Seed, Light, Dark, Status, Spacing, Radius, Motion, Touch, Hinge, Breakpoint}`
(hand-written now; generated from `design/tokens.json` later).
Components: `NekoCard`, `SectionHeader`, `KeyValueRow`, `StatusPill`,
`ForwardStatusPill`, `SleepingCat`, `EmptyState`, `SegmentRing` (health ring),
`PostmarkStamp` + `StampLanding` (setup finish), `HoldToConfirmButton` (danger
actions, 1.5 s), `NekoIcons` (line icons the Material core set lacks). Cats
appear only in empty and idle states — never on errors, security or danger UI.
Motion: `core.ui.motion.LocalReducedMotion` (set from the system animator scale
in MainActivity, always true in screenshot tests) switches springs to snaps or
100 ms fades; there are no infinite animations.

## 5. Screenshots and CI

`app/src/test/.../ScreenshotMatrixTest` renders every screen (shell
destinations and their detail states, the setup wizard steps) for Fold8 cover
(`w475dp-h751dp-port-420dpi`), inner landscape flat (`w933dp-h704dp-land-420dpi`)
and inner portrait tabletop (`w704dp-h933dp-port-420dpi`, injected
`FoldState.tabletop()`), light and dark, stored at half resolution.
`WizardFlowTest` drives `SetupRoute` with `FakeForwarderController`;
`ContinuityTest` switches postures in place and checks selection, filters,
state restoration and the hinge keep-out. Compose UI tests register
`ComponentActivity` with Robolectric (`RegisterComponentActivityRule`) instead of
depending on `ui-test-manifest`. Reference images
live in `app/src/test/screenshots/`; CI records them while none are committed
and verifies afterwards (`./gradlew :app:recordRoborazziDebug` /
`verifyRoborazziDebug`).

`.github/workflows/android.yml`: `test` (unit + Robolectric + Roborazzi,
artifact `screenshots`), `build` (debug + unsigned release with R8,
exported-component allowlist check against `config/exported-components.txt`,
artifact `nekopost-debug-apk`), `lint`, and `release` (tags `app-v*` only,
repo secrets `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, no caches, draft GitHub release).
Debug builds are signed with the committed `keystore/debug.p12`
(password `android`, alias `androiddebugkey`) so debug APKs from different
runs install over each other. Because that key is public, debug builds are
a separate app (`com.drinkcat.nekopost.debug`, label "Nekopost Debug") that
can never update a release install; real-device runs with a real token use
a signed release from an `app-v*` tag.

Manifest rules: the launcher activity is our only exported component;
`allowBackup=false`; data extraction excludes everything; cleartext is off
(debug builds allow `10.0.2.2` and `localhost`). New exported components
(SmsReceiver with `BROADCAST_SMS`, the notification listener with
`BIND_NOTIFICATION_LISTENER_SERVICE`, …) must be added to the allowlist.

Screen security (design §8.3): `MainActivity` sets `FLAG_SECURE` and turns
off the Recents screenshot. Confirm actions (enroll, permission requests)
ignore gestures that passed through another app's window
(`TouchSecurity`), the unenroll sheet filters obscured touches on its
window, and the enrollment link field is a password-type field so keyboards
do not learn the token.

## 6. Server inbox and push additions (server 0.10.0–0.10.2, all additive)

| Where | Addition |
|---|---|
| `GET /v1/inbox`, `POST /v1/inbox/search`, `GET /v1/sync` items | `sender`: unmasked title (SMS address as submitted, or `"<app> · <title>"`), whitespace collapsed, ≤ 64 code points; a token right after a code keyword is always masked, and when `has_code` every code-shaped token in the title is masked too (except an SMS title that is a bare phone number or short code); `null` when the body is gone. `title` / `preview` stay masked. `app_only` (bool): Telegram is not part of this message; the App push decides `status`. `status_text` uses「已推送到 App」/「部分设备已收到推送」for app-only messages. `deliveries[].aux` (bool); `deliveries[].state` may be `skipped` (this device's push settings or it is the source device; not a failure). |
| `GET /v1/inbox` | `unread_count` (all retained unread messages); filters `?unread=1`, `?has_code=1` (combinable with the `before`/`before_id` cursor). |
| `GET /v1/inbox/{id}` | `?via=push` audits as `message.push_fetch` instead of `message.view`; `&ack=1` also acknowledges the push (same as `POST /v1/devices/self/acks` for that ID) before the item is built. |
| `GET /v1/devices/self` | `telegram_types`: `null` = Telegram receives every type, otherwise the sorted list it receives (empty = none); other types reach the user only through the App. `push_enabled`: the server has FCM configured. |
| `POST /v1/inbox/read`, `POST /v1/inbox/unread` (server 0.10.2) | Batch read state, `msg:read` scope, DPoP like `PATCH /v1/inbox/{id}`. Body either `{"ids":[…]}` (1–500 valid message IDs; duplicates counted once, unknown/deleted IDs skipped) or, for `/read` only, `{"all":true,"up_to_id":"m_…","has_code":bool}`: every unread message whose `(received_at, id)` is not newer than `up_to_id` (the newest message the list shows; later arrivals stay unread), `has_code:true` limiting it to code messages (the 验证码 chip; 未读/全部 send `false`). Mixing the two forms, `all` on `/unread`, unknown fields → `400 invalid_body`. `up_to_id` gone → `409 stale_list` (refresh, retry). `200 {"changed":n,"unread_count":u}`; repeating is a no-op (`changed:0`). One transaction, each changed message gets a new `seq` (seen by `/v1/sync` and `/v1/events`), one audit entry per batch. After a read batch the *other* devices get the `read` FCM signal for the changed IDs (newest 500 at most); the calling device clears its own notifications for the IDs it marked (for `all`: every listed message up to `up_to_id`) and takes `unread_count` for the badge. Rate limit per device: 60/min, burst 20 → `429 rate_limited` + `Retry-After`. No partial success: on any error nothing changed. |
| FCM data | `{v:"1", t:"read", ids:"m_…,m_…"}` when a message is marked read on another device (never sent to the device that marked it); `{v:"1", t:"gone", ids}` when a message is deleted (bot) or its body destroyed. NORMAL priority, ttl 1 h, ≤ 50 IDs per push, best effort (reconcile on sync as well). Existing `msg` / `ping` / `revoked` unchanged. |

App 0.3.1 (批量已读) uses the batch endpoints through `DeviceInbox.applyBatch`
(`inbox/BatchRead.kt` builds the bodies and applies an accepted change to the
loaded rows). 消息 overflow 「全部标为已读」 sends `all` bounded to the newest row
the list shows (`has_code` under the 验证码 chip; with a search it sends the
unread rows shown as `ids`). Long-press on a row starts 多选 (contextual bar
with the count, 全选/全不选 and ×; 标为已读 / 标为未读 under the list; back,
Esc or × leave it; on a single pane an open message closes first). Rows carry a
custom accessibility action 「多选」, and while selecting each row is a
checkbox. Changes are not optimistic: rows, the badge (`unread_count`) and
notifications change only after the server accepted the batch; a failure
keeps the selection and shows 「没有标记成功：…」. Selections over 500 go in
chunks of 500.
