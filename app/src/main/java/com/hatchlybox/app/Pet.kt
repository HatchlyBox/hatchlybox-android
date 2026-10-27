package com.hatchlybox.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

// The `pet` node's cat: a tabby drawn from soft shapes that merge into one outlined silhouette. Its spine bends and
// stretches, its legs are a cat's (the back knee points forward, the hock back; it walks on its toes), and its tail
// follows with lag like a real one. It keeps the same small size in any card and does more the more room it has:
// in a tiny card it sits, loafs, grooms, yawns, kneads and watches a butterfly; with width it walks, rolls over,
// pounces, plays with yarn and has zoomies; with height it climbs the side and jumps at a toy on a string. A tap on
// it pets it; a tap or a drag anywhere else is a laser dot it stalks and pounces on. Plain Kotlin (no Android) so
// its frames can be drawn and checked off the phone.

private const val PIf = PI.toFloat()

/** A path in the cat's units: moves, lines, curves and ovals. */
class PetPath {
    val ops = ArrayList<Float>(48)
    fun moveTo(x: Float, y: Float) = apply { ops.add(0f); ops.add(x); ops.add(y) }
    fun lineTo(x: Float, y: Float) = apply { ops.add(1f); ops.add(x); ops.add(y) }
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = apply { ops.add(2f); ops.add(x1); ops.add(y1); ops.add(x); ops.add(y) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { ops.add(3f); ops.add(x1); ops.add(y1); ops.add(x2); ops.add(y2); ops.add(x); ops.add(y) }
    fun close() = apply { ops.add(4f) }
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = apply {
        val k = 0.5523f
        moveTo(cx + rx, cy)
        cubicTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        cubicTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        cubicTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        cubicTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        close()
    }

    /** An oval turned by [deg] degrees around its centre. */
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, deg: Float) = apply {
        val r = deg * PIf / 180f; val c = cos(r); val s = sin(r)
        fun px(x: Float, y: Float) = cx + x * c - y * s
        fun py(x: Float, y: Float) = cy + x * s + y * c
        val k = 0.5523f
        moveTo(px(rx, 0f), py(rx, 0f))
        cubicTo(px(rx, ry * k), py(rx, ry * k), px(rx * k, ry), py(rx * k, ry), px(0f, ry), py(0f, ry))
        cubicTo(px(-rx * k, ry), py(-rx * k, ry), px(-rx, ry * k), py(-rx, ry * k), px(-rx, 0f), py(-rx, 0f))
        cubicTo(px(-rx, -ry * k), py(-rx, -ry * k), px(-rx * k, -ry), py(-rx * k, -ry), px(0f, -ry), py(0f, -ry))
        cubicTo(px(rx * k, -ry), py(rx * k, -ry), px(rx, -ry * k), py(rx, -ry * k), px(rx, 0f), py(rx, 0f))
        close()
    }

    /** Walks the path: m(x,y), l(x,y), q(x1,y1,x,y), c(x1,y1,x2,y2,x,y), z(). */
    inline fun forEach(m: (Float, Float) -> Unit, l: (Float, Float) -> Unit, q: (Float, Float, Float, Float) -> Unit,
                       c: (Float, Float, Float, Float, Float, Float) -> Unit, z: () -> Unit) {
        var i = 0
        while (i < ops.size) {
            when (ops[i].toInt()) {
                0 -> { m(ops[i + 1], ops[i + 2]); i += 3 }
                1 -> { l(ops[i + 1], ops[i + 2]); i += 3 }
                2 -> { q(ops[i + 1], ops[i + 2], ops[i + 3], ops[i + 4]); i += 5 }
                3 -> { c(ops[i + 1], ops[i + 2], ops[i + 3], ops[i + 4], ops[i + 5], ops[i + 6]); i += 7 }
                else -> { z(); i += 1 }
            }
        }
    }
}

/** Where the cat draws: a canvas with a transform stack. Colours are ARGB. */
interface PetPen {
    fun save()
    fun restore()
    fun translate(x: Float, y: Float)
    fun rotate(degrees: Float)
    fun scale(sx: Float, sy: Float)
    fun fill(p: PetPath, argb: Int, alpha: Float = 1f)
    fun stroke(p: PetPath, argb: Int, width: Float, alpha: Float = 1f)
}

/** A cat's colours. */
class Coat(val fur: Int, val far: Int, val stripe: Int, val white: Int, val line: Int, val eye: Int, val pink: Int) {
    companion object {
        /** Wifoz: a grey tabby with black stripes and a little white under the mouth. */
        val Tabby = Coat(0xFF8E9197.toInt(), 0xFF6E7177.toInt(), 0xFF26272B.toInt(), 0xFFF3F1EC.toInt(), 0xFF1D1E22.toInt(), 0xFF9CCB5B.toInt(), 0xFFE59AA8.toInt())
        val Ginger = Coat(0xFFE08A3C.toInt(), 0xFFB8692A.toInt(), 0xFF9A4A16.toInt(), 0xFFF6EEE2.toInt(), 0xFF3A2312.toInt(), 0xFF7FB64A.toInt(), 0xFFE7909C.toInt())
        val Black = Coat(0xFF2E2F35.toInt(), 0xFF1E1F24.toInt(), 0xFF1A1B1F.toInt(), 0xFFF0EEEA.toInt(), 0xFF0E0F12.toInt(), 0xFFE2C043.toInt(), 0xFFD98894.toInt())
        val Cream = Coat(0xFFE9D7BC.toInt(), 0xFFCDB898.toInt(), 0xFFB79A72.toInt(), 0xFFFBF7F0.toInt(), 0xFF4A3A28.toInt(), 0xFF6AA8D8.toInt(), 0xFFE7909C.toInt())
        fun of(name: String) = when (name.lowercase()) { "ginger", "orange" -> Ginger; "black" -> Black; "cream", "white" -> Cream; else -> Tabby }
    }
}

private class V(var x: Float, var y: Float)

/**
 * Everything that places the cat at one moment, in its own units (body length about 1, facing right, ground y = 0).
 * The torso is a bendable spine: [len] stretches or gathers it, [arch] humps it.
 */
private class Pose {
    var cx = 0f; var cy = -0.46f; var pitch = 0f; var len = 1f; var arch = 0f
    val paw = FloatArray(8)                               // targets: near front, far front, near hind, far hind (x, y)
    val flex = FloatArray(4)                              // how far each paw curls up off the ground, 0..1
    var legTurn = 0f                                      // the legs' "down", turned (climbing, on its back)
    var hx = 0.22f; var hy = -0.26f; var tilt = 0f        // the head's centre from the chest (not turned with it)
    var lookX = 0.3f; var lookY = 0f                      // where the eyes look, -1..1
    var eyes = 1f; var happy = 0f; var mouth = 0f; var ears = 0f; var twitch = 0f; var pupil = 0.25f
    var tail = 215f; var curl = 6f; var wave = 6f; var waveSpeed = 2f
    var sit = 0f                                          // haunches down
    var lie = 0f                                          // lying: the legs folded under
    var belly = 0f                                        // rolled over (1: on its back)
    var squash = 1f                                       // breathing, landing
    var tailFront = 0f                                    // the tail wrapped round the front

    fun set(i: Int, x: Float, y: Float) { paw[i * 2] = x; paw[i * 2 + 1] = y }
    fun copy(): Pose = Pose().also { o -> o.from(this, this, 0f) }

    fun from(a: Pose, b: Pose, t: Float) {
        fun m(p: Float, q: Float) = p + (q - p) * t
        cx = m(a.cx, b.cx); cy = m(a.cy, b.cy); pitch = m(a.pitch, b.pitch); len = m(a.len, b.len); arch = m(a.arch, b.arch)
        for (i in 0 until 8) paw[i] = m(a.paw[i], b.paw[i])
        for (i in 0 until 4) flex[i] = m(a.flex[i], b.flex[i])
        legTurn = m(a.legTurn, b.legTurn)
        hx = m(a.hx, b.hx); hy = m(a.hy, b.hy); tilt = m(a.tilt, b.tilt); lookX = m(a.lookX, b.lookX); lookY = m(a.lookY, b.lookY)
        eyes = m(a.eyes, b.eyes); happy = m(a.happy, b.happy); mouth = m(a.mouth, b.mouth); ears = m(a.ears, b.ears)
        twitch = m(a.twitch, b.twitch); pupil = m(a.pupil, b.pupil)
        tail = m(a.tail, b.tail); curl = m(a.curl, b.curl); wave = m(a.wave, b.wave); waveSpeed = m(a.waveSpeed, b.waveSpeed)
        sit = m(a.sit, b.sit); lie = m(a.lie, b.lie); belly = m(a.belly, b.belly); squash = m(a.squash, b.squash)
        tailFront = if (t < 0.5f) a.tailFront else b.tailFront
    }
}

private enum class Doing { SIT, LOAF, GROOM, YAWN, LOOK, WALK, RUN, SCRATCH, CLIMB, PLAY, SLEEP, STRETCH, CHASE, PET, KNEAD, ROLL, POUNCE, BUG, HOP }

private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val kind: Int)

/** One leg's joints, top to paw. */
private class Leg(val j: V, val a: V, val b: V, val p: V)

/** Shapes that merge into one outlined silhouette: every outline first, then every fill. */
private class Ink {
    val fills = ArrayList<PetPath>(); val lines = ArrayList<PetPath>(); val widths = ArrayList<Float>()
    fun line(a: V, b: V, w: Float) { lines += PetPath().moveTo(a.x, a.y).lineTo(b.x, b.y); widths += w }
    fun fill(p: PetPath) { fills += p }
    fun draw(pen: PetPen, fur: Int, line: Int, ol: Float) {
        fills.forEach { pen.stroke(it, line, ol * 2f) }
        lines.forEachIndexed { i, it -> pen.stroke(it, line, widths[i] + ol * 2f) }
        fills.forEach { pen.fill(it, fur) }
        lines.forEachIndexed { i, it -> pen.stroke(it, fur, widths[i]) }
    }
}

/**
 * The cat's world and mind. Sizes are in dp: the card is [w]×[h], the cat [s] long. Time goes forward in steps of
 * 1/30 s ([advanceTo]), from a seed, so the same moment always looks the same.
 */
class PetCat(seed: Long = 7L, val coat: Coat = Coat.Tabby) {
    private var rnd = seed xor 0x5DEECE66DL
    private fun random(): Float { rnd = (rnd * 6364136223846793005L + 1442695040888963407L); return ((rnd ushr 40).toFloat() / (1L shl 24).toFloat()) }
    private fun between(a: Float, b: Float) = a + (b - a) * random()

    var w = 300f; private set
    var h = 160f; private set
    /** The cat's length in dp: the same in every card, smaller only when the card can't hold it. */
    var s = 44f; private set
    private val ground get() = h - 3f
    private val left get() = 2f
    private val right get() = w - 2f
    /** The card's shape, as widgets name them. */
    var shape = "wide"; private set

