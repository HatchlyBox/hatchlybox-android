package com.hatchlybox.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Pictures and motion for drawn widgets: the `canvas` node (vector shapes in SVG path syntax, pixel sprites,
// images), `anim` on any node or shape (motion presets, no maths), and `frames` for images and sprites.

/** Seconds since the widget started moving; null while nothing in it moves. `widget check … motion` fixes it. */
val LocalWidgetTime = compositionLocalOf<State<Float>?> { null }

/** Whether anything in a widget moves: an `anim`, or frames to flip through. */
fun animated(spec: JSONObject?): Boolean = spec?.toString()?.let { it.contains("\"anim\"") || it.contains("\"frames\"") || it.contains("\"pet\"") } == true

/** The time a widget's parts move by, about 30 times a second while it's drawn (frames stop when the screen is off). */
@Composable
fun WidgetTime(w: HomeWidget, content: @Composable () -> Unit) {
    val outer = LocalWidgetTime.current
    val moves = remember(w.stamp) { animated(w.spec) }
    if (outer != null || !moves) { content(); return }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) { withFrameNanos { time.floatValue = (it - start) / 1e9f }; kotlinx.coroutines.delay(25) }
    }
    CompositionLocalProvider(LocalWidgetTime provides time) { content() }
}

// ---------- motion ----------

/**
 * One motion: from rest towards `move` (dx, dy), `rotate` (degrees), `scale` and `fade` (opacity) and back.
 * kind: wave (smooth back and forth every `every` s), pulse (rests, then a quick there-and-back taking `for` s:
 * blinks, twitches, hops), loop (rest to the end steadily, then from rest again: spins, drifting clouds).
 */
class Anim(val kind: String, val every: Float, val delay: Float, val span: Float,
           val dx: Float, val dy: Float, val rotate: Float, val sx: Float, val sy: Float, val fade: Float, val pivot: Offset?) {
    fun amount(t: Float): Float {
        val p = t + delay
        return when (kind) {
            "loop" -> (p / every).let { it - floor(it) }
            "pulse" -> (p - floor(p / every) * every).let { if (it < span) sin(PI * it / span).toFloat() else 0f }
            else -> ((1 - cos(2 * PI * p / every)) / 2).toFloat()
        }
    }

    companion object {
        val Kinds = setOf("wave", "pulse", "loop")
        val Keys = setOf("kind", "every", "delay", "for", "move", "rotate", "scale", "fade", "pivot")

        fun of(o: JSONObject): Anim {
            val move = o.optJSONArray("move")
            val sc = o.opt("scale")
            val (sx, sy) = when (sc) { is JSONArray -> sc.optDouble(0, 1.0).toFloat() to sc.optDouble(1, 1.0).toFloat(); is Number -> sc.toFloat() to sc.toFloat(); else -> 1f to 1f }
            val pivot = o.optJSONArray("pivot")?.let { Offset(it.optDouble(0, 0.0).toFloat(), it.optDouble(1, 0.0).toFloat()) }
            return Anim(o.optString("kind", "wave"), o.optDouble("every", 2.0).toFloat().coerceAtLeast(0.1f), o.optDouble("delay", 0.0).toFloat(),
                o.optDouble("for", 0.25).toFloat().coerceAtLeast(0.05f), move?.optDouble(0, 0.0)?.toFloat() ?: 0f, move?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                o.optDouble("rotate", 0.0).toFloat(), sx, sy, o.optDouble("fade", 1.0).toFloat(), pivot)
        }

        /** `anim` as one motion or a list of them. */
        fun list(v: Any?): List<Anim> = when (v) {
            is JSONObject -> listOf(of(v))
            is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it)?.let(::of) }
            else -> emptyList()
        }
    }
}

