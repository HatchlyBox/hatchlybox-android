package com.hatchlybox.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin

// The gold thread shows what an action is doing. While it works, a light circles the create box. When it's
// done, the light leaves the box for where the result is: the app's icon or widget it opened, or the
// message with the answer, whose edges then shine all around so the eye goes there. Something done in
// another app ends in a ring of light high on the screen. Settings → Create box can switch it off.

object Gold {
    /** Places a thread can go: key → bounds in the window, and their corners (px), to draw around them. */
    val targets = mutableStateMapOf<String, Rect>()
    val corners = mutableMapOf<String, Float>()
    class Flight(val from: Offset, val to: Offset, val around: String?, val arrived: CompletableDeferred<Unit>)
    var flight by mutableStateOf<Flight?>(null)
    /** The box a light is circling while its action works, or null. */
    var working by mutableStateOf<String?>(null)
    /** The overlay is on screen (the home screen or the library): without it, actions just run. */
    var shown = false
    /** Hatchlybox is the app in front (resumed). */
    @Volatile var front = false
    /** Where each target is on the whole screen, measured now (the gold cursor over other apps works in those pixels). */
    val screen = mutableMapOf<String, () -> Rect?>()
    /** The create box in use (home or library): threads start from its button, and circle the box around it. */
    var active = "send"
    /** Settings → Create box → Gold thread. */
    var enabled by mutableStateOf(true)
    /** The message with an action's answer (Messages marks it). */
    const val ANSWER = "notice"

    private fun box(key: String) = "box:$key"
    private val on get() = shown && enabled && android.animation.ValueAnimator.areAnimatorsEnabled()

    /** Circles the box while [work] runs (the local model choosing, an action working), then stops. */
    suspend fun <T> around(from: String? = null, work: suspend () -> T): T {
        val key = box(from ?: active)
        if (!on || targets[key] == null) return work()
        working = key
        try { return work() } finally { if (working == key) working = null }
    }

    /** Sends a thread from one target to another (null or unknown: up, to a ring high on the screen); returns as it lands. */
    suspend fun fly(from: String?, to: String?) {
        if (!on) return
        val start = targets[from ?: active]?.center ?: return
        val end = to?.let { targets[it]?.center }
        val f = Flight(start, end ?: Offset(start.x, (start.y * 0.3f).coerceAtLeast(120f)), null, CompletableDeferred())
        flight = f
        withTimeoutOrNull(1500) { f.arrived.await() }
    }

    /** Takes the light from the box to the answer once it's on screen, and lights its edges all around. */
    suspend fun toAnswer(from: String? = null) {
        if (!on) return
        val start = (targets[box(from ?: active)] ?: targets[from ?: active])?.let { Offset(it.center.x, it.top) } ?: return
        // The message appears a frame or two after it's set.
        val r = withTimeoutOrNull(600) { while (targets[ANSWER] == null) delay(16); targets[ANSWER] } ?: return fly(from, null)
        // It lands on the answer's nearest edge.
        val end = Offset(r.center.x, if (start.y > r.bottom) r.bottom else if (start.y < r.top) r.top else r.center.y)
        val f = Flight(start, end, ANSWER, CompletableDeferred())
        flight = f
        withTimeoutOrNull(1500) { f.arrived.await() }
    }
}

/** Marks a place a gold thread can go to or start from; [corner] is its shape's, for drawing around it. */
fun Modifier.goldTarget(key: String, corner: Dp = 0.dp): Modifier = composed {
    val px = with(LocalDensity.current) { corner.toPx() }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(key) { onDispose { Gold.targets.remove(key); Gold.corners.remove(key); Gold.screen.remove(key) } }
    onGloballyPositioned { c ->
        Gold.targets[key] = c.boundsInRoot(); Gold.corners[key] = px
        // Read when asked, so it's where it is then (mid-animation too), on the whole screen.
        Gold.screen[key] = {
            if (!c.isAttached) null else {
                val s = IntArray(2).also { view.getLocationOnScreen(it) }; val w = IntArray(2).also { view.getLocationInWindow(it) }
                c.boundsInWindow().translate((s[0] - w[0]).toFloat(), (s[1] - w[1]).toFloat())
            }
        }
    }
}