    var time = 0f; private set
    private var x = 150f; private var oy = 0f; private var facing = 1f; private var turned = 1f
    private var doing = Doing.SIT; private var at = 0f; private var length = 3f; private var stage = 0
    private var gait = 0f; private var targetX = 150f; private var climb = 0f; private var tired = 0f; private var runs = 0
    private var lookX = 0.3f; private var lookY = 0f; private var lookNext = 0f; private var eyeX = 0.3f; private var eyeY = 0f
    private var blinkAt = 2f; private var twitchAt = 3f
    private var jumpFrom = V(0f, 0f); private var jumpTo = V(0f, 0f); private var jumpH = 0f
    private var bats = 0
    private val ball = Bit(0f, 0f, 0f, 0f, 0f, 0); private var ballOn = false; private var spin = 0f
    private var dot: V? = null; private var dotLife = 0f; private var dragging = false
    // The butterfly.
    private var bugOn = false; private var bugX = 0f; private var bugY = 0f; private var bugT = 0f; private var bugPh = 0f
    private var bugLife = 0f; private var bugDur = 12f; private var bugAway = false; private var bugDX = 0f; private var bugFade = 1f
    // The toy on a string, hanging from the top edge.
    private var toyOn = false; private var toyShow = 0f; private var toyX = 0f; private var toyA = 0f; private var toyAV = 0f
    private val bits = ArrayList<Bit>()                  // hearts (1), z's (2), sparkles (3)
    private val marks = ArrayList<Bit>()                 // scratch marks on an edge: x, y, side, age
    private var pose = Pose(); private var blendFrom: Pose? = null; private var blendAt = 0f
    private val tailN = 13; private val tailA = FloatArray(tailN); private var tailReady = false
    private var tailLo = -9f; private var tailHi = 9f        // the card's sides in the cat's units, for the tail
    private var started = false

    /** The card is this size (dp): the cat stays its size and fits its moves to the room. */
    fun resize(width: Float, height: Float) {
        if (abs(width - w) < 0.5f && abs(height - h) < 0.5f && started) return
        w = width.coerceAtLeast(40f); h = height.coerceAtLeast(40f)
        shape = when {
            w < 125f && h < 125f -> "tiny"
            w >= h * 1.8f -> "wide"
            h >= w * 1.5f -> "tall"
            w >= 240f && h >= 240f -> "large"
            else -> "small"
        }
        s = minOf(44f, w * 0.5f, h * 0.56f).coerceAtLeast(20f)
        x = x.coerceIn(minX(), maxX())
        if (!started) { x = w * 0.5f; started = true; begin(Doing.SIT, 2.5f) }
        ball.x = ball.x.coerceIn(left + ballR(), right - ballR())
        climb = climb.coerceAtMost(maxClimb())
        toyX = toyX.coerceIn(minX(), maxX())
    }

    private fun minX() = min(left + s * 0.88f, w / 2)
    private fun maxX() = max(right - s * 0.88f, w / 2)
    private fun roomy() = maxX() - minX() > s * 0.8f
    private fun maxClimb() = ((ground - s * 1.7f) / s).coerceAtLeast(0f)
    private fun ballR() = s * 0.11f
    private fun toyLen() = (ground - s * 1.55f).coerceAtLeast(s * 0.3f)
    /** The edge in front of the cat, in its units. */
    private fun wallAhead() = (if (facing > 0) right - x else x - left) / s

    // ---------- what it does next ----------

    private fun begin(d: Doing, len: Float) {
        blendFrom = pose.copy(); blendAt = time
        doing = d; at = 0f; length = len; stage = 0
    }

    private fun next() {
        tired += 1f
        val tiny = shape == "tiny"
        val span = maxX() - minX()
        val canClimb = maxClimb() >= 0.7f
        val canHop = h - 6f >= s * 2.1f
        val options = listOf(
            Doing.SIT to 2.2f, Doing.LOAF to (if (tiny) 2.6f else 1.6f), Doing.GROOM to 2f, Doing.YAWN to 0.9f,
            Doing.LOOK to 1.1f, Doing.STRETCH to 0.9f, Doing.KNEAD to 0.8f, Doing.SLEEP to (0.3f + tired * 0.1f),
            Doing.BUG to when (shape) { "wide", "large" -> 2.2f; else -> 1.5f },
            Doing.WALK to if (roomy()) 2.6f else 0f,
            Doing.RUN to when { span < s * 1.6f -> 0f; shape == "wide" -> 3f; shape == "large" -> 2f; else -> 0.5f },
            Doing.PLAY to if (tiny) 1f else 2.2f,
            Doing.SCRATCH to if (tiny) 0f else 1.3f,
            Doing.ROLL to if (w >= s * 1.6f) 1.1f else 0f,
            Doing.POUNCE to if (roomy()) 1.1f else 0f,
            Doing.CLIMB to if (!canClimb) 0f else when (shape) { "tall" -> 3.2f; "large" -> 1.5f; else -> 0.6f },
            Doing.HOP to if (!canHop) 0f else when (shape) { "tall" -> 2.6f; "large" -> 1.6f; else -> 0.6f },
        ).filter { it.second > 0f && it.first != doing }
        var r = random() * options.sumOf { it.second.toDouble() }.toFloat()
        val pick = options.firstOrNull { r -= it.second; r <= 0f }?.first ?: Doing.SIT
        startDoing(pick)
    }

    private fun startDoing(d: Doing) {
        when (d) {
            Doing.WALK -> { targetX = between(minX(), maxX()); begin(d, 99f) }
            Doing.RUN -> {
                targetX = if (x - minX() > maxX() - x) minX() + random() * s * 0.3f else maxX() - random() * s * 0.3f
                runs = if (shape == "wide") (random() * 2.6f).toInt() else 0
                begin(d, 99f)
            }
            Doing.SLEEP -> { tired = 0f; begin(d, between(8f, 13f)) }
            Doing.SCRATCH, Doing.CLIMB -> { facing = if (x - left < right - x) -1f else 1f; if (d == Doing.SCRATCH && random() < 0.3f) facing = -facing; begin(d, 99f) }
            Doing.PLAY -> { if (!ballOn) dropBall(); bats = 2 + (random() * 3).toInt(); begin(d, 99f) }
            Doing.GROOM -> begin(d, between(4.5f, 6.5f))
            Doing.YAWN -> begin(d, 2.4f)
            Doing.LOAF -> begin(d, between(5f, 9f))
            Doing.STRETCH -> begin(d, 3.4f)
            Doing.KNEAD -> begin(d, between(3.5f, 5.5f))
            Doing.ROLL -> begin(d, 5.2f)
            Doing.POUNCE -> begin(d, 99f)
            Doing.BUG -> { startBug(); begin(d, 99f) }
            Doing.HOP -> { toyOn = true; toyX = between(minX(), maxX()); toyA = between(-0.12f, 0.12f); toyAV = 0f; bats = 2 + (random() * 2).toInt(); begin(d, 99f) }
            else -> begin(d, between(2.5f, 5f))
        }
    }

    private fun dropBall() {
        ballOn = true
        ball.x = if (roomy()) between(minX(), maxX()) else w / 2 + s * 0.5f * (if (random() < 0.5f) -1 else 1)
        ball.x = ball.x.coerceIn(left + ballR(), right - ballR())
        ball.y = ground - ballR() - s * 0.9f; ball.vx = between(-0.5f, 0.5f) * s; ball.vy = 0f
    }

    private fun startBug() {
        bugOn = true; bugAway = false; bugFade = 1f; bugLife = 0f; bugDur = between(10f, 16f)
        bugT = random() * 20f; bugPh = random() * 6f
        bugX = if (random() < 0.5f) -s * 0.2f else w + s * 0.2f; bugY = max(s * 0.4f, ground - s * 2f)
    }

    // ---------- what people do ----------

    /** Whether (px, py) in dp is on the cat. */
    fun hits(px: Float, py: Float): Boolean {
        val p = pose
        val bx = x + facing * p.cx * s; val by = oy + p.cy * s
        val hc = headAt(p); val hx = x + facing * hc.x * s; val hy = oy + hc.y * s
        return hypot(px - bx, py - by) < s * 0.55f || hypot(px - hx, py - hy) < s * 0.36f
    }

    /** A tap: on the cat it's a pet (and wakes it); elsewhere a laser dot to chase. */
    fun tap(px: Float, py: Float) {
        if (hits(px, py)) {
            if (doing == Doing.SLEEP) { begin(Doing.STRETCH, 3.4f); return }
            if (doing == Doing.CLIMB || doing == Doing.HOP && stage == 2) return
            begin(Doing.PET, 2.8f); return
        }
        laser(px, py); dotLife = 3.2f
    }

    /** A finger held and moving: the dot follows it. */
    fun drag(px: Float, py: Float) { dragging = true; laser(px, py); dotLife = 99f }
    fun release() { if (dragging) { dragging = false; dotLife = 1.6f } }

    /** Double-tap: flings the ball or drops one, then plays. */
    fun doubleTap(px: Float, py: Float) {
        if (!ballOn) { dropBall(); bats = 3 }
        else { ball.vx = (px - ball.x).coerceIn(-s * 3f, s * 3f); ball.vy = -s * 3f; spin = 0f }
        if (doing != Doing.SLEEP) begin(Doing.PLAY, 99f)
    }

    /** Long-press: kneading (happy paw-press) if awake, a stretch if asleep. */
    fun longPress(px: Float, py: Float) {
        if (doing == Doing.SLEEP) begin(Doing.STRETCH, 3.4f)
        else if (doing != Doing.CLIMB) begin(Doing.KNEAD, between(3.5f, 5.5f))
    }

    private fun laser(px: Float, py: Float) {
        dot = V(px.coerceIn(left, right), py.coerceIn(2f, ground))
        if (bugOn) bugAway = true
        if (doing != Doing.CHASE && doing != Doing.CLIMB) { if (doing == Doing.SLEEP) begin(Doing.STRETCH, 1.6f) else begin(Doing.CHASE, 99f) }
    }

    // ---------- time ----------

    /** Moves on to [t] seconds, in steps of 1/30 s (a big jump starts over from just before it). */
    fun advanceTo(t: Float) {
        val dt = 1f / 30f
        if (t < time) return
        if (t - time > 20f) { var skip = time; while (skip + dt < t - 20f) { step(dt); skip += dt } }
        while (time + dt <= t) step(dt)
    }