/** A node's `anim`: its motions applied when drawn (no layout), around `pivot` as fractions of the node (default its middle). */
@Composable
fun Modifier.widgetAnim(n: JSONObject): Modifier {
    if (!n.has("anim")) return this
    val time = LocalWidgetTime.current ?: return this
    val anims = remember(n) { Anim.list(n.opt("anim")) }
    if (anims.isEmpty()) return this
    return this.graphicsLayer {
        val t = time.value
        var tx = 0f; var ty = 0f; var r = 0f; var sx = 1f; var sy = 1f; var a = 1f
        anims.forEach { an ->
            val f = an.amount(t)
            tx += an.dx * f; ty += an.dy * f; r += an.rotate * f
            sx *= 1 + (an.sx - 1) * f; sy *= 1 + (an.sy - 1) * f; a *= 1 + (an.fade - 1) * f
        }
        val p = anims.firstNotNullOfOrNull { it.pivot }
        transformOrigin = TransformOrigin(p?.x ?: 0.5f, p?.y ?: 0.5f)
        translationX = tx.dp.toPx(); translationY = ty.dp.toPx(); rotationZ = r; scaleX = sx; scaleY = sy; alpha = a.coerceIn(0f, 1f)
    }
}

// ---------- the canvas node ----------

private val ShapeKinds = setOf("rect", "circle", "ellipse", "line", "path", "text", "sprite", "image", "group")
private val ShapeCommon = setOf("shape", "name", "if", "anim", "pivot", "fill", "stroke", "width", "opacity", "cap", "tap")
private val ShapeKeys = mapOf(
    "rect" to setOf("x", "y", "w", "h", "corner"), "circle" to setOf("x", "y", "r"), "ellipse" to setOf("x", "y", "rx", "ry"),
    "line" to setOf("x1", "y1", "x2", "y2"), "path" to setOf("d"),
    "text" to setOf("text", "x", "y", "size", "color", "align", "bold", "font"),
    "sprite" to setOf("rows", "frames", "fps", "palette", "x", "y", "pixel"),
    "image" to setOf("src", "frames", "fps", "x", "y", "w", "h"),
    "group" to setOf("shapes"),
)

