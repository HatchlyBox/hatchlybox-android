package com.hatchlybox.nightly

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opening an app the way the user does, so they can follow it: home, the library swiped up, the light flowing
 * to the app's icon (or straight to it when it's on the home page in view), and a press there. While an agent
 * has the screen, its gold light does it; otherwise the create box's gold thread does.
 */
object Reach {
    suspend fun open(context: Context, m: MakerModel, item: LibItem) = withContext(Dispatchers.Main) {
        val s = HandsService.instance
        val cursor = s != null && AgentScreen.active && Layer.up
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        m.used(item.key)
        // Home first: the home button, where the thumb would press it (or, without the hands, Hatchlybox to the front).
        if (!Gold.front) {
            if (cursor) {
                delay(Layer.moveTo(s!!, w / 2f, h - 60f) + 80); Layer.ring(s)
                s.global(AccessibilityService.GLOBAL_ACTION_HOME)
            } else runCatching {
                context.startActivity(Intent(context.applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            withTimeoutOrNull(2500) { while (!Gold.front) delay(40) }
            delay(350)
        }
        if (!Gold.front || (!cursor && !(Gold.enabled && Gold.shown))) { item.open(); return@withContext }
        AgentChat.shown = false
        // On the home page in view: pressed right there. Otherwise up comes the library, scrolled to it.
        var key = item.key
        if (m.library || !inView(Gold.screen[key]?.invoke(), w, h)) {
            key = "lib:" + item.key
            if (!m.library) {
                // A swipe up from the bottom, as the library is opened by hand.
                if (cursor) { delay(Layer.moveTo(s!!, w / 2f, h * 0.86f) + 60); Layer.slide(s, w / 2f, h * 0.4f, 380) }
                m.library = true
            }
            m.reveal = item.key
        }
        val r = still(key, w, h)
        if (r == null) { m.reveal = null; item.open(); return@withContext }
        if (cursor) { delay(Layer.moveTo(s!!, r.center.x, r.center.y) + 140); Layer.ring(s); delay(180) }
        else Gold.fly(null, key)
        m.reveal = null
        item.open()
    }

    private fun inView(r: Rect?, w: Float, h: Float) = r != null && r.width > 0 && r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h

    /** Where the icon is once it has stopped moving (the library sliding up, the list scrolling), or null. */
    private suspend fun still(key: String, w: Float, h: Float): Rect? {
        var last: Rect? = null
        return withTimeoutOrNull(3500) {
            while (true) {
                delay(90)
                val r = Gold.screen[key]?.invoke()
                if (inView(r, w, h) && r == last) return@withTimeoutOrNull r
                last = r
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }
}
