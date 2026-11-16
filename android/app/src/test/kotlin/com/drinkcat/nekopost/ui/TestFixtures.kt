package com.drinkcat.nekopost.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.rules.RuleAction
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.motion.LocalReducedMotion
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.system.FoldDebugInfo
import com.drinkcat.nekopost.system.InstalledApp
import com.drinkcat.nekopost.ui.setup.DeviceTraits
import com.drinkcat.nekopost.ui.setup.SetupSnapshot
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows
import java.time.ZoneId

/** Galaxy Z Fold8 (wide) at the default 420 dpi; see design §10.10. */
data class Device(val name: String, val qualifiers: String, val fold: FoldState)

object Fold8 {
    val Cover = Device("cover", "w475dp-h751dp-port-420dpi", FoldState.Cover)
    val InnerFlat = Device("inner_flat", "w933dp-h704dp-land-420dpi", FoldState.Flat)
    val InnerTabletop = Device("inner_tabletop", "w704dp-h933dp-port-420dpi", FoldState.tabletop())
    val All = listOf(Cover, InnerFlat, InnerTabletop)
}

/**
 * Pins everything that would make renders non-deterministic: time zone, "now",
 * and motion (reduced, so captures never land mid-animation).
 */
@Composable
fun TestHarness(dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDisplayZone provides ZoneId.of("Asia/Tokyo"),
        LocalNow provides { FakeForwarderData.Now },
        LocalReducedMotion provides true,
    ) {
        NekopostTheme(darkTheme = dark, content = content)
    }
}

/** Sample data for the screens that FakeForwarderData does not cover. */
object Samples {
    val device = DeviceTraits(sdkInt = 37, isSamsung = true, isFoldable = true)

    val link: EnrollmentLink = EnrollmentLink.parseOrNull(
        "nekopost://enroll?v=1&kind=source&url=https%3A%2F%2Fosaka.drinkcat.com&id=fold8" +
            "&token=mgs_Q2F0c0FyZUxpcXVpZF9ub3RTb2xpZA&name=Fold8%20%E4%B8%BB%E5%8A%9B%E6%9C%BA",
    )!!

    val apps = listOf(
        InstalledApp("jp.naver.line.android", "LINE"),
        InstalledApp("com.sagawa.app", "佐川急便"),
        InstalledApp("jp.co.rakuten.bank", "楽天銀行"),
        InstalledApp("com.google.android.gm", "Gmail"),
        InstalledApp("jp.co.yamato.transport", "ヤマト運輸"),
        InstalledApp("com.mufg.bank", "三菱UFJ銀行"),
        InstalledApp("org.telegram.messenger", "Telegram"),
        InstalledApp("com.android.chrome", "Chrome"),
    )

    val rules = listOf(
        SmsSenderRule("+8190*", RuleAction.Forward),
        SmsSenderRule("*广告*", RuleAction.Skip),
        SmsSenderRule("10690*", RuleAction.Skip),
    )

    val foldDebug = FoldDebugInfo(
        widthPx = 2448,
        heightPx = 1848,
        widthDp = 933,
        heightDp = 704,
        density = 2.625f,
        densityDpi = 420,
        fontScale = 1.0f,
        widthClass = "EXPANDED (≥840)",
        heightClass = "MEDIUM (≥480)",
        foldingFeature = "state=FLAT orientation=VERTICAL isSeparating=false occlusion=NONE bounds=[1224,0,1224,1848]",
        posture = "Flat",
        hingeAngle = 180f,
    )

    fun snapshot(
        enrolled: Boolean = false,
        permissions: PermissionStatus = PermissionStatus.None,
        otp: OtpHealth = OtpHealth.Empty,
        smsBlocked: Boolean = false,
    ) = SetupSnapshot(
        enrollment = if (enrolled) FakeForwarderData.enrollment else null,
        permissions = permissions,
        otp = otp,
        device = device,
        smsBlocked = smsBlocked,
    )
}

/**
 * Registers ComponentActivity with Robolectric's package manager so
 * createAndroidComposeRule can launch it without the ui-test-manifest
 * artifact (which would add an exported activity to the debug APK).
 * Must wrap the compose rule (RuleChain.outerRule(...).around(composeRule)).
 */
class RegisterComponentActivityRule : TestWatcher() {
    override fun starting(description: Description) {
        val app: Application = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(app.packageManager)
            .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }
}