private fun JSONObject.f(k: String, d: Float = 0f) = optDouble(k, d.toDouble()).toFloat()
private fun shapesOf(o: JSONObject): List<JSONObject> = o.optJSONArray("shapes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

/** A shape made ready to draw: its outline (for rects, circles, ellipses, lines and paths) and its motions. */
private class Shape(val o: JSONObject, val kind: String, val path: Path?, val anims: List<Anim>, val children: List<Shape>) {
    /** Where it is at rest, in the canvas's units: what a tap is matched against. */
    val bounds: Rect? by lazy {
        when (kind) {
            "group" -> children.mapNotNull { it.bounds }.reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
            "text" -> o.f("size", 10f).let { s -> Rect(o.f("x") - s * 2, o.f("y") - s * 0.7f, o.f("x") + s * 2, o.f("y") + s * 0.7f) }
            "sprite" -> spriteFrames(o).firstOrNull()?.let { rows -> val p = o.f("pixel", 1f); Rect(o.f("x"), o.f("y"), o.f("x") + (rows.maxOfOrNull { it.length } ?: 0) * p, o.f("y") + rows.size * p) }
            "image" -> Rect(o.f("x"), o.f("y"), o.f("x") + o.f("w", 10f), o.f("y") + o.f("h", 10f))
            else -> path?.getBounds()
        }
    }
}

private fun spriteFrames(o: JSONObject): List<List<String>> {
    fun rows(a: JSONArray) = (0 until a.length()).map { a.optString(it) }
    o.optJSONArray("frames")?.let { fr -> return (0 until fr.length()).mapNotNull { fr.optJSONArray(it)?.let(::rows) } }
    return o.optJSONArray("rows")?.let { listOf(rows(it)) }.orEmpty()
}

private fun outline(o: JSONObject, kind: String): Path? = when (kind) {
    "rect" -> Path().apply { addRoundRect(RoundRect(Rect(o.f("x"), o.f("y"), o.f("x") + o.f("w"), o.f("y") + o.f("h")), CornerRadius(o.f("corner")))) }
    "circle" -> Path().apply { addOval(Rect(Offset(o.f("x"), o.f("y")), o.f("r"))) }
    "ellipse" -> Path().apply { addOval(Rect(o.f("x") - o.f("rx"), o.f("y") - o.f("ry"), o.f("x") + o.f("rx"), o.f("y") + o.f("ry"))) }
    "line" -> Path().apply { moveTo(o.f("x1"), o.f("y1")); lineTo(o.f("x2"), o.f("y2")) }
    "path" -> runCatching { PathParser().parsePathString(o.optString("d")).toPath() }.getOrNull()
    else -> null
}

private fun compile(list: List<JSONObject>): List<Shape> = list.map { o ->
    val kind = o.optString("shape")
    Shape(o, kind, outline(o, kind), Anim.list(o.opt("anim")), if (kind == "group") compile(shapesOf(o)) else emptyList())
}

/** Every colour a canvas names, to look up in the theme before drawing. */
private fun colourNames(list: List<JSONObject>, into: MutableSet<String> = linkedSetOf()): Set<String> {
    list.forEach { o ->
        listOf("fill", "stroke", "color").forEach { k ->
            when (val v = o.opt(k)) { is String -> into += v; is JSONObject -> listOf("from", "to").forEach { c -> v.optString(c).takeIf { it.isNotBlank() }?.let { into += it } } }
        }
        o.optJSONObject("palette")?.let { p -> p.keys().forEach { into += p.optString(it) } }
        colourNames(shapesOf(o), into)
    }
    return into
}

/**
 * A picture drawn from shapes in its own units (`view`: [width, height], default 100×100), scaled to fit the node
 * (`fit`: contain, or cover to fill it and crop). Shapes draw in order; groups move together; a shape's or
 * group's `tap` runs when it is tapped, else the tap goes to the widget.
 */
@Composable
internal fun CanvasNode(n: JSONObject, state: JSONObject, item: Pair<Any?, Int>?, dir: File, act: (JSONObject) -> Unit, m: Modifier) {
    val view = n.optJSONArray("view")
    val vw = view?.optDouble(0, 100.0)?.toFloat()?.coerceAtLeast(1f) ?: 100f
    val vh = view?.optDouble(1, 100.0)?.toFloat()?.coerceAtLeast(1f) ?: 100f
    val cover = n.optString("fit") == "cover"
    val shapes = remember(n) { compile(shapesOf(n)) }
    val names = remember(n) { colourNames(shapesOf(n)).toList() }
    val colours = names.associateWith { color(fill(it, state, item), Color.Unspecified) }
    val textColour = color("text")
    val time = LocalWidgetTime.current
    val measurer = rememberTextMeasurer()
    val sized = if (n.has("height") || n.has("weight")) m.fillMaxWidth() else m.fillMaxWidth().aspectRatio(vw / vh)
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun place(size: IntSize): Triple<Float, Float, Float> {
        val s = if (cover) maxOf(size.width / vw, size.height / vh) else minOf(size.width / vw, size.height / vh)
        return Triple(s, (size.width - vw * s) / 2, (size.height - vh * s) / 2)
    }
    fun shown(o: JSONObject) = !o.has("if") || o.optString("if").trim().let { c -> if (c.startsWith("!")) !truthyValue(raw(c.drop(1), state, item)) else truthyValue(raw(c, state, item)) }
    val tappable = remember(n) { n.toString().contains("\"tap\"") }
    val input = if (!tappable) Modifier else Modifier.pointerInput(n) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if (up.uptimeMillis - down.uptimeMillis > 450 || (up.position - down.position).getDistance() > 24f) return@awaitEachGesture
            val (s, ox, oy) = place(box)
            val p = Offset((up.position.x - ox) / s, (up.position.y - oy) / s)
            // The topmost shown shape with a tap under the finger (with a little slack for small parts).
            fun hit(list: List<Shape>): JSONObject? {
                for (sh in list.asReversed()) {
                    if (!shown(sh.o)) continue
                    if (sh.kind == "group") hit(sh.children)?.let { return it }
                    val tap = sh.o.optJSONObject("tap") ?: continue
                    if (sh.bounds?.inflate(4f)?.contains(p) == true) return tap
                }
                return null
            }
            hit(shapes)?.let { a -> up.consume(); act(a) }
        }
    }
    Canvas(sized.clipToBounds().then(input)) {
        box = IntSize(size.width.toInt(), size.height.toInt())
        val t = time?.value ?: 0f
        val (s, ox, oy) = place(box)
        translate(ox, oy) { scale(s, s, Offset.Zero) { draw(shapes, t, colours, textColour, state, item, dir, measurer, 1f) } }
    }
}