    private fun step(dt: Float) {
        time += dt; at += dt
        // Look around now and then (the eyes follow smoothly); blink; flick an ear.
        if (time > lookNext) { lookX = between(-0.6f, 1f); lookY = between(-0.6f, 0.4f); lookNext = time + between(0.8f, 2.6f) }
        if (time > blinkAt + 0.18f) blinkAt = time + between(2f, 5.5f)
        if (time > twitchAt + 0.3f) twitchAt = time + between(2.5f, 7f)
        if (dot != null) { dotLife -= dt; if (dotLife <= 0f) dot = null }
        stepBall(dt); stepBug(dt); stepToy(dt)
        bits.forEach { it.x += it.vx * dt; it.y += it.vy * dt; it.life -= dt }
        bits.removeAll { it.life <= 0f }
        marks.forEach { it.life += dt }
        marks.removeAll { it.life > 9f }
        oy = ground
        // No room to move: it stays in the middle, its body (not its tail) centred.
        val moving = doing == Doing.SCRATCH || doing == Doing.CLIMB || doing == Doing.CHASE || doing == Doing.BUG || doing == Doing.HOP || doing == Doing.PLAY
        if (!roomy() && !moving) x += (w / 2 + facing * s * 0.05f - x) * min(1f, dt * 3f)
        when (doing) {
            Doing.WALK -> if (move(targetX, 0.8f, dt)) next()
            Doing.RUN -> if (move(targetX, 3.1f, dt)) begin(Doing.LOOK, if (runs > 0) between(0.3f, 0.6f) else between(1.2f, 2.2f))
            Doing.LOOK -> if (at > length) { if (runs > 0) { runs--; targetX = if (x > w / 2) minX() + random() * s * 0.3f else maxX() - random() * s * 0.3f; begin(Doing.RUN, 99f) } else next() }
            Doing.SCRATCH -> scratch(dt)
            Doing.CLIMB -> climbing(dt)
            Doing.PLAY -> play(dt)
            Doing.CHASE, Doing.BUG -> chase(dt)
            Doing.POUNCE -> pounce(dt)
            Doing.HOP -> hop(dt)
            Doing.PET -> { if (((at * 10).toInt() % 4 == 0) && bits.count { it.kind == 1 } < 4 && random() < 0.35f) heart(); if (at > length) next() }
            Doing.SLEEP -> { if (random() < dt * 0.6f) { val hc = headAt(pose); bits += Bit(x + facing * (hc.x + 0.1f) * s, oy + (hc.y - 0.2f) * s, s * 0.12f * facing, -s * 0.28f, 2.4f, 2) }; if (at > length) begin(Doing.STRETCH, 3.4f) }
            Doing.KNEAD -> { if (((at * 7).toInt() % 9 == 0) && bits.count { it.kind == 1 } < 3) heart(); if (at > length) next() }
            else -> if (at > length) next()
        }
        if (doing != Doing.HOP) toyOn = false
        turned += (facing - turned) * min(1f, dt * 11f)
        eyeX += (lookX - eyeX) * min(1f, dt * 7f); eyeY += (lookY - eyeY) * min(1f, dt * 7f)
        val target = poseNow()
        val from = blendFrom
        if (from != null) {
            val k = ((time - blendAt) / 0.42f).coerceIn(0f, 1f)
            if (k >= 1f) { blendFrom = null; pose = target } else pose = Pose().also { it.from(from, target, k * k * (3 - 2 * k)) }
        } else pose = target
        stepTail(dt)
    }

    private fun heart() { val hc = headAt(pose); bits += Bit(x + facing * (hc.x + between(-0.1f, 0.2f)) * s, oy + (hc.y - 0.3f) * s, between(-0.1f, 0.1f) * s, -s * 0.45f, 1.6f, 1) }

    /** Walks or runs towards [tx]; true once there. The legs keep time with the ground they cover. */
    private fun move(tx: Float, speed: Float, dt: Float): Boolean {
        val tgt = tx.coerceIn(minX(), maxX())
        val d = tgt - x
        if (abs(d) < s * 0.04f) return true
        facing = sign(d)
        val v = min(abs(d), speed * s * dt * (if (speed > 2f) min(1f, 0.35f + at * 1.6f) else 1f))
        x += facing * v
        gait += v / s / (if (speed > 2f) 1.6f else 0.5f)
        return false
    }

    private fun stepBall(dt: Float) {
        if (!ballOn) return
        val r = ballR()
        ball.vy += s * 9f * dt
        ball.x += ball.vx * dt; ball.y += ball.vy * dt
        if (ball.y > ground - r) { ball.y = ground - r; ball.vy = if (abs(ball.vy) > s * 0.8f) -ball.vy * 0.45f else 0f; ball.vx *= (1f - 1.6f * dt) }
        if (ball.x < left + r) { ball.x = left + r; ball.vx = abs(ball.vx) * 0.7f }
        if (ball.x > right - r) { ball.x = right - r; ball.vx = -abs(ball.vx) * 0.7f }
        spin += ball.vx / r * dt * 57.3f
    }

    private fun stepBug(dt: Float) {
        if (!bugOn) return
        bugT += dt; bugLife += dt
        if (bugLife > bugDur) bugAway = true
        if (bugAway) {
            bugY -= s * 1.5f * dt; bugX += bugDX * s * dt; bugFade -= dt * 0.5f
            if (bugY < -s * 0.3f || bugFade <= 0f) bugOn = false
            return
        }
        // It wanders over the card on a lazy figure-of-eight, bobbing as it flaps.
        val span = (w / 2f - s * 0.3f).coerceAtLeast(4f)
        val tx = w / 2f + span * sin(bugT * 0.43f + bugPh) * (0.65f + 0.35f * cos(bugT * 0.21f))
        val low = ground - s * 0.6f; val high = min(low, max(s * 0.35f, ground - s * 2.6f))
        val ty = low + (high - low) * (0.5f + 0.5f * sin(bugT * 0.57f + bugPh * 2f)) + s * 0.08f * sin(bugT * 7f)
        bugX += (tx - bugX) * min(1f, dt * 1.6f); bugY += (ty - bugY) * min(1f, dt * 1.6f)
    }

    private fun stepToy(dt: Float) {
        toyShow = (toyShow + (if (toyOn) dt else -dt) / 0.7f).coerceIn(0f, 1f)
        val l = toyLen() / s
        toyAV += (-9f / l * sin(toyA) - 0.8f * toyAV) * dt
        toyA += toyAV * dt
        // The toy stays inside the card: it bounces softly off the sides.
        val tx = toyX + toyLen() * sin(toyA)
        if (tx < left + s * 0.15f && toyAV < 0f || tx > right - s * 0.15f && toyAV > 0f) toyAV = -toyAV * 0.5f
    }

    private fun toyPos(): V { val l = toyLen() * toyShow; return V(toyX + l * sin(toyA), l * cos(toyA)) }

    private fun scratch(dt: Float) {
        when (stage) {
            0 -> { // to the edge
                val stand = if (facing > 0) right - s * 0.88f else left + s * 0.88f
                if (abs(stand - x) < s * 0.05f || !roomy() && at > 0.6f) { stage = 1; at = 0f; blendFrom = pose.copy(); blendAt = time }
                else { val f = facing; move(stand, 0.8f, dt); facing = f; if (sign(stand - x) != f && abs(stand - x) > s * 0.05f) facing = sign(stand - x) }
            }
            1 -> { // claws on the edge, a stroke every 0.32 s
                if ((at / 0.32f).toInt() != ((at - dt) / 0.32f).toInt()) {
                    if (wallAhead() < 0.95f) {
                        val wx = if (facing > 0) right - 1f else left + 1f
                        marks += Bit(wx, oy - s * between(0.7f, 1.0f), facing, 0f, 0f, 0)
                    }
                }
                if (at > 3.4f) { stage = 2; at = 0f; blendFrom = pose.copy(); blendAt = time }
            }
            else -> if (at > 0.5f) { facing = -facing; begin(Doing.SIT, between(1.5f, 2.5f)) }
        }
    }

    private fun climbing(dt: Float) {
        val top = maxClimb()
        when (stage) {
            0 -> { // to the side it climbs
                val f = facing
                if (move(if (f > 0) maxX() else minX(), 0.8f, dt)) { stage = 1; at = 0f; blendFrom = pose.copy(); blendAt = time; facing = f }
                else facing = f
            }
            1 -> {
                val stand = if (facing > 0) right - s * 0.34f else left + s * 0.34f
                x += (stand - x) * min(1f, dt * 8f)
                climb = min(top, climb + 0.62f * dt); gait += 0.62f * dt / 0.5f; if (climb >= top) { stage = 2; at = 0f }
            }
            2 -> if (at > 2.4f) { stage = 3; at = 0f }
            3 -> { climb = max(0f, climb - 0.55f * dt); gait -= 0.55f * dt / 0.5f; if (climb <= 0.25f) { stage = 4; at = 0f; blendFrom = pose.copy(); blendAt = time } }
            else -> { // off the side and back onto the floor
                climb = max(0f, climb - 1.2f * dt)
                x += ((if (facing > 0) maxX() else minX()) - x) * min(1f, dt * 9f)
                if (at > 0.45f) { climb = 0f; facing = -facing; begin(Doing.SIT, 1.8f) }
            }
        }
    }

    private fun play(dt: Float) {
        val r = ballR()
        val reach = s * 0.62f
        when (stage) {
            0 -> { // after the ball
                if (abs(ball.vx) < s * 0.3f && ball.y >= ground - r - 1f) {
                    val side = if (ball.x > x) -1f else 1f
                    var spot = ball.x + side * reach
                    if (spot < minX() || spot > maxX()) spot = ball.x - side * reach
                    targetX = spot
                    val fast = abs(spot - x) > s * 1.6f
                    if (move(spot, if (fast) 2.6f else 0.8f, dt)) { facing = sign(ball.x - x).takeIf { it != 0f } ?: facing; stage = 1; at = 0f; blendFrom = pose.copy(); blendAt = time }
                } else { facing = sign(ball.x - x).takeIf { it != 0f } ?: facing; if (at > 4f) { stage = 1; at = 0f } }
            }
            1 -> if (at > 0.6f) { stage = 2; at = 0f } // crouch and wiggle a moment
            2 -> { // bat it
                if (at > 0.14f && at - dt <= 0.14f && abs(ball.x - x) < s * 0.95f) {
                    val room = if (facing > 0) right - ball.x else ball.x - left
                    ball.vx = facing * s * min(between(1.4f, 2.8f), 0.6f + room / s * 1.1f)
                    ball.vy = -s * between(0.6f, 1.6f)
                }
                if (at > 0.4f) { stage = 3; at = 0f; bats-- }
            }
            else -> if (at > 0.7f) { if (bats <= 0) begin(Doing.SIT, between(1.5f, 2.5f)) else { stage = 0; at = 0f } }
        }
        if (doing == Doing.PLAY && stage == 0 && at > 7f) begin(Doing.SIT, 2f)
    }

    /** What it's after: the laser dot, or the butterfly. */
    private fun quarry(): V? = if (doing == Doing.BUG) (if (bugOn && !bugAway) V(bugX, bugY) else null) else dot

    private fun leapMin() = min(left + s * 1.05f, w / 2)
    private fun leapMax() = max(right - s * 1.05f, w / 2)

