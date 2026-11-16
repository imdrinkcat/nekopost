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
) { val isEnrolled: Boolean }
fun ForwarderState.health(): ForwarderHealth   // NotEnrolled, Idle, NeedsPermission, Paused, Backlog, Healthy

data class Enrollment(val serverUrl: String, val sourceId: String, val sourceName: String, val enrolledAt: Instant) { val serverHost: String }
data class PermissionStatus(val receiveSms: Boolean, val readSms: Boolean, val notificationListener: Boolean,
                            val postNotifications: Boolean, val ignoringBatteryOptimizations: Boolean) { val smsReady; val allGranted }
data class QueueStats(val pending: Int = 0, val failed: Int = 0, val oldestPendingAt: Instant? = null)
data class DeliveryEvent(val at: Instant, val kind: ForwardKind)
data class FailureEvent(val at: Instant, val reason: FailureReason, val httpStatus: Int? = null, val detail: String? = null)
enum class FailureReason { Network, CertificatePinning, Unauthorized, SourceDisabled, Rejected, RateLimited, Server }
enum class PauseReason { Unauthorized, SourceDisabled }

enum class ForwardKind { Sms, Notification, Test }
enum class ForwardStatus { Queued, Sending, Retrying, Accepted, Duplicate, Failed, Paused, Skipped }
data class ForwardLogItem(val id: Long, val kind: ForwardKind, val title: String, val sourcePackage: String? = null,
                          val createdAt: Instant, val status: ForwardStatus, val attempts: Int = 0,
                          val lastHttpStatus: Int? = null, val lastError: String? = null,
                          val serverMessageId: String? = null, val updatedAt: Instant = createdAt)

data class OtpHealth(val windowDays: Int = 7, val realtimeCount: Int = 0, val reconciledCount: Int = 0,
                     val medianDelay: Duration? = null, val maxDelay: Duration? = null, val lastCodeAt: Instant? = null) {
    val verdict: OtpVerdict   // NoData, NoDelay, DelayDetected (any code SMS found only by the reconciler)
}

sealed interface EnrollResult { Success(enrollment) ; Failure(reason: EnrollFailure, httpStatus: Int?) }
enum class EnrollFailure { Network, CertificatePinning, Unauthorized, SourceDisabled, Server, AlreadyEnrolled }
sealed interface SendTestResult { Accepted(serverMessageId: String?, duplicate: Boolean); Queued(reason); Rejected(reason, httpStatus); NotEnrolled }
object NotificationAppPolicy { val blockedPackages: Set<String>; fun isSelectable(packageName: String): Boolean }
```

Semantics the implementation must keep:

| Item | Rule |
|---|---|
| `enroll` | Verify the token with a request that creates no message: `GET /v1/messages/<probe id>` with `Authorization: Bearer mgs_…` → 404 = valid, 401 = `Unauthorized`, 403 = `SourceDisabled`. Store the token encrypted (Tink AEAD, Keystore-wrapped keyset). Set the SMS reconciler start point (`last_seen_id = MAX(_id)`, `enabled_at = now`) — never upload old SMS. |
| `unenroll` | Delete token, outbox, log, DataStore and Keystore aliases. Irreversible. |
| `ForwardLogItem.title` | SMS: originating address as-is; notification: app label; never the body. |
| `ForwardStatus` | 202 → `Accepted`; 200 duplicate → `Duplicate`; network/429/5xx → `Retrying`; 413/415/422 → `Failed` (kept 7 days); 401/403 → `Paused` (queue kept, `ForwarderState.paused` set); local rule → `Skipped`. |
| Uploads | `POST /v1/messages`, JSON `{title, body, type: "sms"|"notification", priority, occurred_at (RFC 3339), meta{}}`, header `Idempotency-Key`; SMS keys are `"sms:" + hex(HMAC-SHA256(device_secret, subId‖addr‖tsMillis‖body))[:32]`, notification keys a persisted random UUID. Honour `Retry-After`. See `docs/api.md`. |
| `OtpHealth` | Code SMS over the last 7 days: counted as realtime when delivered by `SMS_RECEIVED`, as reconciled when first seen by the inbox scan. |
| `setNotificationApps` | Filter through `NotificationAppPolicy.isSelectable` (Telegram clients and this app would loop). |

`FakeForwarderController` (in `:core`) implements everything in memory;
`FakeForwarderData` holds deterministic sample data (`Now` = 2026-11-16 10:20 JST)
for previews and screenshot tests.

`:forwarder` provides `com.drinkcat.nekopost.forwarder.AndroidForwarderController(context: Context, scope: CoroutineScope)`.
In M0 it is an in-memory stub; M1 fills in SmsReceiver, SmsReconciler,
NotificationListener, the Room outbox (Tink AEAD payloads, AAD = row id),
the WorkManager + OkHttp uploader with ISRG Root X1/X2 SPKI pins (1-year
expiry, fail-open after), HMAC idempotency keys and per-app rules — keeping
the class name and constructor. Room's compiler runs through KSP (already wired).

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
fold, destination, onDestinationChange, actions: ShellActions)` is stateless;
`NekopostRoot(controller, fold, appVersion)` is the stateful wrapper.

## 4. Theme and components (`core.ui.theme`, `core.ui.token`, `core.ui.component`)

`NekopostTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) { }`;
`NekopostTheme.colors.tone(StatusTone)` gives fixed status colours.
Tokens: `NekoTokens.{Seed, Light, Dark, Status, Spacing, Radius, Motion, Touch, Hinge, Breakpoint}`
(hand-written now; generated from `design/tokens.json` later).
Components: `NekoCard`, `SectionHeader`, `KeyValueRow`, `StatusPill`,
`ForwardStatusPill`, `SleepingCat`, `EmptyState`. Cats appear only in empty and
idle states — never on errors, security or danger UI.

## 5. Screenshots and CI

`app/src/test/.../ShellScreenshotTest` renders the shell for Fold8 cover
(`w475dp-h751dp-port-420dpi`), inner landscape flat (`w933dp-h704dp-land-420dpi`)
and inner portrait tabletop (`w704dp-h933dp-port-420dpi`, injected
`FoldState.tabletop()`), light and dark, every destination. Reference images
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
runs install over each other.

Manifest rules: the launcher activity is our only exported component;
`allowBackup=false`; data extraction excludes everything; cleartext is off
(debug builds allow `10.0.2.2` and `localhost`). New exported components
(SmsReceiver with `BROADCAST_SMS`, the notification listener with
`BIND_NOTIFICATION_LISTENER_SERVICE`, …) must be added to the allowlist.