private fun truthyValue(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is JSONArray -> v.length() > 0
    is String -> v.isNotBlank() && v != "false" && v != "0"
    else -> true
}

private fun DrawScope.draw(list: List<Shape>, t: Float, colours: Map<String, Color>, text: Color, state: JSONObject, item: Pair<Any?, Int>?,
                            dir: File, measurer: androidx.compose.ui.text.TextMeasurer, alpha: Float) {
    for (sh in list) {
        val o = sh.o
        if (o.has("if")) { val c = o.optString("if").trim(); val on = if (c.startsWith("!")) !truthyValue(raw(c.drop(1), state, item)) else truthyValue(raw(c, state, item)); if (!on) continue }
        var tx = 0f; var ty = 0f; var r = 0f; var sx = 1f; var sy = 1f; var a = alpha * o.f("opacity", 1f)
        sh.anims.forEach { an ->
            val f = an.amount(t)
            tx += an.dx * f; ty += an.dy * f; r += an.rotate * f
            sx *= 1 + (an.sx - 1) * f; sy *= 1 + (an.sy - 1) * f; a *= 1 + (an.fade - 1) * f
        }
        a = a.coerceIn(0f, 1f)
        if (a <= 0.003f) continue
        val pivot = (o.optJSONArray("pivot")?.let { Offset(it.optDouble(0, 0.0).toFloat(), it.optDouble(1, 0.0).toFloat()) }
            ?: sh.anims.firstNotNullOfOrNull { it.pivot } ?: sh.bounds?.center ?: Offset.Zero)
        withTransform({
            translate(tx, ty)
            if (r != 0f) rotate(r, pivot)
            if (sx != 1f || sy != 1f) scale(sx, sy, pivot)
        }) { one(sh, t, colours, text, state, item, dir, measurer, a) }
    }
}

