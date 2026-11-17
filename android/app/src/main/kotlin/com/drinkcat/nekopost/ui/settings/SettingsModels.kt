package com.drinkcat.nekopost.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.lock.FoldCloseBehavior
import java.time.Instant

/** Groups of the settings list, in order; [Danger] is always last. */
enum class SettingsGroupKind(@param:StringRes val label: Int) {
    Messages(R.string.settings_section_messages),
    Forwarding(R.string.settings_section_forwarding),
    Security(R.string.settings_section_security),
    Maintenance(R.string.settings_section_maintenance),
    Danger(R.string.settings_section_danger),
}

/**
 * Entries of 设置. [Account] is the identity card at the top of the list (not a
 * row); [Setup] opens the wizard directly and [Unenroll] opens its sheet
 * directly, so neither has a detail page.
 */
enum class SettingsItem(
    @param:StringRes val title: Int,
    val icon: ImageVector,
    val section: SettingsGroupKind,
    val danger: Boolean = false,
) {
    Account(R.string.settings_account, Icons.Filled.Person, SettingsGroupKind.Messages),
    Notifications(R.string.settings_notifications, Icons.Filled.Notifications, SettingsGroupKind.Messages),
    Apps(R.string.settings_apps, NekoIcons.PostBox, SettingsGroupKind.Forwarding),
    SmsRules(R.string.settings_sms_rules, NekoIcons.Sms, SettingsGroupKind.Forwarding),
    AppLock(R.string.settings_security, Icons.Filled.Lock, SettingsGroupKind.Security),
    Admin(R.string.settings_admin, NekoIcons.Server, SettingsGroupKind.Security),
    Setup(R.string.settings_setup, Icons.Filled.Build, SettingsGroupKind.Maintenance),
    About(R.string.settings_about, Icons.Filled.Info, SettingsGroupKind.Maintenance),
    Unenroll(R.string.settings_unenroll, Icons.Filled.Delete, SettingsGroupKind.Danger, danger = true),
    ;

    /** Rows that act at once instead of opening a page. */
    val isAction: Boolean get() = this == Setup || this == Unenroll
}

/** Pages inside 设置 › 设备与管理. */
enum class AdminPage { Devices, AddDevice, Sources, Keys, Audit, Server }

/** 设置 › 应用锁 as rendered. */
@Immutable
data class LockSettingsUi(
    val enabled: Boolean = true,
    /** The phone has a secure lock screen (PIN, pattern, password). */
    val secure: Boolean = true,
    val graceSeconds: Int = 30,
    val idleMinutes: Int = 5,
    val foldClose: FoldCloseBehavior = FoldCloseBehavior.Curtain,
    /** Show 「合上手机时」 (only on foldables). */
    val foldable: Boolean = false,
    /** Something is enrolled or paired (otherwise the lock has nothing to protect yet). */
    val armed: Boolean = true,
    /** 展开时开信封 (§10.4 ⑫), listed with the other fold behaviours. */
    val envelope: Boolean = true,
)

/** This phone as a receiving device (`GET /v1/devices/self`); null when not paired. */
@Immutable
data class ThisDeviceUi(
    val name: String,
    val serverHost: String,
    val deviceId: String,
    val canRead: Boolean,
    val canSend: Boolean,
    val canAdmin: Boolean,
    /** null until the server answered. */
    val pushState: String? = null,
    /** null = Telegram receives every type; otherwise the types it receives (empty = none). */
    val telegramTypes: List<String>? = null,
    /** The server answered `GET /v1/devices/self` (so [telegramTypes] is meaningful). */
    val serverKnown: Boolean = false,
    val pushEnabled: Boolean = true,
)

/** 设置 › 消息通知 as rendered. */
@Immutable
data class NotifySettingsUi(
    /** POST_NOTIFICATIONS granted and notifications enabled for the app. */
    val permission: Boolean = true,
    val showContent: Boolean = true,
    val testing: Boolean = false,
    val testResult: String? = null,
)

enum class DeviceRole { Full, Receiver, Sender, Other }