/** Gold light running round the edge, for something waiting on the user (a chat with an answer to read). */
fun Modifier.goldShine(on: Boolean, corner: Dp): Modifier = composed {
    if (!on) return@composed this
    val spin = rememberInfiniteTransition(label = "gold-shine")
    val p by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "round")
    val breathe by spin.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    drawWithContent {
        drawContent()
        val path = outline(Rect(Offset.Zero, size), corner.toPx(), 1.5.dp.toPx())
        val m = PathMeasure().apply { setPath(path, true) }
        drawPath(path, GoldMid.copy(alpha = 0.18f * breathe), style = Stroke(5.dp.toPx()))
        val head = m.length * p
        shine(m, head - m.length * 0.3f, head, 1f)
        shine(m, head + m.length * 0.2f, head + m.length * 0.5f, 0.45f)
    }
}

private val GoldLight = Color(0xFFFFF4C8)
private val GoldMid = Color(0xFFE8B84A)
private val GoldDeep = Color(0xFFB98516)

/** A rounded rectangle's outline, a little outside it, for a light to run along. */
private fun outline(r: Rect, corner: Float, pad: Float): Path {
    val o = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
    val c = (corner + pad).coerceAtMost(o.height / 2f)
    return Path().apply { addRoundRect(RoundRect(o, CornerRadius(c, c))) }
}