private fun DrawScope.one(sh: Shape, t: Float, colours: Map<String, Color>, text: Color, state: JSONObject, item: Pair<Any?, Int>?,
                           dir: File, measurer: androidx.compose.ui.text.TextMeasurer, a: Float) {
    val o = sh.o
    fun col(name: String?, fallback: Color = text) = name?.let { colours[it] }?.takeIf { it != Color.Unspecified } ?: fallback
    when (sh.kind) {
        "group" -> draw(sh.children, t, colours, text, state, item, dir, measurer, a)
        "text" -> {
            val size = o.f("size", 10f)
            val style = TextStyle(color = col(o.optString("color").ifBlank { null }), fontSize = size.toSp(),
                fontWeight = if (o.optBoolean("bold")) FontWeight.SemiBold else null,
                fontFamily = when (o.optString("font")) { "mono" -> FontFamily.Monospace; "serif" -> FontFamily.Serif; else -> null })
            val laid = measurer.measure(fill(o.optString("text"), state, item), style)
            val x = o.f("x") - when (o.optString("align", "center")) { "start" -> 0f; "end" -> laid.size.width.toFloat(); else -> laid.size.width / 2f }
            drawText(laid, topLeft = Offset(x, o.f("y") - laid.size.height / 2f), alpha = a)
        }
        "sprite" -> {
            val frames = spriteFrames(o).takeIf { it.isNotEmpty() } ?: return
            val rows = frames[(floor(t * o.f("fps", 4f)).toInt().coerceAtLeast(0)) % frames.size]
            val px = o.f("pixel", 1f); val x0 = o.f("x"); val y0 = o.f("y")
            val pal = o.optJSONObject("palette")
            rows.forEachIndexed { j, row ->
                var i = 0
                while (i < row.length) {
                    val ch = row[i]
                    var k = i + 1
                    while (k < row.length && row[k] == ch) k++
                    val c = if (ch == '.' || ch == ' ') null else if (pal != null) pal.optString(ch.toString()).takeIf { it.isNotBlank() }?.let { col(it, Color.Unspecified) }?.takeIf { it != Color.Unspecified } else col(o.optString("fill").ifBlank { null })
                    // A hair of overlap so neighbouring pixels never show a seam when scaled.
                    if (c != null) drawRect(c, Offset(x0 + i * px, y0 + j * px), Size((k - i) * px + 0.02f, px + 0.02f), alpha = a)
                    i = k
                }
            }
        }
        "image" -> {
            val srcs = o.optJSONArray("frames")?.let { fr -> (0 until fr.length()).map { fr.optString(it) } } ?: listOf(o.optString("src"))
            val src = fill(srcs[(floor(t * o.f("fps", 6f)).toInt().coerceAtLeast(0)) % srcs.size], state, item)
            val file = File(dir, src).takeIf { src.isNotBlank() && it.canonicalPath.startsWith(dir.canonicalPath) } ?: return
            val img = WidgetImages.get(file) ?: return
            drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(o.f("x").toInt(), o.f("y").toInt()),
                dstSize = IntSize(o.f("w", img.width.toFloat()).toInt().coerceAtLeast(1), o.f("h", img.height.toFloat()).toInt().coerceAtLeast(1)), alpha = a)
        }
        else -> {
            val path = sh.path ?: return
            val stroke = o.optString("stroke").ifBlank { null }
            val fillV = o.opt("fill")
            val bounds = sh.bounds ?: Rect.Zero
            // A fill is a colour, or a gradient {from, to, dir: down|right|out}.
            val brush: Brush? = when (fillV) {
                is JSONObject -> {
                    val from = col(fillV.optString("from")); val to = col(fillV.optString("to"))
                    when (fillV.optString("dir", "down")) {
                        "right" -> Brush.horizontalGradient(listOf(from, to), bounds.left, bounds.right)
                        "out" -> Brush.radialGradient(listOf(from, to), bounds.center, maxOf(bounds.width, bounds.height).coerceAtLeast(1f) / 2)
                        else -> Brush.verticalGradient(listOf(from, to), bounds.top, bounds.bottom)
                    }
                }
                is String -> if (fillV == "none") null else androidx.compose.ui.graphics.SolidColor(col(fillV))
                else -> if (stroke == null && sh.kind != "line") androidx.compose.ui.graphics.SolidColor(text) else null
            }
            if (brush != null && sh.kind != "line") drawPath(path, brush, alpha = a, style = Fill)
            if (stroke != null || sh.kind == "line") {
                val cap = when (o.optString("cap", "round")) { "butt" -> StrokeCap.Butt; "square" -> StrokeCap.Square; else -> StrokeCap.Round }
                drawPath(path, col(stroke), alpha = a, style = Stroke(o.f("width", 1f), cap = cap, join = StrokeJoin.Round))
            }
        }
    }
}

// ---------- checking ----------