    /** The highest it may leap, in its units, so it stays in the card. */
    private fun leapRoom() = (ground / s - 1.35f).coerceAtLeast(0f)

    private fun chase(dt: Float) {
        val d = quarry()
        if (d == null) { if (stage < 2) begin(Doing.LOOK, 1.6f) else if (at > 0.5f) next(); return }
        val high = (oy - d.y) / s
        when (stage) {
            0 -> { // stalk, then run when far
                val gap = d.x - x
                val spot = d.x - sign(gap) * s * 0.75f
                val far = abs(gap) > s * 2f
                if (abs(gap) < s * 1.15f && high < 1.7f) { facing = sign(gap).takeIf { it != 0f } ?: facing; stage = 1; at = 0f; blendFrom = pose.copy(); blendAt = time }
                else if (!move(spot, if (far) 2.6f else 0.6f, dt)) Unit
                else { facing = sign(gap).takeIf { it != 0f } ?: facing; if (high >= 1.7f) { stage = 4; at = 0f } }
            }
            1 -> { // crouch, wiggle
                if (at > 0.6f) {
                    stage = 2; at = 0f
                    jumpFrom = V(x, oy); jumpTo = V((d.x - facing * s * 0.32f).coerceIn(leapMin(), leapMax()), oy)
                    jumpH = ((oy - d.y) / s - 0.5f).coerceIn(0.25f, leapRoom())
                    blendFrom = pose.copy(); blendAt = time
                }
            }
            2 -> { // the leap
                val k = (at / 0.46f).coerceIn(0f, 1f)
                x = jumpFrom.x + (jumpTo.x - jumpFrom.x) * k
                if (k >= 1f) {
                    x = jumpTo.x; stage = 3; at = 0f
                    if (hypot(d.x - (x + facing * s * 0.45f), d.y - (oy - s * 0.4f)) < s * 1.1f) {
                        repeat(5) { bits += Bit(d.x, d.y, between(-0.6f, 0.6f) * s, between(-0.8f, -0.1f) * s, 0.7f, 3) }
                        if (doing == Doing.BUG) { if (random() < 0.4f) { bugAway = true; bugDX = between(-1f, 1f) } else bugY -= s * 0.6f } else if (!dragging) dot = null
                    }
                }
            }
            3 -> if (at > 0.45f) { if (quarry() == null) begin(if (doing == Doing.BUG) Doing.LOOK else Doing.PET, 1.6f) else { stage = 0; at = 0f } }
            else -> { // too high: sit and watch, a paw up now and then
                if (abs(d.x - x) > s * 1.3f || high < 1.6f) { stage = 0; at = 0f }
                if (doing == Doing.CHASE && at > 4f && !dragging) { dot = null; begin(Doing.SIT, 1.5f) }
            }
        }
    }

    /** Pretend prey: wiggle, pounce on a spot ahead, then look about as if it meant to. */
    private fun pounce(dt: Float) {
        when (stage) {
            0 -> { if (wallAhead() < 1.6f) facing = -facing; stage = 1; at = 0f }
            1 -> if (at > 1.1f) {
                stage = 2; at = 0f
                jumpFrom = V(x, oy); jumpTo = V((x + facing * s * between(0.7f, 1.1f)).coerceIn(leapMin(), leapMax()), oy); jumpH = 0.3f
                blendFrom = pose.copy(); blendAt = time
            }
            2 -> { val k = (at / 0.42f).coerceIn(0f, 1f); x = jumpFrom.x + (jumpTo.x - jumpFrom.x) * k; if (k >= 1f) { stage = 3; at = 0f } }
            else -> if (at > 0.6f) begin(Doing.LOOK, 1.5f)
        }
    }

    /** Jumps at the toy on a string a few times. */
    private fun hop(dt: Float) {
        val t = toyPos()
        when (stage) {
            0 -> { // under it
                if (toyShow < 1f && at < 1f) return
                val side = if (t.x >= x) -1f else 1f
                var spot = t.x + side * s * 0.3f
                if (spot < minX() || spot > maxX()) spot = t.x - side * s * 0.3f
                if (move(spot, 0.8f, dt) || at > 6f) { facing = sign(t.x - x).takeIf { it != 0f } ?: facing; stage = 1; at = 0f; blendFrom = pose.copy(); blendAt = time }
            }
            1 -> if (at > 0.55f) { stage = 2; at = 0f; jumpH = ((ground - t.y) / s - 1.25f).coerceIn(0.1f, (ground / s - 1.55f).coerceAtLeast(0.1f)); blendFrom = pose.copy(); blendAt = time }
            2 -> {
                if (at > 0.27f && at - dt <= 0.27f) {
                    val hc = headAt(pose)
                    if (abs(t.x - (x + facing * hc.x * s)) < s * 0.6f) { toyAV += facing * between(0.25f, 0.45f); bits += Bit(t.x, t.y, 0f, -s * 0.3f, 0.5f, 3) }
                }
                if (at > 0.55f) { stage = 3; at = 0f; bats-- }
            }
            else -> if (at > 0.5f) { if (bats <= 0) begin(Doing.SIT, between(1.5f, 2.5f)) else { stage = 0; at = 0f } }
        }
    }

    // ---------- poses ----------

    private fun frac(v: Float) = ((v % 1f) + 1f) % 1f
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun stand(p: Pose) {
        p.cx = 0f; p.cy = -0.46f; p.pitch = 0f; p.len = 1f; p.arch = 0f
        p.set(0, 0.30f, 0f); p.set(1, 0.24f, 0f); p.set(2, -0.25f, 0f); p.set(3, -0.31f, 0f)
        p.hx = 0.22f; p.hy = -0.26f; p.tilt = 0f; p.tail = 218f; p.curl = 5f; p.wave = 6f; p.waveSpeed = 1.8f
    }

    private fun sitting(p: Pose) {
        p.cx = -0.04f; p.cy = -0.32f; p.pitch = -50f; p.len = 0.9f; p.sit = 1f
        p.set(0, 0.19f, 0f); p.set(1, 0.13f, 0f); p.set(2, 0.03f, 0f); p.set(3, -0.03f, 0f)
        p.hx = 0.13f; p.hy = -0.27f; p.tail = 128f; p.curl = -9f; p.wave = 5f; p.waveSpeed = 1.1f; p.tailFront = 1f
    }

    private fun loaf(p: Pose) {
        p.cx = 0f; p.cy = -0.21f; p.pitch = 0f; p.len = 0.86f; p.lie = 1f; p.squash = 1.04f
        p.set(0, 0.36f, 0f); p.set(1, 0.30f, 0f); p.set(2, -0.2f, 0f); p.set(3, -0.26f, 0f)
        p.hx = 0.17f; p.hy = -0.20f
        p.tail = 168f; p.curl = -12f; p.wave = 4f; p.waveSpeed = 1f; p.tailFront = 1f
    }

    /** Walking: a cat's lateral walk (hind, then the front on the same side), the head steady while the body bobs. */
    private fun walk(p: Pose, ph: Float, stride: Float = 0.30f, lift: Float = 0.09f) {
        stand(p)
        val offs = floatArrayOf(0.25f, 0.75f, 0f, 0.5f)
        val base = floatArrayOf(0.30f, 0.24f, -0.25f, -0.31f)
        for (i in 0 until 4) {
            val u = frac(ph + offs[i]); val st = 0.6f
            if (u < st) { p.set(i, base[i] + stride * (0.5f - u / st), 0f); p.flex[i] = 0f }
            else {
                val v = (u - st) / (1f - st); val e = v * v * (3 - 2 * v)
                p.set(i, base[i] + stride * (-0.5f + e), -lift * sin(PIf * v)); p.flex[i] = 0.8f * sin(PIf * min(1f, v * 1.25f))
            }
        }
        val a = ph * 2f * PIf
        p.cy = -0.46f + 0.012f * cos(a * 2f)
        p.pitch = 1.5f * sin(a * 2f + 0.8f); p.arch = 0.012f * sin(a * 2f)
        p.hx = 0.24f; p.hy = -0.24f - 0.012f * cos(a * 2f)
        p.tilt = 3f * sin(a); p.tail = 222f; p.curl = 5f; p.wave = 8f; p.waveSpeed = 2.2f
        p.lookX = 1f; p.lookY = 0.1f
    }

    /** Running: a rotary gallop. The spine gathers (hind paws under the chest) and stretches (front paws reaching). */
    private fun gallop(p: Pose, ph: Float) {
        stand(p)
        val a = ph * 2f * PIf
        val e = -cos(a)                                   // -1 gathered, +1 stretched
        p.len = 1f + 0.12f * e
        p.arch = 0.07f * (1f - e) / 2f
        val lands = floatArrayOf(0.58f, 0.66f, 0.04f, 0.11f)
        val base = floatArrayOf(0.30f, 0.24f, -0.26f, -0.32f)
        for (i in 0 until 4) {
            val front = i < 2
            val stride = if (front) 0.52f else 0.58f
            val lift = if (front) 0.20f else 0.15f
            val u = frac(ph - lands[i]); val st = 0.30f
            val bx = base[i] * p.len
            if (u < st) { p.set(i, bx + stride * (0.5f - u / st), 0f); p.flex[i] = 0f }
            else {
                val v = (u - st) / (1f - st); val ev = v * v * (3 - 2 * v)
                p.set(i, bx + stride * (-0.5f + ev), -lift * sin(PIf * v)); p.flex[i] = sin(PIf * v)
            }
        }
        val air = max(0f, sin(a * 2f - 0.6f))
        p.cy = -0.44f - 0.05f * air
        p.pitch = -5f * sin(a)
        p.hx = 0.27f + 0.03f * e; p.hy = -0.18f; p.tilt = -4f
        p.ears = 0.45f; p.lookX = 1f; p.lookY = 0f; p.pupil = 0.7f
        p.tail = 192f; p.curl = 1.5f; p.wave = 5f; p.waveSpeed = 7f
    }

    /** Low and ready, the rump wiggling. */
    private fun crouch(p: Pose, wiggle: Float) {
        stand(p)
        p.cy = -0.31f; p.pitch = 5f; p.len = 0.95f
        p.cx = 0.02f * sin(at * 30f) * wiggle; p.arch = 0.02f
        p.set(0, 0.38f, 0f); p.set(1, 0.32f, 0f); p.set(2, -0.20f, 0f); p.set(3, -0.26f, 0f)
        p.hx = 0.25f; p.hy = -0.13f; p.ears = 0.55f; p.pupil = 1f
        p.tail = 186f; p.curl = 1f; p.wave = 14f * wiggle; p.waveSpeed = 12f
    }

