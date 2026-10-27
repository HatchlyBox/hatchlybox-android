package com.hatchlybox.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Hatchlybox's hands in other apps, when the user switches it on (Android's Accessibility settings): press
 * a button in front (WhatsApp's Send, Settings' Force stop), go home, take a screenshot, lock the phone. Each
 * press is shown with the gold thread drawn over the app, to the button it presses. It reads the screen only
 * while an action runs, and keeps nothing.
 */
class HandsService : AccessibilityService() {
    companion object {
        @Volatile var instance: HandsService? = null
        val on get() = instance != null

        /** Switched on in Android's settings (even before the service has started). */
        fun enabled(c: Context): Boolean {
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(c.packageName + "/") && it.endsWith("HandsService") }
        }

        /**
         * Android 13 and later grey out accessibility for apps installed from a file ("Restricted setting") until
         * the user allows it in the app's info (⋮ → Allow restricted settings). No public API says so: this reads
         * the app op behind it, and where that isn't readable, guesses from how the app was installed.
         */
        fun restricted(c: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 33 || enabled(c)) return false
            val mode = runCatching {
                c.getSystemService(android.app.AppOpsManager::class.java)
                    .unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), c.packageName)
            }.getOrNull()
            if (mode != null) return mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
            val source = runCatching { c.packageManager.getInstallSourceInfo(c.packageName).packageSource }.getOrNull()
            return source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun global(action: Int) = performGlobalAction(action)

    /**
     * Waits (up to ms) for something in the app in front: by view id ("com.whatsapp:id/send"), or by its text
     * or description (any of the words given, whole, in any case), and returns it.
     */
    suspend fun find(ids: List<String>, words: List<String>, ms: Long = 5000): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }
            for (root in roots) {
                for (id in ids) runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull { it.isVisibleToUser }?.let { return it }
                for (w in words) {
                    val found = runCatching { root.findAccessibilityNodeInfosByText(w) }.getOrNull().orEmpty()
                    found.firstOrNull { n ->
                        n.isVisibleToUser && listOfNotNull(n.text, n.contentDescription).any { it.toString().trim().equals(w, ignoreCase = true) }
                    }?.let { return it }
                }
            }
            delay(150)
        }
        return null
    }

    /** Presses it (or the nearest pressable thing around it), with the gold thread drawn to it first. */
    suspend fun press(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        withContext(Dispatchers.Main) { thread(r) }
        delay(560)
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        // Not pressable as a view: a tap where it is.
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    suspend fun pressFirst(ids: List<String>, words: List<String>, ms: Long = 5000): Boolean = find(ids, words, ms)?.let { press(it) } ?: false

    /** The gold thread over whatever is on screen, from the bottom middle to that place. */
    fun thread(to: Rect) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = ThreadView(this, to)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(v, lp) }.onSuccess { v.play { runCatching { wm.removeView(v) } } }
    }

    /** The thread, drawn with Android's own canvas (outside Compose): travel, then a ring where it lands. */
    private class ThreadView(c: Context, val to: Rect) : View(c) {
        private var p = 0f
        private var land = 0f
        private val dp = c.resources.displayMetrics.density
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFE8B84A.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 1.6f * dp }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        fun play(done: () -> Unit) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600; interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { p = it.animatedValue as Float; invalidate() }
                doOnEnd {
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 480
                        addUpdateListener { land = it.animatedValue as Float; invalidate() }
                        doOnEnd(done)
                        start()
                    }
                }
                start()
            }
        }

        private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { f() }
        })

        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2).also { getLocationOnScreen(it) }
            val ax = width / 2f; val ay = height - 24 * dp
            val bx = to.exactCenterX() - loc[0]; val by = to.exactCenterY() - loc[1]
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            val dx = bx - ax; val dy = by - ay; val dist = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val lift = (dist * 0.22f).coerceAtMost(220 * dp) * (if (dx >= 0) 1 else -1)
            val path = Path().apply { moveTo(ax, ay); quadTo(mx - dy / dist * lift, my + dx / dist * lift, bx, by) }
            val pm = PathMeasure(path, false)
            val len = pm.length
            val fade = 1f - land
            val head = len * p
            val tail = (head - len * 0.38f).coerceAtLeast(0f) + len * 0.38f * land
            if (head > tail) {
                val seg = Path(); pm.getSegment(tail, head, seg, true)
                val a = FloatArray(2); val b = FloatArray(2); pm.getPosTan(tail, a, null); pm.getPosTan(head, b, null)
                glow.alpha = (26 * fade).toInt(); glow.strokeWidth = 9 * dp; canvas.drawPath(seg, glow)
                glow.alpha = (46 * fade).toInt(); glow.strokeWidth = 4.5f * dp; canvas.drawPath(seg, glow)
                line.shader = LinearGradient(a[0], a[1], b[0], b[1], intArrayOf(0x00B98516, ((230 * fade).toInt() shl 24) or 0xE8B84A, ((255 * fade).toInt() shl 24) or 0xFFF4C8),
                    null, Shader.TileMode.CLAMP)
                canvas.drawPath(seg, line)
            }
            if (p < 1f) {
                val t = FloatArray(2); pm.getPosTan(head, t, null)
                dot.color = 0x40E8B84A; canvas.drawCircle(t[0], t[1], 6 * dp, dot)
                dot.color = 0xFFFFF4C8.toInt(); canvas.drawCircle(t[0], t[1], 2 * dp, dot)
            }
            if (land > 0f) {
                glow.alpha = (140 * (1 - land)).toInt(); glow.strokeWidth = 1.5f * dp
                canvas.drawCircle(bx, by, 6 * dp + 22 * dp * land, glow)
            }
        }
    }
}

/** Words a few languages use for buttons the hands press. */
internal object ButtonWords {
    val send = listOf("Send", "Enviar", "Envoyer", "Senden", "Invia", "Verzenden", "Wyślij")
    val forceStop = listOf("Force stop", "Forçar paragem", "Forçar parada", "Forzar detención", "Forcer l'arrêt", "Beenden erzwingen", "Forza interruzione", "Forceren stoppen", "Wymuś zatrzymanie", "Forzar cierre")
    val ok = listOf("OK", "Ok", "Force stop", "Forçar paragem", "Forçar parada", "Forzar detención", "Forcer l'arrêt", "Beenden erzwingen", "Forza interruzione", "Aceptar", "Confirmar")
    fun lower(l: List<String>) = l.map { it.lowercase(Locale.ROOT) }
}
