package com.drinkcat.nekopost.system

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.lock.ExternalKind
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.Signature
import kotlin.coroutines.resume

/** How a BiometricPrompt ended, mapped to what the UI should say (nothing for a cancel). */
sealed interface AuthOutcome {
    data class Success(val signature: Signature?) : AuthOutcome

    /** The user cancelled or pressed the negative button: say nothing. */
    data object Cancelled : AuthOutcome

    /** Too many attempts. */
    data object Lockout : AuthOutcome

    /** No screen lock or no strong biometric enrolled. */
    data object Unavailable : AuthOutcome

    data class Failed(val message: String) : AuthOutcome
}

/**
 * The one BiometricPrompt helper of the app (unlock, 值守台 翻牌, k_reauth admin
 * operations). The device-credential screen is a separate activity on One UI,
 * so every prompt is registered as an external activity first.
 */
object DeviceAuth {
    const val UNLOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when the device has a secure lock screen the app lock can rely on. */
    fun canUnlock(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val biometric = context.getSystemService(BiometricManager::class.java)
        return keyguard?.isDeviceSecure == true &&
            biometric?.canAuthenticate(UNLOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Biometric or screen lock (BIOMETRIC_STRONG | DEVICE_CREDENTIAL). Returns a
     * signal to cancel the prompt (e.g. when the screen leaves composition).
     */
    fun unlock(context: Context, title: String, subtitle: String?, onResult: (AuthOutcome) -> Unit): CancellationSignal {
        val signal = CancellationSignal()
        val release = ExternalGuards.expect(ExternalKind.Biometric)
        try {
            val builder = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setAllowedAuthenticators(UNLOCK_AUTHENTICATORS)
                .setConfirmationRequired(false)
            if (subtitle != null) builder.setSubtitle(subtitle)
            builder.build().authenticate(signal, context.mainExecutor, callback(release, onResult))
        } catch (e: RuntimeException) {
            release()
            onResult(AuthOutcome.Unavailable)
        }
        return signal
    }

    /**
     * k_reauth: a strong biometric unlocks the hardware-bound signing key for one
     * admin operation (design §3.7). No device-credential fallback.
     */
    suspend fun authorize(context: Context, signature: Signature): Signature? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val release = ExternalGuards.expect(ExternalKind.Biometric)
        fun finish(value: Signature?) {
            release()
            if (continuation.isActive) continuation.resume(value)
        }
        try {
            BiometricPrompt.Builder(context)
                .setTitle(context.getString(R.string.auth_admin_title))
                .setSubtitle(context.getString(R.string.auth_admin_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton(context.getString(R.string.action_cancel), context.mainExecutor) { _, _ -> finish(null) }
                .build()
                .authenticate(
                    BiometricPrompt.CryptoObject(signature),
                    signal,
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            finish(result.cryptoObject?.signature)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            finish(null)
                        }
                    },
                )
        } catch (e: RuntimeException) {
            finish(null)
        }
    }

    private fun callback(release: () -> Unit, onResult: (AuthOutcome) -> Unit) =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                release()
                onResult(AuthOutcome.Success(result.cryptoObject?.signature))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                release()
                onResult(outcomeFor(errorCode, errString.toString()))
            }
        }

    fun outcomeFor(errorCode: Int, message: String): AuthOutcome = when (errorCode) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
        -> AuthOutcome.Cancelled
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
        -> AuthOutcome.Lockout
        BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        -> AuthOutcome.Unavailable
        else -> AuthOutcome.Failed(message)
    }
}