    /** In the air: stretched long, front paws reaching, hind legs trailing. */
    private fun leap(p: Pose, k: Float, height: Float) {
        stand(p)
        val y = -0.48f - sin(PIf * k) * (0.18f + height)
        p.cy = y; p.pitch = -22f + 38f * k; p.len = 1.14f; p.arch = -0.02f
        p.set(0, 0.66f, y + 0.20f); p.set(1, 0.60f, y + 0.25f); p.set(2, -0.66f, y + 0.30f); p.set(3, -0.60f, y + 0.34f)
        p.flex[2] = 0.2f; p.flex[3] = 0.2f
        p.hx = 0.26f; p.hy = -0.20f; p.ears = 0.5f; p.pupil = 1f; p.tail = 178f; p.curl = 1f; p.wave = 4f
    }

    private fun land(p: Pose) {
        stand(p); p.cy = -0.36f; p.squash = 0.94f; p.set(0, 0.42f, 0f); p.set(1, 0.36f, 0f); p.hy = -0.18f; p.ears = 0.2f
    }

    private fun poseNow(): Pose {
        val p = Pose()
        val t = time
        p.lookX = eyeX; p.lookY = eyeY
        p.squash = 1f + 0.016f * sin(t * 2.6f)
        val k = (at / length).coerceIn(0f, 1f)
        when (doing) {
            Doing.SIT -> { sitting(p); p.hx += 0.01f * sin(t * 0.7f); p.tilt = 4f * sin(t * 0.5f) }
            Doing.LOOK -> {
                sitting(p)
                p.hy -= 0.03f; p.tilt = 12f * sin(t * 1.1f); p.ears = 0f; p.tail = 172f; p.wave = 12f; p.waveSpeed = 3f
                p.lookX = sin(t * 1.1f)
            }
            Doing.LOAF -> {
                loaf(p)
                val slow = (sin(t * 0.9f) * 0.5f + 0.5f)
                p.eyes = if (k > 0.5f) 0.25f + 0.5f * slow else 0.75f + 0.25f * slow
                p.tilt = 3f * sin(t * 0.4f)
            }
            Doing.GROOM -> {
                sitting(p)
                if (k < 0.5f) { // licks a raised paw
                    val lick = sin(at * 10f)
                    p.set(0, 0.36f, -0.42f + 0.03f * lick); p.flex[0] = 0.8f
                    p.tilt = 18f + 5f * lick; p.hx = 0.14f; p.hy = -0.22f; p.eyes = 0.2f; p.mouth = 0.25f + 0.2f * lick
                } else { // and wipes it over its face
                    val a = (at - length * 0.5f) * 5f
                    p.set(0, 0.38f + 0.06f * cos(a), -0.56f + 0.08f * sin(a)); p.flex[0] = 0.9f
                    p.tilt = 12f + 10f * sin(a + 1f); p.hx = 0.12f; p.hy = -0.24f; p.eyes = 0.08f
                }
                p.ears = 0.15f
            }
            Doing.YAWN -> {
                sitting(p)
                val q = sin(PIf * k)
                p.hy = -0.27f - 0.04f * q; p.tilt = -26f * q; p.mouth = 1.3f * q; p.eyes = 1f - q; p.ears = 0.5f * q
                p.squash = 1f + 0.04f * q
            }
            Doing.PET -> {
                sitting(p)
                p.happy = 1f; p.eyes = 0f; p.tilt = -14f + 6f * sin(t * 4f); p.hy = -0.30f
                p.tail = 258f; p.curl = 8f; p.wave = 5f; p.waveSpeed = 3f; p.tailFront = 0f
                p.cx += 0.005f * sin(t * 60f) // purring
            }
            Doing.KNEAD -> {
                loaf(p); p.cy = -0.24f
                val kph = at * PIf * 2.4f
                p.set(0, 0.36f, -0.07f * max(0f, sin(kph))); p.set(1, 0.30f, -0.07f * max(0f, sin(kph + PIf)))
                p.eyes = 0.1f; p.happy = 1f; p.tilt = 5f * sin(at * 3f)
                p.tail = 172f; p.wave = 6f; p.waveSpeed = 3f
                p.cx += 0.003f * sin(at * 60f)
            }
            Doing.SLEEP -> {
                loaf(p)
                p.cy = -0.19f; p.len = 0.8f; p.arch = 0.07f; p.hx = 0.08f; p.hy = 0.0f; p.tilt = 30f; p.eyes = 0f; p.lookY = 0.5f
                p.tail = 150f; p.curl = -15f; p.wave = 2f; p.waveSpeed = 0.5f
                p.squash = 1f + 0.035f * sin(t * 1.5f)
            }
            Doing.STRETCH -> {
                stand(p)
                if (k < 0.55f) { // front stretch: paws far forward, chest down, rump up
                    val q = sin(PIf * (k / 0.55f))
                    p.set(0, 0.30f + 0.36f * q, 0f); p.set(1, 0.24f + 0.34f * q, 0f)
                    p.cx = 0.05f * q; p.cy = -0.46f + 0.09f * q; p.pitch = 20f * q; p.len = 1f + 0.08f * q; p.arch = -0.05f * q
                    p.hx = 0.24f + 0.06f * q; p.hy = -0.24f + 0.20f * q; p.tilt = -18f * q
                    p.mouth = ((q - 0.5f) * 2.4f).coerceAtLeast(0f); p.eyes = 1f - 0.9f * q; p.ears = 0.35f * q
                    p.tail = 230f + 30f * q; p.curl = 3f
                } else { // back stretch: a hind leg pushed out behind
                    val q = sin(PIf * ((k - 0.55f) / 0.45f))
                    p.pitch = -5f * q; p.cx = 0.05f * q; p.set(2, -0.25f - 0.32f * q, -0.08f * q); p.hy = -0.27f; p.tail = 215f
                }
            }
            Doing.ROLL -> rollPose(p, k)
            Doing.WALK -> walk(p, gait)
            Doing.RUN -> gallop(p, gait)
            Doing.SCRATCH -> scratchPose(p)
            Doing.CLIMB -> climbPose(p)
            Doing.PLAY -> playPose(p)
            Doing.CHASE, Doing.BUG -> chasePose(p)
            Doing.POUNCE -> when (stage) {
                0, 1 -> { crouch(p, 1f); p.lookX = 1f; p.lookY = 0.4f }
                2 -> leap(p, (at / 0.42f).coerceIn(0f, 1f), jumpH)
                else -> land(p)
            }
            Doing.HOP -> hopPose(p)
        }
        val blinking = time > blinkAt && time < blinkAt + 0.18f
        if (blinking && p.happy < 0.5f) p.eyes = min(p.eyes, 0.08f)
        if (time > twitchAt && time < twitchAt + 0.3f) p.twitch = sin(PIf * (time - twitchAt) / 0.3f)
        return p
    }

    /** Lies down, rolls onto its back and paws at the air, rolls back and gets up. */
    private fun rollPose(p: Pose, k: Float) {
        loaf(p)
        p.cy = -0.22f; p.len = 0.95f; p.tailFront = 0f; p.tail = 175f; p.curl = -2f
        val over = when { k < 0.18f -> 0f; k < 0.32f -> (k - 0.18f) / 0.14f; k < 0.74f -> 1f; k < 0.88f -> 1f - (k - 0.74f) / 0.14f; else -> 0f }
        val e = over * over * (3 - 2 * over)
        p.belly = e
        if (e > 0.5f) {
            p.lie = 0f; p.legTurn = 180f
            val a = at * 7f
            p.set(0, 0.34f + 0.05f * sin(a), -0.62f + 0.05f * cos(a)); p.set(1, 0.26f + 0.05f * sin(a + 2f), -0.58f)
            p.set(2, -0.26f + 0.04f * sin(a + 1f), -0.56f); p.set(3, -0.34f, -0.52f + 0.04f * sin(a + 3f))
            for (i in 0 until 4) p.flex[i] = 0.6f
            p.happy = 0.7f; p.eyes = 0.6f; p.hy = -0.15f; p.hx = 0.20f; p.tilt = 160f + 10f * sin(at * 2f)
            p.cx = 0.02f * sin(at * 3f); p.tail = 190f; p.curl = 6f; p.wave = 14f; p.waveSpeed = 3f
        } else { p.tilt = 60f * e; p.hy = -0.2f + 0.2f * e }
    }

    private fun scratchPose(p: Pose) {
        if (stage == 0) { walk(p, gait); return }
        val wall = min(wallAhead(), 1.2f)
        val up = if (stage == 1) min(1f, at / 0.35f) else 1f - min(1f, at / 0.45f)
        stand(p)
        val k = up * up * (3 - 2 * up)
        p.pitch = -60f * k; p.cx = 0.02f * k; p.cy = -0.46f - 0.16f * k; p.len = 1f + 0.06f * k
        val stroke = if (stage == 1) at / 0.32f else 0f
        val reachX = wall - 0.05f
        val a = sin(stroke * PIf); val b = sin((stroke + 0.5f) * PIf)
        val fy1 = -1.0f + 0.16f * (a * a); val fy2 = -0.94f + 0.16f * (b * b)
        p.set(0, lerp(0.30f, reachX, k), lerp(0f, fy1, k)); p.set(1, lerp(0.24f, reachX - 0.03f, k), lerp(0f, fy2, k))
        p.flex[0] = 0.3f * k; p.flex[1] = 0.3f * k
        p.set(2, lerp(-0.25f, -0.06f, k), 0f); p.set(3, lerp(-0.31f, -0.12f, k), 0f)
        p.hx = 0.14f; p.hy = -0.20f; p.tilt = -18f * k; p.ears = 0.4f * k; p.eyes = 1f - 0.5f * k
        p.lookX = 1f; p.lookY = -0.6f; p.tail = 200f; p.curl = 5f; p.wave = 16f; p.waveSpeed = 4f
    }

    private fun climbPose(p: Pose) {
        if (stage == 0) { walk(p, gait); return }
        val wall = wallAhead()
        if (stage >= 4) { // pushing off the edge, down
            stand(p); p.pitch = -30f; p.cy = -0.50f - climb; p.set(0, 0.24f, -climb * 0.5f); p.set(1, 0.18f, -climb * 0.5f)
            p.tail = 230f; p.ears = 0.3f; return
        }
        p.pitch = -86f; p.legTurn = -86f
        p.cx = wall - 0.44f; p.cy = -0.56f - climb
        // In the torso's frame the edge is below the belly: paws climb it as they'd walk the ground, turned upright.
        val stride = 0.28f
        val offs = floatArrayOf(0.25f, 0.75f, 0f, 0.5f)
        val base = floatArrayOf(0.30f, 0.24f, -0.25f, -0.31f)
        val r = p.pitch * PIf / 180f
        for (i in 0 until 4) {
            val moving = stage == 1 || stage == 3
            val u = if (moving) frac(gait + offs[i]) else 0.3f
            val along = if (u < 0.6f) stride * (0.5f - u / 0.6f) else stride * (-0.5f + (u - 0.6f) / 0.4f)
            val off = if (u < 0.6f) 0f else 0.1f * sin(PIf * (u - 0.6f) / 0.4f)
            p.flex[i] = if (u < 0.6f) 0f else 0.6f * sin(PIf * (u - 0.6f) / 0.4f)
            val tx = base[i] + along; val ty = 0.44f - off
            p.set(i, p.cx + tx * cos(r) - ty * sin(r), p.cy + tx * sin(r) + ty * cos(r))
        }
        if (stage == 2) { p.hx = -0.06f; p.hy = -0.20f; p.tilt = 55f + 12f * sin(at * 1.5f); p.lookX = -0.4f; p.lookY = 0.3f; p.ears = 0f }
        else { p.hx = 0.02f; p.hy = -0.24f; p.tilt = -20f; p.lookX = 0.3f; p.lookY = -1f; p.ears = 0.2f }
        p.tail = 120f; p.curl = 5f; p.wave = 12f; p.waveSpeed = 2.5f
    }