@Immutable
data class AdminDevice(
    val id: String,
    val name: String,
    val model: String,
    val role: DeviceRole,
    val pushState: String,
    val revoked: Boolean,
    val suspended: Boolean,
    val self: Boolean,
)

@Immutable
data class AdminSource(val id: String, val name: String, val enabled: Boolean)

@Immutable
data class AdminKey(val name: String, val prefix: String)

@Immutable
data class AuditLine(val actor: String, val action: String, val result: String)

@Immutable
data class ServerStatusUi(
    val overall: String,
    val components: List<Pair<String, String>>,
    val received: Int? = null,
    val delivered: Int? = null,
    val filtered: Int? = null,
    val version: String? = null,
    val timezone: String? = null,
    val retentionDays: Int? = null,
)

/** The role a new device gets (POST /v1/admin/pairings `role`). */
enum class PairingRole(val wire: String) { Receiver("receiver"), Sender("sender"), Full("full") }

/** A pairing created on this phone (PairingDesk ⑬). */
@Immutable
data class PairingUi(
    val id: String,
    val name: String,
    val shortCode: String,
    val qr: ImageBitmap? = null,
    /** open / claimed / done / rejected / contested / expired. */
    val state: String = "open",
    /** What the new device calls itself once it claimed the code. */
    val claimedBy: String? = null,
    val choices: List<String> = emptyList(),
    val deciding: Boolean = false,
    val result: String? = null,
    /** The code stops working at this time (5 minutes after creation). */
    val expiresAt: Instant? = null,
    /** Last status poll: the desk recomposes with it, which keeps the countdown current. */
    val checkedAt: Instant? = null,
)

/** 设置 › 设备与管理 as rendered; lists are null until loaded. */
@Immutable
data class AdminUi(
    val devices: List<AdminDevice>? = null,
    val sources: List<AdminSource>? = null,
    val keys: List<AdminKey>? = null,
    val auditOk: Boolean? = null,
    val audit: List<AuditLine>? = null,
    val server: ServerStatusUi? = null,
    val busy: Boolean = false,
    val feedback: String? = null,
    val pairing: PairingUi? = null,
    /** A new admin credential or rotated source token: shown once. */
    val secret: String? = null,
)

/** Events of the 设备与管理 pages. */
@Immutable
data class AdminActions(
    val onLoad: (AdminPage) -> Unit = {},
    val onAddDevice: (name: String, role: PairingRole, source: String?) -> Unit = { _, _, _ -> },
    val onTestPush: (String) -> Unit = {},
    val onRevokeDevice: (String) -> Unit = {},
    val onCreateSource: (id: String, name: String) -> Unit = { _, _ -> },
    val onSetSourceEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onRotateSource: (String) -> Unit = {},
    val onDeleteSource: (String) -> Unit = {},
    val onCreateKey: (String) -> Unit = {},
    val onRevokeKey: (String) -> Unit = {},
    val onChoosePairing: (String) -> Unit = {},
    val onClosePairing: () -> Unit = {},
    val onCancelPairing: () -> Unit = {},
    val onDismissSecret: () -> Unit = {},
    val onCopySecret: (String) -> Unit = {},
)

/** What 解除配对 removes when both identities exist. */
enum class UnpairTarget { Device, Forwarder, Both }

/** Events of the settings pages beyond the forwarder ones in ShellActions. */
@Immutable
data class SettingsActions(
    val onLockEnabled: (Boolean) -> Unit = {},
    val onLockGrace: (Int) -> Unit = {},
    val onIdleLock: (Int) -> Unit = {},
    val onFoldClose: (FoldCloseBehavior) -> Unit = {},
    val onOpenSecuritySettings: () -> Unit = {},
    val onNotificationContent: (Boolean) -> Unit = {},
    val onEnvelope: (Boolean) -> Unit = {},
    val onTestPush: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onPairThisPhone: () -> Unit = {},
    /** Removes the receiving-device pairing (DELETE /v1/devices/self). */
    val onUnpairDevice: () -> Unit = {},
    val admin: AdminActions = AdminActions(),
)