/** What in a canvas can't be drawn as written. */
internal fun lintCanvas(n: JSONObject, here: String, dir: File, knownColour: (String) -> Boolean): List<String> {
    val out = mutableListOf<String>()
    n.optJSONArray("view")?.let { if (it.length() != 2 || it.optDouble(0, 0.0) <= 0 || it.optDouble(1, 0.0) <= 0) out += "$here: view is [width, height], two positive numbers" }
    if (n.has("fit") && n.optString("fit") !in setOf("contain", "cover")) out += "$here: fit is contain or cover"
    if (!n.has("shapes")) out += "$here: a canvas needs \"shapes\": [ … ]"
    fun walk(list: JSONArray?, path: String) {
        if (list == null) return
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: run { out += "$path[$i] isn't a shape object"; null } ?: continue
            val kind = o.optString("shape")
            val at = "$path[$i]" + (o.optString("name").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: " ($kind)")
            val keys = ShapeKeys[kind]
            if (keys == null) { out += "$at: \"shape\" must be one of ${ShapeKinds.joinToString()}" + (if (o.has("type")) " (shapes use \"shape\", not \"type\")" else ""); continue }
            o.keys().forEach { if (it !in ShapeCommon && it !in keys) out += "$at: \"$it\" isn't a $kind key, so it's ignored" }
            listOf("stroke", "color").forEach { k -> o.optString(k).takeIf { o.has(k) && !knownColour(it) }?.let { out += "$at: colour \"$it\" isn't known" } }
            when (val f = o.opt("fill")) {
                is String -> if (f != "none" && !knownColour(f)) out += "$at: fill \"$f\" isn't a known colour"
                is JSONObject -> listOf("from", "to").forEach { c -> if (!knownColour(f.optString(c)) || f.optString(c).isBlank()) out += "$at: a gradient fill needs \"from\" and \"to\" colours" }
            }
            if (kind == "path") {
                val d = o.optString("d")
                if (d.isBlank() || runCatching { PathParser().parsePathString(d) }.isFailure) out += "$at: \"d\" isn't SVG path data (like \"M10 20 Q30 0 50 20 Z\")"
            }
            if (kind == "sprite") {
                if (spriteFrames(o).isEmpty()) out += "$at: a sprite needs \"rows\" (a list of strings) or \"frames\" (a list of those)"
                o.optJSONObject("palette")?.let { p -> p.keys().forEach { k -> if (!knownColour(p.optString(k))) out += "$at: palette \"$k\" colour \"${p.optString(k)}\" isn't known" } }
            }
            if (kind == "image") {
                val srcs = o.optJSONArray("frames")?.let { fr -> (0 until fr.length()).map { fr.optString(it) } } ?: listOf(o.optString("src"))
                srcs.filter { it.isNotBlank() && !it.contains('{') && !File(dir, it).isFile }.forEach { out += "$at: there's no image \"$it\" in the widget's folder" }
            }
            out += lintAnim(o.opt("anim"), at)
            if (kind == "group") walk(o.optJSONArray("shapes"), "$at.shapes")
        }
    }
    walk(n.optJSONArray("shapes"), "$here.shapes")
    return out
}

internal fun lintAnim(v: Any?, at: String): List<String> {
    val list = when (v) { null -> return emptyList(); is JSONObject -> listOf(v); is JSONArray -> (0 until v.length()).map { v.opt(it) }; else -> return listOf("$at: anim is an object like {\"move\": [0, -3], \"every\": 2} or a list of them") }
    val out = mutableListOf<String>()
    list.forEach { a ->
        val o = a as? JSONObject ?: run { out += "$at: anim is an object like {\"move\": [0, -3], \"every\": 2}"; return@forEach }
        o.keys().forEach { if (it !in Anim.Keys) out += "$at: anim \"$it\" isn't a motion key (${Anim.Keys.joinToString()})" }
        if (o.has("kind") && o.optString("kind") !in Anim.Kinds) out += "$at: anim kind is wave, pulse or loop"
        if (listOf("move", "rotate", "scale", "fade").none { o.has(it) }) out += "$at: the anim moves nothing: give it move, rotate, scale or fade"
    }
    return out
}

/** How many parts of a widget move, and the longest motion's period (for `widget check … motion`). */
fun motionOf(spec: JSONObject?): Pair<Int, Float> {
    var parts = 0; var longest = 0f
    fun walk(v: Any?) {
        when (v) {
            is JSONObject -> {
                if (v.has("anim")) { parts++; Anim.list(v.opt("anim")).forEach { longest = maxOf(longest, it.every) } }
                if (v.optString("type") == "pet") { parts++; longest = maxOf(longest, 6f) }
                if (v.has("frames")) { parts++; longest = maxOf(longest, (v.optJSONArray("frames")?.length() ?: 1) / v.optDouble("fps", 4.0).toFloat().coerceAtLeast(0.5f)) }
                v.keys().forEach { k -> if (k != "anim") walk(v.opt(k)) }
            }
            is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
        }
    }
    walk(spec)
    return parts to longest
}