    private fun playPose(p: Pose) {
        val r = ballR() / s
        val bx = (ball.x - x) * facing / s; val by = (ball.y - oy) / s
        when (stage) {
            0 -> if (abs(ball.vx) < s * 0.3f && ball.y >= ground - ballR() - 1f) {
                    if (abs(targetX - x) > s * 1.6f) gallop(p, gait) else walk(p, gait)
                } else { sitting(p); p.ears = 0.2f; p.pupil = 0.9f }
            1 -> crouch(p, 1f)
            2 -> {
                crouch(p, 0f); p.cy = -0.38f
                val q = sin(PIf * (at / 0.4f).coerceIn(0f, 1f))
                p.set(0, lerp(0.38f, bx - r * 0.6f, q), lerp(0f, by - r * 0.3f, q)); p.flex[0] = 0.5f * q
            }
            else -> { sitting(p); p.ears = 0.1f }
        }
        p.lookX = if (bx > 0) 1f else -1f; p.lookY = (by + 0.5f).coerceIn(-1f, 1f)
    }

    private fun chasePose(p: Pose) {
        val d = quarry()
        val dx = if (d != null) (d.x - x) * facing / s else 1f
        val dy = if (d != null) (d.y - oy) / s else -0.5f
        when (stage) {
            0 -> {
                if (d != null && abs(d.x - x) > s * 2f) gallop(p, gait)
                else { walk(p, gait, 0.24f, 0.06f); p.cy = -0.38f; p.hx = 0.26f; p.hy = -0.14f; p.ears = 0.5f; p.tail = 190f; p.wave = 4f; p.pupil = 0.9f }
            }
            1 -> crouch(p, 1f)
            2 -> leap(p, (at / 0.46f).coerceIn(0f, 1f), jumpH)
            3 -> land(p)
            else -> {
                sitting(p); p.hy = -0.30f; p.tilt = -22f; p.pupil = 0.9f; p.tail = 175f; p.wave = 12f; p.waveSpeed = 5f
                if (((at * 0.8f) % 1f) < 0.25f) { p.set(0, 0.40f, -0.70f); p.flex[0] = 0.5f }
            }
        }
        p.lookX = dx.coerceIn(-1f, 1f); p.lookY = (dy + 0.6f).coerceIn(-1f, 1f)
    }

    private fun hopPose(p: Pose) {
        val t = toyPos()
        val dy = (t.y - oy) / s
        when (stage) {
            0 -> { if (toyShow < 1f && at < 1f) { sitting(p); p.hy = -0.3f; p.tilt = -20f } else walk(p, gait) }
            1 -> { crouch(p, 1f); p.hy = -0.24f; p.tilt = -24f }
            2 -> {
                val k = (at / 0.55f).coerceIn(0f, 1f)
                val q = sin(PIf * k)
                stand(p)
                val y = -0.50f - jumpH * q
                p.cy = y; p.pitch = -62f * q; p.len = 1.05f
                p.set(0, 0.30f + 0.06f * q, y - 0.62f * q); p.set(1, 0.26f + 0.04f * q, y - 0.52f * q)
                p.flex[0] = 0.3f * q; p.flex[1] = 0.3f * q
                p.set(2, -0.10f, y + 0.42f); p.set(3, -0.16f, y + 0.44f); p.flex[2] = 0.3f; p.flex[3] = 0.3f
                p.hx = 0.10f; p.hy = -0.24f; p.tilt = -30f * q; p.ears = 0.3f; p.pupil = 1f
                p.tail = 160f; p.curl = 3f; p.wave = 6f
            }
            else -> land(p)
        }
        p.lookX = 0.4f; p.lookY = (dy + 1.2f).coerceIn(-1f, 1f)
    }

    // ---------- drawing ----------

    /** Draws the world in dp: scratch marks, toys, the dot, the cat, hearts and z's. */
    fun draw(pen: PetPen) {
        val c = coat
        marks.forEach { m ->
            val a = (1f - m.life / 9f).coerceIn(0f, 1f) * 0.75f
            val p = PetPath()
            for (k in 0 until 3) { val yy = m.y + k * s * 0.045f; p.moveTo(m.x, yy); p.lineTo(m.x - m.vx * s * 0.10f, yy + s * 0.07f) }
            pen.stroke(p, c.line, max(1.2f, s * 0.018f), a)
        }
        if (toyShow > 0f) drawToy(pen)
        if (ballOn) drawBall(pen)
        dot?.let { d ->
            val a = min(1f, dotLife * 2f).coerceIn(0f, 1f)
            pen.fill(PetPath().oval(d.x, d.y, s * 0.075f, s * 0.075f), 0xFFFF3B3B.toInt(), 0.28f * a)
            pen.fill(PetPath().oval(d.x, d.y, s * 0.035f, s * 0.035f), 0xFFFF2020.toInt(), a)
            pen.fill(PetPath().oval(d.x, d.y, s * 0.014f, s * 0.014f), 0xFFFFE0E0.toInt(), a)
        }
        val p = pose
        // A soft shadow on the ground, smaller the higher it is.
        val up = (-p.cy - 0.46f).coerceAtLeast(0f)
        pen.fill(PetPath().oval(x + turned * p.cx * s, ground + s * 0.005f, s * (0.52f - up * 0.25f).coerceAtLeast(0.18f), s * 0.045f), 0xFF000000.toInt(), 0.18f)
        if (abs(turned) > 0.2f) {
            val a = (left + s * 0.06f - x) / (turned * s); val b = (right - s * 0.06f - x) / (turned * s)
            tailLo = min(a, b); tailHi = max(a, b)
        } else { tailLo = -9f; tailHi = 9f }
        pen.save()
        pen.translate(x, oy)
        pen.scale(turned * s, s)
        drawCat(pen, p)
        pen.restore()
        if (bugOn) drawBug(pen)
        bits.forEach { b ->
            val a = min(1f, b.life * 1.6f)
            when (b.kind) {
                1 -> pen.fill(heartPath(b.x, b.y, s * 0.09f), 0xFFF2607A.toInt(), a)
                2 -> pen.stroke(PetPath().moveTo(b.x, b.y).lineTo(b.x + s * 0.09f, b.y).lineTo(b.x, b.y + s * 0.09f).lineTo(b.x + s * 0.09f, b.y + s * 0.09f), c.line, max(1.2f, s * 0.02f), a * 0.7f)
                else -> pen.fill(PetPath().oval(b.x, b.y, s * 0.025f, s * 0.025f), 0xFFFFD34D.toInt(), a)
            }
        }
    }

    private fun heartPath(x: Float, y: Float, r: Float) = PetPath().moveTo(x, y + r * 0.9f)
        .cubicTo(x - r * 1.4f, y - r * 0.1f, x - r * 0.7f, y - r * 1.2f, x, y - r * 0.35f)
        .cubicTo(x + r * 0.7f, y - r * 1.2f, x + r * 1.4f, y - r * 0.1f, x, y + r * 0.9f).close()

    private fun drawBall(pen: PetPen) {
        val r = ballR()
        pen.fill(PetPath().oval(ball.x, ground + s * 0.01f, r * 0.9f, r * 0.25f), 0xFF000000.toInt(), 0.14f)
        pen.save(); pen.translate(ball.x, ball.y); pen.rotate(spin)
        pen.fill(PetPath().oval(0f, 0f, r, r), 0xFFE0566E.toInt())
        val yarn = PetPath()
        yarn.moveTo(-r * 0.9f, -r * 0.2f).quadTo(0f, -r * 0.9f, r * 0.85f, -r * 0.35f)
        yarn.moveTo(-r * 0.95f, r * 0.15f).quadTo(0f, -r * 0.35f, r * 0.95f, r * 0.1f)
        yarn.moveTo(-r * 0.7f, r * 0.6f).quadTo(0f, r * 0.15f, r * 0.7f, r * 0.62f)
        yarn.moveTo(-r * 0.2f, -r * 0.95f).quadTo(r * 0.35f, 0f, -r * 0.1f, r * 0.97f)
        pen.stroke(yarn, 0xFFB0344C.toInt(), r * 0.13f)
        pen.stroke(PetPath().oval(0f, 0f, r, r), 0xFF7E2234.toInt(), r * 0.1f)
        pen.restore()
        pen.stroke(PetPath().moveTo(ball.x, ball.y + r).quadTo(ball.x - sign(ball.vx + 0.01f) * r * 1.6f, ground, ball.x - sign(ball.vx + 0.01f) * r * 2.6f, ground - r * 0.1f), 0xFFC23F57.toInt(), r * 0.12f)
    }

    /** A feather pom-pom on a string from the top edge. */
    private fun drawToy(pen: PetPen) {
        val t = toyPos()
        pen.stroke(PetPath().moveTo(toyX, -2f).lineTo(t.x, t.y), 0xFFD8D2C4.toInt(), max(0.8f, s * 0.015f), 0.9f)
        pen.save(); pen.translate(t.x, t.y); pen.rotate(-toyA * 57.3f)
        val r = s * 0.09f
        for ((ang, col) in listOf(-25f to 0xFF4FB3BF.toInt(), 0f to 0xFFF2C94C.toInt(), 25f to 0xFFE0566E.toInt())) {
            pen.fill(PetPath().oval(0f, r * 1.9f, r * 0.42f, r * 1.25f, ang + 6f * sin(time * 5f + ang)), col)
        }
        pen.fill(PetPath().oval(0f, r * 0.2f, r, r), 0xFFB04AC0.toInt())
        pen.fill(PetPath().oval(-r * 0.3f, -r * 0.15f, r * 0.35f, r * 0.3f), 0xFFFFFFFF.toInt(), 0.25f)
        pen.restore()
    }

