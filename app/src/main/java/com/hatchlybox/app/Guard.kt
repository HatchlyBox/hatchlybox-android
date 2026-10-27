package com.hatchlybox.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

// What actions need from the screen in front: Android's permissions, asked when an action first needs one,
// and the user's identity for the sensitive actions, checked the way they chose in Settings.

object Front {
    private var activity = WeakReference<ComponentActivity>(null)
    private var permissions: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null
    private var screens: ActivityResultLauncher<android.content.Intent>? = null
    private var screenPending: CompletableDeferred<Boolean>? = null

    /** From MainActivity.onCreate (before it starts: that's when launchers can be registered). */
    fun attach(a: ComponentActivity) {
        activity = WeakReference(a)
        permissions = a.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pending?.complete(it); pending = null }
        screens = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { screenPending?.complete(it.resultCode == Activity.RESULT_OK); screenPending = null }
    }

    /** Opens a system screen that answers OK or not (the screen lock check on older phones). */
    suspend fun confirm(intent: android.content.Intent): Boolean {
        val launcher = screens ?: return false
        val d = CompletableDeferred<Boolean>()
        screenPending?.cancel(); screenPending = d
        launcher.launch(intent)
        return d.await()
    }
    val current: ComponentActivity? get() = activity.get()

    fun has(c: Context, vararg p: String) = p.all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    /** Asks for those Android permissions (the ones not given yet); true when all are given. */
    suspend fun ask(c: Context, vararg p: String): Boolean {
        if (has(c, *p)) return true
        val launcher = permissions ?: return false
        val d = CompletableDeferred<Map<String, Boolean>>()
        pending?.cancel(); pending = d
        launcher.launch(p.filter { !has(c, it) }.toTypedArray())
        return d.await().let { has(c, *p) }
    }
}

/** How the sensitive actions are confirmed: the user's choice, kept on this phone. */
object Guard {
    /** biometric: fingerprint or face; credential: the screen lock (PIN, pattern, password); either; none. */
    var method by mutableStateOf("either")
    private fun prefs(c: Context) = c.getSharedPreferences("guard", Context.MODE_PRIVATE)
    fun load(c: Context) { method = prefs(c).getString("method", "either") ?: "either" }
    fun choose(c: Context, m: String) { method = m; prefs(c).edit().putString("method", m).apply() }

    val choices = listOf(
        "either" to "Fingerprint, face or screen lock", "biometric" to "Fingerprint or face only",
        "credential" to "Screen lock only (PIN, pattern or password)", "none" to "Don't ask (not recommended)",
    )

    /** What this phone can do: some have no fingerprint or face, or no screen lock at all. */
    fun available(c: Context, m: String): Boolean {
        if (m == "none") return true
        if (Build.VERSION.SDK_INT < 30) return true
        val bm = c.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(authenticators(m)) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun authenticators(m: String): Int = when (m) {
        "biometric" -> BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        "credential" -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else -> BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    /** Checks it's the phone's owner before a sensitive action; true to go ahead. */
    suspend fun verify(what: String): Boolean {
        if (method == "none") return true
        val a: Activity = Front.current ?: return false
        // Before Android 10 the prompt can't offer the screen lock: Android's own screen for it instead.
        if (Build.VERSION.SDK_INT < 29 && method != "biometric" || Build.VERSION.SDK_INT < 28) {
            val km = a.getSystemService(android.app.KeyguardManager::class.java) ?: return false
            if (!km.isDeviceSecure) return true
            @Suppress("DEPRECATION") val i = km.createConfirmDeviceCredentialIntent("Confirm it's you", what) ?: return true
            return Front.confirm(i)
        }
        return suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            val b = BiometricPrompt.Builder(a).setTitle("Confirm it's you").setSubtitle(what)
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(authenticators(method))
            else if (method != "biometric" && Build.VERSION.SDK_INT >= 29) @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else b.setNegativeButton("Cancel", a.mainExecutor) { _, _ -> if (cont.isActive) cont.resume(false) }
            runCatching {
                b.build().authenticate(cancel, a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { if (cont.isActive) cont.resume(true) }
                    override fun onAuthenticationError(code: Int, msg: CharSequence?) { if (cont.isActive) cont.resume(false) }
                })
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }
}