/** A shining piece of the outline between two distances along it (they may go past its end: it wraps round). */
private fun DrawScope.shine(m: PathMeasure, from: Float, to: Float, alpha: Float) {
    if (to <= from || alpha <= 0f) return
    val len = m.length
    fun piece(a: Float, b: Float) {
        val seg = Path().also { m.getSegment(a, b, it, true) }
        val p0 = m.getPosition(a); val p1 = m.getPosition(b)
        drawPath(seg, GoldMid.copy(alpha = 0.12f * alpha), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(seg, GoldMid.copy(alpha = 0.22f * alpha), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(seg, Brush.linearGradient(listOf(GoldDeep.copy(alpha = 0f), GoldMid.copy(alpha = 0.9f * alpha), GoldLight.copy(alpha = alpha)), p0, p1),
            style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
    val a = ((from % len) + len) % len
    val b = a + (to - from)
    if (b <= len) piece(a, b) else { piece(a, len); piece(0f, b - len) }
}

/** Where along the outline is nearest a point (sampled; close enough for a light). */
private fun nearest(m: PathMeasure, p: Offset): Float {
    var best = 0f; var bestD = Float.MAX_VALUE
    val n = 64
    for (i in 0 until n) {
        val d = m.length * i / n
        val q = m.getPosition(d)
        val dd = (q - p).getDistanceSquared()
        if (dd < bestD) { bestD = dd; best = d }
    }
    return best
}

/** Draws the threads in flight and the light circling a working box; put once over everything they can cross. */
@Composable
fun GoldOverlay() {
    DisposableEffect(Unit) { Gold.shown = true; onDispose { Gold.shown = false } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val mark = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }

    // Working: a comet goes round the box, about once a second and a half, with a faint glow all along it.
    Gold.working?.let { key ->
        val r = Gold.targets[key] ?: return@let
        val spin = rememberInfiniteTransition(label = "gold-work")
        val p by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "round")
        val breathe by spin.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
        Canvas(mark) {
            val path = outline(r.translate(-origin), Gold.corners[key] ?: 0f, 2.dp.toPx())
            val m = PathMeasure().apply { setPath(path, true) }
            drawPath(path, GoldMid.copy(alpha = 0.10f + 0.10f * breathe), style = Stroke(1.dp.toPx()))
            val head = m.length * p
            shine(m, head - m.length * 0.22f, head, 1f)
            drawCircle(GoldMid.copy(alpha = 0.3f), 5.dp.toPx(), m.getPosition(head % m.length))
            drawCircle(GoldLight, 1.8.dp.toPx(), m.getPosition(head % m.length))
        }
    }

    val f = Gold.flight ?: return
    val travel = remember(f) { Animatable(0f) }
    val land = remember(f) { Animatable(0f) }
    LaunchedEffect(f) {
        travel.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        f.arrived.complete(Unit)
        // Around an answer it stays a little longer: the light runs round its edges, then fades.
        launch { land.animateTo(1f, tween(if (f.around != null) 1500 else 520, easing = LinearEasing)) }.join()
        if (Gold.flight === f) Gold.flight = null
    }
    Canvas(mark) {
        val a = f.from - origin; val b = f.to - origin
        // A soft arc: it lifts away from the straight line, to the side it's heading.
        val mid = (a + b) / 2f
        val d = b - a
        val lift = (d.getDistance() * 0.22f).coerceAtMost(220f)
        val normal = if (d.getDistance() > 0f) Offset(-d.y, d.x) / d.getDistance() else Offset(0f, -1f)
        val side = if (d.x >= 0) 1f else -1f
        val c = mid + normal * lift * side
        val path = Path().apply { moveTo(a.x, a.y); quadraticBezierTo(c.x, c.y, b.x, b.y) }
        val measure = PathMeasure().apply { setPath(path, false) }
        val len = measure.length
        val p = travel.value
        val fade = 1f - (if (f.around != null) (land.value * 3f).coerceAtMost(1f) else land.value)
        if (p < 1f || fade > 0f) {
            // The string: its head at p, its tail a little behind, thinning out.
            val head = len * p
            val tail = (head - len * 0.38f).coerceAtLeast(0f) + len * 0.38f * (1f - fade)
            if (head > tail) {
                val seg = Path().also { measure.getSegment(tail, head, it, true) }
                val from = measure.getPosition(tail); val to = measure.getPosition(head)
                val brush = Brush.linearGradient(listOf(GoldDeep.copy(alpha = 0f), GoldMid.copy(alpha = 0.9f * fade), GoldLight.copy(alpha = fade)), from, to)
                drawPath(seg, GoldMid.copy(alpha = 0.10f * fade), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                drawPath(seg, GoldMid.copy(alpha = 0.18f * fade), style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(seg, brush, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            }
            if (p < 1f) {
                val tip = measure.getPosition(len * p)
                drawCircle(GoldMid.copy(alpha = 0.25f), 6.dp.toPx(), tip)
                drawCircle(GoldLight, 2.dp.toPx(), tip)
            }
        }
        if (land.value <= 0f) return@Canvas
        val r = f.around?.let { Gold.targets[it] }
        if (r == null) {
            // Where it lands: a ring that opens and fades.
            val rr = 6.dp.toPx() + 22.dp.toPx() * land.value
            drawCircle(GoldMid.copy(alpha = 0.55f * (1f - land.value)), rr, b, style = Stroke(1.5.dp.toPx()))
            drawCircle(GoldLight.copy(alpha = 0.35f * (1f - land.value)), 3.dp.toPx(), b)
        } else {
            // The answer: from where it landed, the light spreads both ways round its edges until they meet,
            // the whole edge glows a moment, and fades.
            val o = outline(r.translate(-origin), Gold.corners[f.around] ?: 0f, 3.dp.toPx())
            val m = PathMeasure().apply { setPath(o, true) }
            val at = nearest(m, b)
            val t = land.value
            val spread = (t / 0.45f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) }
            val glow = if (t < 0.45f) 0.3f + 0.7f * spread else sin(((t - 0.45f) / 0.55f) * PI.toFloat() / 2f + PI.toFloat() / 2f).coerceAtLeast(0f)
            val half = m.length / 2f * spread
            shine(m, at - half, at + half, glow)
            if (t > 0.4f) drawPath(o, GoldLight.copy(alpha = 0.28f * glow), style = Stroke(1.dp.toPx()))
        }
    }
}