// ---------- the pet node ----------

/** Draws a [PetCat] on a Compose canvas: its paths become Compose paths, its transform stack the canvas's. */
private class ComposePen(val canvas: androidx.compose.ui.graphics.Canvas) : PetPen {
    private val paint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true }
    override fun save() = canvas.save()
    override fun restore() = canvas.restore()
    override fun translate(x: Float, y: Float) = canvas.translate(x, y)
    override fun rotate(degrees: Float) = canvas.rotate(degrees)
    override fun scale(sx: Float, sy: Float) = canvas.scale(sx, sy)
    private fun path(p: PetPath) = Path().also { out ->
        p.forEach({ x, y -> out.moveTo(x, y) }, { x, y -> out.lineTo(x, y) }, { a, b, x, y -> out.quadraticTo(a, b, x, y) },
            { a, b, c, d, x, y -> out.cubicTo(a, b, c, d, x, y) }, { out.close() })
    }
    override fun fill(p: PetPath, argb: Int, alpha: Float) {
        paint.style = androidx.compose.ui.graphics.PaintingStyle.Fill
        paint.color = Color(argb).let { it.copy(alpha = it.alpha * alpha.coerceIn(0f, 1f)) }
        canvas.drawPath(path(p), paint)
    }
    override fun stroke(p: PetPath, argb: Int, width: Float, alpha: Float) {
        paint.style = androidx.compose.ui.graphics.PaintingStyle.Stroke
        paint.strokeWidth = width; paint.strokeCap = StrokeCap.Round; paint.strokeJoin = StrokeJoin.Round
        paint.color = Color(argb).let { it.copy(alpha = it.alpha * alpha.coerceIn(0f, 1f)) }
        canvas.drawPath(path(p), paint)
    }
}

/**
 * The `pet` node: a cat that lives in the card (Pet.kt), sized and behaving by the card's real size. A tap on it
 * pets it; a tap elsewhere is a laser dot it chases; press, hold a moment and drag to lead the dot around. A
 * quick swipe still turns the home page, and a long press still opens the widget's menu.
 */
@Composable
internal fun PetNode(n: JSONObject, id: String, m: Modifier) {
    val cat = remember(id, n.optString("coat")) { PetCat(id.hashCode().toLong(), Coat.of(n.optString("coat"))) }
    val time = LocalWidgetTime.current
    // The widget's clock starts again from 0 when its card is made again; the cat's time only goes forward.
    val base = remember(cat) { floatArrayOf(Float.NaN) }
    var lastTapMillis by remember(cat) { mutableStateOf(0L) }
    val input = Modifier.pointerInput(cat) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var leading = false
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                val held = c.uptimeMillis - down.uptimeMillis
                val moved = (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                if (!c.pressed) {
                    if (leading) cat.release()
                    else if (!moved && held < 500) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapMillis < 320L) {
                            cat.doubleTap(c.position.x / density, c.position.y / density)
                            lastTapMillis = 0L
                        } else {
                            cat.tap(c.position.x / density, c.position.y / density)
                            lastTapMillis = now
                        }
                        c.consume()
                    }
                    break
                }
                // Moving at once is a swipe (the page's); held still first, then moved, it leads the dot.
                if (!leading && moved) { if (held < 220 || c.isConsumed) break; leading = true }
                if (leading) { cat.drag(c.position.x / density, c.position.y / density); c.consume() }
            }
        }
    }
    Canvas(m.clipToBounds().then(input)) {
        val t = time?.value ?: 0f
        if (base[0].isNaN() || t + base[0] < cat.time - 0.5f) base[0] = cat.time - t
        cat.resize(size.width / density, size.height / density)
        cat.advanceTo(t + base[0])
        scale(density, density, Offset.Zero) { cat.draw(ComposePen(drawContext.canvas)) }
    }
}