    /** The butterfly: wings that open and close as it flaps. */
    private fun drawBug(pen: PetPen) {
        val a = bugFade.coerceIn(0f, 1f)
        val flap = 0.25f + 0.75f * abs(sin(time * 15f))
        val r = s * 0.10f
        pen.save(); pen.translate(bugX, bugY); pen.rotate(8f * sin(time * 3f))
        for (side in floatArrayOf(-1f, 1f)) {
            pen.fill(PetPath().oval(side * r * 0.55f * flap, -r * 0.25f, r * 0.6f * flap, r * 0.55f), 0xFFF59E3B.toInt(), a)
            pen.fill(PetPath().oval(side * r * 0.42f * flap, r * 0.38f, r * 0.42f * flap, r * 0.38f), 0xFFE9732C.toInt(), a)
            pen.fill(PetPath().oval(side * r * 0.8f * flap, -r * 0.5f, r * 0.14f * flap, r * 0.12f), 0xFF2B2420.toInt(), a)
        }
        pen.fill(PetPath().oval(0f, 0f, r * 0.11f, r * 0.55f), 0xFF2B2420.toInt(), a)
        pen.stroke(PetPath().moveTo(0f, -r * 0.5f).quadTo(-r * 0.15f, -r * 0.9f, -r * 0.3f, -r * 1.0f).moveTo(0f, -r * 0.5f).quadTo(r * 0.15f, -r * 0.9f, r * 0.3f, -r * 1.0f), 0xFF2B2420.toInt(), max(0.6f, r * 0.06f), a)
        pen.restore()
    }

    /** The torso's own frame to the cat's: stretched by len, humped by arch, turned by pitch (and flipped on its back). */
    private fun rot(p: Pose, lx: Float, ly: Float): V {
        val r = p.pitch * PIf / 180f
        val u = (lx / 0.46f).coerceIn(-1f, 1f)
        val sx = lx * p.len * (2f - p.squash)
        val sy = ly * p.squash * (1f - 2f * p.belly) - p.arch * (1f - u * u)
        return V(p.cx + sx * cos(r) - sy * sin(r), p.cy + sx * sin(r) + sy * cos(r))
    }

    private fun headAt(p: Pose): V { val c = rot(p, 0.30f, -0.04f); return V(c.x + p.hx, c.y + p.hy) }

    private fun turn(dx: Float, dy: Float, deg: Float): V { val r = deg * PIf / 180f; return V(dx * cos(r) - dy * sin(r), dx * sin(r) + dy * cos(r)) }

    /** A two-bone reach from [j] towards [t]: the middle joint and the end. [forward]: the joint bends towards +x. */
    private fun ik(j: V, t: V, l1: Float, l2: Float, forward: Boolean): Pair<V, V> {
        var dx = t.x - j.x; var dy = t.y - j.y
        var d = hypot(dx, dy)
        if (d < 0.0001f) { dy = 0.0001f; d = 0.0001f }
        val maxD = (l1 + l2) * 0.999f; val minD = abs(l1 - l2) + 0.02f
        if (d > maxD) { dx *= maxD / d; dy *= maxD / d; d = maxD }
        if (d < minD) { dx *= minD / d; dy *= minD / d; d = minD }
        val a = acos(((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f))
        val base = atan2(dy, dx)
        val k1 = V(j.x + l1 * cos(base + a), j.y + l1 * sin(base + a))
        val k2 = V(j.x + l1 * cos(base - a), j.y + l1 * sin(base - a))
        val c1 = dx * (k1.y - j.y) - dy * (k1.x - j.x)
        return (if ((c1 < 0f) == forward) k1 else k2) to V(j.x + dx, j.y + dy)
    }

    /**
     * A leg as a cat has it: the front one shoulder, elbow (bending back), wrist; the hind one hip, knee (bending
     * forward), hock. Both stand on their toes.
     */
    private fun leg(p: Pose, i: Int): Leg {
        val front = i < 2; val far = i % 2 == 1
        val j = if (front) rot(p, 0.27f - (if (far) 0.05f else 0f), 0.03f) else rot(p, -0.29f - (if (far) 0.05f else 0f), 0.02f)
        val px = p.paw[i * 2]; val py = p.paw[i * 2 + 1]
        val f = p.flex[i]
        val v = if (front) { val th = -0.2f + 1.5f * f; turn(0.07f * sin(th), -0.07f * cos(th), p.legTurn) }
                else { val ph = lerp(0.72f + 0.7f * f, 1.5f, max(p.sit, p.lie)); turn(-0.15f * sin(ph), -0.15f * cos(ph), p.legTurn) }
        val low = V(px + v.x, py + v.y)
        val (mid, end) = if (front) ik(j, low, 0.21f, 0.21f, p.belly > 0.5f) else ik(j, low, 0.21f, 0.23f, p.belly <= 0.5f)
        // Out of reach, the paw comes with the end of the leg.
        return Leg(j, mid, end, V(px + end.x - low.x, py + end.y - low.y))
    }

    private fun legInk(ink: Ink, g: Leg, front: Boolean, p: Pose) {
        val tucked = p.lie > 0.5f
        if (!tucked) {
            if (front) { ink.line(g.j, g.a, 0.13f); ink.line(g.a, g.b, 0.078f); ink.line(g.b, g.p, 0.066f) }
            else {
                ink.line(g.a, g.b, 0.075f); ink.line(g.b, g.p, 0.06f)
                val ang = atan2(g.a.y - g.j.y, g.a.x - g.j.x) * 180f / PIf
                val half = hypot(g.a.x - g.j.x, g.a.y - g.j.y) / 2f
                ink.fill(PetPath().oval((g.j.x + g.a.x) / 2f, (g.j.y + g.a.y) / 2f, half + 0.07f + 0.04f * p.sit, 0.105f + 0.045f * p.sit, ang))
            }
        } else if (!front) {
            ink.fill(PetPath().oval(g.j.x + 0.04f, g.j.y + 0.04f, 0.17f, 0.13f, -8f))
            return
        }
        val toe = turn(0.024f, -0.02f, p.legTurn)
        ink.fill(PetPath().oval(g.p.x + toe.x, g.p.y + toe.y, 0.058f, 0.035f, p.legTurn))
    }

    private fun torsoPath(p: Pose): PetPath {
        fun pt(x: Float, y: Float) = rot(p, x, y)
        val path = PetPath()
        val pts = listOf(
            listOf(-0.44f, -0.12f),
            listOf(-0.40f, -0.20f, -0.30f, -0.21f, -0.12f, -0.19f),   // back
            listOf(0.06f, -0.18f, 0.22f, -0.22f, 0.34f, -0.18f),      // withers
            listOf(0.48f, -0.13f, 0.52f, 0.02f, 0.42f, 0.12f),        // chest front
            listOf(0.34f, 0.20f, 0.20f, 0.20f, 0.06f, 0.16f),         // chest
            listOf(-0.08f, 0.12f, -0.22f, 0.13f, -0.32f, 0.14f),      // belly, tucked up to the flank
            listOf(-0.46f, 0.16f, -0.52f, 0.0f, -0.44f, -0.12f),      // rump
        )
        val s0 = pt(pts[0][0], pts[0][1]); path.moveTo(s0.x, s0.y)
        for (k in 1 until pts.size) { val q = pts[k]; val a = pt(q[0], q[1]); val b = pt(q[2], q[3]); val e = pt(q[4], q[5]); path.cubicTo(a.x, a.y, b.x, b.y, e.x, e.y) }
        return path.close()
    }

    private fun stepTail(dt: Float) {
        val p = pose
        var ang = p.tail + p.pitch * 0.6f
        for (i in 0 until tailN) {
            ang += p.curl + p.wave * sin(time * p.waveSpeed - i * 0.5f) * ((i + 1) / tailN.toFloat())
            if (!tailReady) tailA[i] = ang else tailA[i] += (ang - tailA[i]) * min(1f, dt * (15f - i * 0.8f))
        }
        tailReady = true
    }

    private fun drawTail(pen: PetPen, p: Pose) {
        val c = coat
        val seg = 0.058f
        val pts = ArrayList<V>(tailN + 1)
        var cur = rot(p, -0.44f, -0.06f); pts += cur
        for (i in 0 until tailN) {
            val r = tailA[i] * PIf / 180f
            cur = V((cur.x + seg * cos(r)).coerceIn(tailLo, tailHi), min(-0.035f, cur.y + seg * sin(r))); pts += cur
        }
        fun wid(i: Int) = 0.10f - 0.035f * i / tailN
        val ink = Ink()
        for (i in 0 until tailN) ink.line(pts[i], pts[i + 1], wid(i))
        ink.draw(pen, c.fur, c.line, 0.022f)
        // Rings: dark bands across the tail.
        for (i in listOf(3, 6, 9)) {
            val a = pts[i]; val b = pts[i + 1]; val dx = b.x - a.x; val dy = b.y - a.y; val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val h = wid(i) * 0.5f; val m = V(lerp(a.x, b.x, 0.5f), lerp(a.y, b.y, 0.5f))
            pen.stroke(PetPath().moveTo(m.x - dy / l * h, m.y + dx / l * h).lineTo(m.x + dy / l * h, m.y - dx / l * h), c.stripe, 0.032f, 0.8f)
        }
        pen.stroke(PetPath().moveTo(lerp(pts[tailN - 2].x, pts[tailN - 1].x, 0.5f), lerp(pts[tailN - 2].y, pts[tailN - 1].y, 0.5f)).lineTo(pts[tailN].x, pts[tailN].y), c.stripe, wid(tailN - 1), 0.9f)
    }

    private fun drawCat(pen: PetPen, p: Pose) {
        val c = coat
        val ol = 0.022f
        if (p.tailFront < 0.5f) drawTail(pen, p)
        val farLegs = Ink()
        legInk(farLegs, leg(p, 1), true, p); legInk(farLegs, leg(p, 3), false, p)
        farLegs.draw(pen, c.far, c.line, ol)
        // The body with its near legs and neck: one silhouette.
        val hc = headAt(p)
        val nearHind = leg(p, 2); val nearFront = leg(p, 0)
        val body = Ink()
        body.fill(torsoPath(p))
        body.line(rot(p, 0.30f, -0.08f), V(hc.x - 0.04f, hc.y + 0.06f), 0.21f)
        legInk(body, nearHind, false, p); legInk(body, nearFront, true, p)
        body.draw(pen, c.fur, c.line, ol)
        // Shading, the belly, the tabby stripes and the white bib.
        fun curve(a: V, m: V, e: V) = PetPath().moveTo(a.x, a.y).quadTo(m.x, m.y, e.x, e.y)
        pen.stroke(curve(rot(p, -0.40f, 0.12f), rot(p, 0f, 0.20f), rot(p, 0.32f, 0.15f)), c.line, 0.08f, 0.13f)
        pen.stroke(curve(rot(p, -0.38f, -0.14f), rot(p, 0f, -0.19f), rot(p, 0.30f, -0.15f)), 0xFFFFFFFF.toInt(), 0.05f, 0.10f)
        if (p.belly > 0.3f) pen.fill(PetPath().oval(rot(p, -0.02f, 0.11f).x, rot(p, -0.02f, 0.11f).y, 0.24f, 0.055f, p.pitch), c.white, (p.belly - 0.3f) * 0.8f)
        val stripes = PetPath()
        for (sx in floatArrayOf(-0.33f, -0.19f, -0.05f, 0.09f, 0.22f)) {
            val a = rot(p, sx, -0.18f); val m = rot(p, sx + 0.035f, -0.07f); val e = rot(p, sx - 0.005f, 0.04f)
            stripes.moveTo(a.x, a.y).quadTo(m.x, m.y, e.x, e.y)
        }
        pen.stroke(stripes, c.stripe, 0.034f, 0.8f)
        pen.stroke(curve(rot(p, -0.40f, -0.18f), rot(p, 0f, -0.215f), rot(p, 0.34f, -0.17f)), c.stripe, 0.03f, 0.55f)
        if (p.lie <= 0.5f) {
            // The near thigh's front edge, so the haunch reads against the belly; rings on the near legs.
            val g = nearHind
            pen.stroke(curve(V(g.j.x + 0.06f, g.j.y - 0.10f), V(g.a.x + 0.10f, (g.j.y + g.a.y) / 2f), V(g.a.x + 0.02f, g.a.y + 0.03f)), c.line, 0.016f, 0.35f)
            for ((a, b) in listOf(g.a to g.b, nearFront.a to nearFront.b)) {
                val m = V(lerp(a.x, b.x, 0.45f), lerp(a.y, b.y, 0.45f)); val dx = b.x - a.x; val dy = b.y - a.y; val l = hypot(dx, dy).coerceAtLeast(0.001f)
                pen.stroke(PetPath().moveTo(m.x - dy / l * 0.035f, m.y + dx / l * 0.035f).lineTo(m.x + dy / l * 0.035f, m.y - dx / l * 0.035f), c.stripe, 0.025f, 0.7f)
            }
        }
        val bib = rot(p, 0.42f, 0.04f)
        pen.fill(PetPath().oval(bib.x, bib.y, 0.075f, 0.10f, p.pitch), c.white, 0.95f)
        drawHead(pen, p)
        if (p.tailFront >= 0.5f) drawTail(pen, p)
    }

    private fun drawHead(pen: PetPen, p: Pose) {
        val c = coat
        val hc = headAt(p)
        pen.save(); pen.translate(hc.x, hc.y); pen.rotate(p.tilt); pen.scale(0.78f, 0.78f); pen.translate(-0.03f, 0f)
        val ol = 0.028f
        // Ears (the far one first), turning back when it's wary or busy; the near one flicks now and then.
        fun ear(bx: Float, by: Float, deg: Float, far: Boolean) {
            pen.save(); pen.translate(bx, by); pen.rotate(deg)
            val outer = PetPath().moveTo(-0.085f, 0.02f).quadTo(-0.07f, -0.12f, 0.005f, -0.19f).quadTo(0.07f, -0.10f, 0.085f, 0.02f).close()
            pen.stroke(outer, c.line, ol * 2f); pen.fill(outer, if (far) c.far else c.fur)
            pen.fill(PetPath().moveTo(-0.05f, 0.0f).quadTo(-0.04f, -0.09f, 0.003f, -0.145f).quadTo(0.04f, -0.08f, 0.05f, 0.0f).close(), c.pink, 0.8f)
            pen.stroke(PetPath().moveTo(-0.02f, -0.01f).lineTo(-0.035f, -0.08f).moveTo(0.02f, -0.01f).lineTo(0.03f, -0.07f), 0xFFFFFFFF.toInt(), 0.01f, 0.5f)
            pen.restore()
        }
        ear(-0.12f, -0.17f, -16f - 34f * p.ears, true)
        ear(0.17f, -0.19f, 14f + 34f * p.ears + 18f * p.twitch, false)
        val head = PetPath().moveTo(-0.24f, 0.02f)
            .cubicTo(-0.26f, -0.14f, -0.12f, -0.25f, 0.03f, -0.25f)
            .cubicTo(0.18f, -0.25f, 0.31f, -0.14f, 0.30f, 0.01f)
            .cubicTo(0.31f, 0.08f, 0.28f, 0.14f, 0.21f, 0.17f)
            .cubicTo(0.13f, 0.215f, -0.07f, 0.215f, -0.16f, 0.17f)
            .cubicTo(-0.23f, 0.13f, -0.27f, 0.08f, -0.24f, 0.02f).close()
        pen.stroke(head, c.line, ol * 2f); pen.fill(head, c.fur)
        // Cheek ruff.
        pen.stroke(PetPath().moveTo(-0.235f, 0.06f).lineTo(-0.285f, 0.10f).lineTo(-0.215f, 0.115f).moveTo(0.29f, 0.07f).lineTo(0.33f, 0.11f).lineTo(0.27f, 0.12f), c.line, 0.02f, 0.8f)
        // The tabby M on the forehead and the cheek stripes.
        val m = PetPath().moveTo(-0.03f, -0.23f).quadTo(-0.025f, -0.17f, -0.02f, -0.13f).moveTo(0.045f, -0.24f).lineTo(0.045f, -0.15f).moveTo(0.12f, -0.23f).quadTo(0.115f, -0.17f, 0.11f, -0.13f)
            .moveTo(-0.23f, 0.0f).lineTo(-0.15f, 0.03f).moveTo(-0.225f, 0.06f).lineTo(-0.15f, 0.065f).moveTo(0.29f, 0.0f).lineTo(0.22f, 0.03f)
        pen.stroke(m, c.stripe, 0.026f, 0.9f)
        // White muzzle and chin.
        pen.fill(PetPath().oval(0.075f, 0.14f, 0.08f, 0.05f), c.white)
        pen.fill(PetPath().oval(0.03f, 0.095f, 0.062f, 0.048f), c.white)
        pen.fill(PetPath().oval(0.125f, 0.095f, 0.062f, 0.048f), c.white)
        // Eyes: almond-shaped, slit pupils that open wide when it hunts or plays.
        for ((ex, big) in listOf(-0.05f to 0.93f, 0.16f to 1f)) {
            val ey = -0.03f
            if (p.happy > 0.5f) {
                pen.stroke(PetPath().moveTo(ex - 0.048f, ey + 0.015f).quadTo(ex, ey - 0.045f, ex + 0.048f, ey + 0.015f), c.line, 0.026f)
            } else if (p.eyes < 0.12f) {
                pen.stroke(PetPath().moveTo(ex - 0.05f, ey).quadTo(ex, ey + 0.03f, ex + 0.05f, ey), c.line, 0.024f)
            } else {
                val rx = 0.056f * big; val ry = 0.05f * big * p.eyes.coerceAtMost(1f)
                val eye = PetPath().moveTo(ex - rx, ey + 0.004f).quadTo(ex - rx * 0.1f, ey - ry * 2f, ex + rx, ey - 0.006f).quadTo(ex + rx * 0.1f, ey + ry * 1.7f, ex - rx, ey + 0.004f).close()
                pen.stroke(eye, c.line, 0.026f); pen.fill(eye, c.eye)
                val px = ex + p.lookX.coerceIn(-1f, 1f) * rx * 0.28f; val py = ey - ry * 0.12f + p.lookY.coerceIn(-1f, 1f) * ry * 0.25f
                pen.fill(PetPath().oval(px, py, rx * (0.18f + 0.5f * p.pupil), ry * 0.9f), 0xFF0D0E10.toInt())
                pen.fill(PetPath().oval(px + rx * 0.3f, py - ry * 0.45f, 0.012f, 0.012f), 0xFFFFFFFF.toInt(), 0.9f)
                pen.stroke(PetPath().moveTo(ex - rx, ey + 0.004f).quadTo(ex - rx * 0.1f, ey - ry * 2f, ex + rx, ey - 0.006f), c.line, 0.03f)
            }
        }
        // Nose, mouth, whiskers.
        pen.fill(PetPath().moveTo(0.052f, 0.05f).lineTo(0.104f, 0.05f).quadTo(0.104f, 0.066f, 0.078f, 0.08f).quadTo(0.052f, 0.066f, 0.052f, 0.05f).close(), c.pink)
        if (p.mouth > 0.05f) {
            val o = p.mouth.coerceAtMost(1.3f)
            pen.fill(PetPath().oval(0.078f, 0.105f + 0.02f * o, 0.04f + 0.01f * o, 0.035f * o), 0xFF4A1F27.toInt())
            pen.fill(PetPath().oval(0.078f, 0.115f + 0.04f * o, 0.025f, 0.014f * o), c.pink)
            if (o > 0.6f) pen.fill(PetPath().moveTo(0.05f, 0.105f).lineTo(0.058f, 0.13f).lineTo(0.066f, 0.105f).close().moveTo(0.09f, 0.105f).lineTo(0.098f, 0.13f).lineTo(0.106f, 0.105f).close(), 0xFFFFFFFF.toInt())
        } else pen.stroke(PetPath().moveTo(0.078f, 0.08f).lineTo(0.078f, 0.097f).moveTo(0.044f, 0.106f).quadTo(0.078f, 0.114f, 0.078f, 0.097f).quadTo(0.078f, 0.114f, 0.112f, 0.106f), c.line, 0.018f)
        val ws = if (p.pupil > 0.6f) 1.2f else 1f
        val wh = PetPath().moveTo(0.0f, 0.09f).quadTo(-0.12f * ws, 0.06f, -0.25f * ws, 0.065f).moveTo(0.0f, 0.105f).quadTo(-0.12f * ws, 0.11f, -0.24f * ws, 0.13f)
            .moveTo(0.16f, 0.09f).quadTo(0.28f * ws, 0.06f, 0.39f * ws, 0.06f).moveTo(0.16f, 0.105f).quadTo(0.28f * ws, 0.11f, 0.38f * ws, 0.13f)
        pen.stroke(wh, 0xFFFFFFFF.toInt(), 0.009f, 0.8f)
        pen.restore()
    }

    /** What it's doing, in words. */
    fun doingNow(): String = doing.name.lowercase()

    /** Starts one of its moves by name (sit, loaf, groom, yawn, look, walk, run, scratch, climb, play, sleep, stretch, knead, roll, pounce, bug, hop, pet). */
    fun start(what: String) {
        val d = when (what.lowercase()) { "peek" -> Doing.LOOK; "butterfly" -> Doing.BUG; else -> runCatching { Doing.valueOf(what.uppercase()) }.getOrNull() } ?: return
        when (d) {
            Doing.WALK -> { targetX = if (x > w / 2) minX() else maxX(); begin(d, 99f) }
            Doing.RUN -> { targetX = if (x > w / 2) minX() else maxX(); runs = 0; begin(d, 99f) }
            Doing.SLEEP -> begin(d, 10f)
            Doing.CHASE -> { dot = V(if (x > w / 2) minX() else maxX(), ground - s * 0.2f); dotLife = 6f; begin(d, 99f) }
            Doing.PET -> begin(d, 2.8f)
            Doing.LOOK -> begin(d, 4f)
            Doing.SIT -> begin(d, 4f)
            else -> startDoing(d)
        }
    }
}
