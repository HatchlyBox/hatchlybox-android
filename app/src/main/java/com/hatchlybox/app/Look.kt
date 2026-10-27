@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hatchlybox.nightly

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// The launcher's look has two layers. A style is the shape of everything: corners, borders, glow, type,
// motion and how the clock and widgets are framed (Soft, Old school, Neon, Glass, Tiles, or one an agent made).
// A palette is its colours: four main ones and seven tones (red to magenta) for widgets and accents, light
// and dark. Then a background (the style's glow, plain, the phone's wallpaper, or a photo) and light or dark.
// Agents make and change themes as JSON in /root/themes/<id>/theme.json (THEMES.md), checked with the
// `theme` command; what the launcher ignores, and why, goes to /root/themes/errors.txt. Saved themes show at once.

/** The seven tones every palette has, by name (widgets use these names). */
val ToneNames = listOf("red", "orange", "yellow", "green", "cyan", "blue", "magenta")

/** WCAG contrast ratio between two colours (1 to 21). */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** The colour, moved toward black or white (away from the background) only as far as it needs to read on it. */
fun readable(c: Color, on: Color, ratio: Float): Color {
    if (contrast(c, on) >= ratio) return c
    val to = if (on.luminance() > 0.4f) Color.Black else Color.White
    var t = 0.05f
    while (t < 1f) { val x = lerp(c, to, t); if (contrast(x, on) >= ratio) return x; t += 0.05f }
    return to
}

/** Seven tones in the accent's own strength, for palettes that don't name them. */
fun deriveTones(accent: Color, surface: Color): List<Color> {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val sat = hsv[1].coerceIn(0.45f, 0.85f); val v = hsv[2].coerceIn(0.55f, 0.95f)
    return listOf(4f, 26f, 46f, 135f, 185f, 218f, 300f).map { h -> readable(Color.hsv(h, sat, v), surface, 3f) }
}

/**
 * A palette: surface, text (ink), accent and a second colour (glow), plus the seven tones, light and dark.
 * style: the style it was made for (a full theme in the picker), native: the mode it was designed in.
 */
class ThemeDef(
    val id: String, val name: String,
    val surface: Color, val ink: Color, val accent: Color, val glow: Color,
    val surfaceDark: Color, val inkDark: Color, val accentDark: Color, val glowDark: Color,
    tonesLight: List<Color>? = null, tonesDark: List<Color>? = null,
    val style: String = "", val native: String = "",
) {
    private val light = tonesLight?.takeIf { it.size == 7 } ?: deriveTones(accent, surface)
    private val dark = tonesDark?.takeIf { it.size == 7 } ?: deriveTones(accentDark, surfaceDark)
    fun tones(dark: Boolean) = if (dark) this.dark else light
    fun tone(name: String, dark: Boolean) = tones(dark).getOrNull(ToneNames.indexOf(name))
}

private fun hexc(s: String) = Color(android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s"))

/**
 * A palette from a theme made for one mode (the Omarchy themes are), its other mode worked out to match:
 * the same accent and tones, moved just enough to read on the other surface. tones: red orange yellow green
 * cyan blue magenta as hex, "-" for one the theme doesn't have (made from its neighbours).
 */
private fun oneMode(id: String, name: String, style: String, native: String, bg: String, fg: String, accent: String, glow: String, tones: String): ThemeDef {
    val b = hexc(bg); val f = hexc(fg); val a = hexc(accent); val g = hexc(glow)
    val raw = tones.split(' ').filter { it.isNotBlank() }.map { if (it == "-") null else hexc(it) }
    val t = raw.mapIndexed { i, c -> c ?: lerp(raw.getOrNull(i - 1) ?: a, raw.getOrNull(i + 1) ?: a, 0.5f) }
    return if (native == "dark") {
        val s = lerp(Color(0xFFFAF9F6), a, 0.06f)
        ThemeDef(id, name, s, readable(lerp(b, Color.Black, 0.3f), s, 7f), readable(a, s, 4.5f), readable(g, s, 3f),
            b, readable(f, b, 4.5f), a, g, t.map { readable(it, s, 3f) }, t, style, native)
    } else {
        val s = lerp(Color(0xFF111214), a, 0.07f)
        ThemeDef(id, name, b, readable(f, b, 4.5f), a, g,
            s, readable(lerp(b, Color.White, 0.2f), s, 7f), readable(a, s, 4.5f), readable(g, s, 3f), t, t.map { readable(it, s, 3f) }, style, native)
    }
}

val Themes = listOf(
    ThemeDef("indigo", "Indigo", Color(0xFFFBF8FF), Color(0xFF1B1B21), Color(0xFF4858AB), Color(0xFF77536D), Color(0xFF121318), Color(0xFFE3E1E9), Color(0xFFBAC3FF), Color(0xFFE5BAD8)),
    ThemeDef("paper", "Paper", Color(0xFFF6F1E7), Color(0xFF2A2620), Color(0xFFB4532A), Color(0xFF6B8F71), Color(0xFF1C1A17), Color(0xFFECE5D8), Color(0xFFE3946B), Color(0xFF9CC3A2)),
    ThemeDef("midnight", "Midnight", Color(0xFFEEF3F8), Color(0xFF0F1B2A), Color(0xFF0E7490), Color(0xFF7C5CD6), Color(0xFF0B1220), Color(0xFFDCE6F2), Color(0xFF5ED4E6), Color(0xFFA78BFA)),
    ThemeDef("forest", "Forest", Color(0xFFF2F5EE), Color(0xFF1A2419), Color(0xFF2F6B3A), Color(0xFFB07D1A), Color(0xFF101612), Color(0xFFDDE6DA), Color(0xFF8FD19E), Color(0xFFD9A441)),
    ThemeDef("dusk", "Dusk", Color(0xFFFAF2F6), Color(0xFF2A1B26), Color(0xFFA23E7A), Color(0xFFC0702F), Color(0xFF1A1220), Color(0xFFEEDDEB), Color(0xFFF0A6CA), Color(0xFFF2B880)),
    ThemeDef("mono", "Mono", Color(0xFFF7F7F7), Color(0xFF161616), Color(0xFF3A3A3A), Color(0xFF8A8A8A), Color(0xFF0E0E0E), Color(0xFFEDEDED), Color(0xFFE0E0E0), Color(0xFF9E9E9E)),
    ThemeDef("synth", "Synth", Color(0xFFF5F0FF), Color(0xFF1A1030), Color(0xFFD1009B), Color(0xFF0091C2), Color(0xFF07040F), Color(0xFFF2E9FF), Color(0xFFFF2BD6), Color(0xFF21E6FF)),
    ThemeDef("terminal", "Phosphor", Color(0xFFF1F5EC), Color(0xFF18230F), Color(0xFF2E7D32), Color(0xFFB8860B), Color(0xFF050805), Color(0xFFC8F7C5), Color(0xFF39FF6A), Color(0xFFFFB000)),
    // Full themes, each after one of Omarchy's, with its own name and the style it suits.
    ThemeDef("marshmallow", "Marshmallow Cloud",
        hexc("eff1f5"), hexc("4c4f69"), hexc("1e66f5"), hexc("ea76cb"), hexc("1e1e2e"), hexc("cdd6f4"), hexc("89b4fa"), hexc("f5c2e7"),
        "d20f39 d84e2b df8e1d 40a02b 179299 1e66f5 ea76cb".split(' ').map { readable(hexc(it), hexc("eff1f5"), 3f) },
        "f38ba8 f6b6ab f9e2af a6e3a1 94e2d5 89b4fa f5c2e7".split(' ').map(::hexc), "soft", ""),
    oneMode("neonrain", "Neon Rain", "neon", "dark", "1a1b26", "a9b1d6", "7aa2f7", "ad8ee6", "f7768e eb927b e0af68 9ece6a 449dab 7aa2f7 ad8ee6"),
    oneMode("tokyonight", "Tokyo Night", "tiled", "dark", "1a1b26", "c0caf5", "7aa2f7", "bb9af7", "f7768e ff9e64 e0af68 9ece6a 7dcfff 7aa2f7 bb9af7"),
    oneMode("mainframe", "Mainframe", "oldschool", "dark", "0B0C16", "ddf7ff", "82FB9C", "7cf8f7", "50f872 50f7a3 50f7d4 4fe88f 7cf8f7 829dd4 86a7df"),
    oneMode("arcade", "Sunset Arcade", "neon", "dark", "05182e", "f6dcac", "faa968", "f85525", "f85525 e97b3c faa968 028391 8cbfb8 3f8f8a 3f8f8a"),
    oneMode("fjord", "Fjord", "glass", "dark", "2e3440", "d8dee9", "88c0d0", "b48ead", "bf616a d5967a ebcb8b a3be8c 88c0d0 81a1c1 b48ead"),
    oneMode("moss", "Moss Hollow", "soft", "dark", "2d353b", "d3c6aa", "7fbbb3", "a7c080", "e67e80 e09d7f dbbc7f a7c080 83c092 7fbbb3 d699b6"),
    oneMode("campfire", "Campfire", "tiled", "dark", "282828", "d4be98", "7daea3", "e1875c", "ea6962 e1875c d8a657 a9b665 89b482 7daea3 d3869b"),
    oneMode("greatwave", "Great Wave", "soft", "dark", "1f1f28", "dcd7ba", "7e9cd8", "c0a36e", "c34043 c17158 c0a36e 76946a 6a9589 7e9cd8 957fb8"),
    oneMode("jadealley", "Jade Alley", "neon", "dark", "111c18", "C1C497", "2DD5B7", "D2689C", "FF5345 a2734b 459451 549e6a 2DD5B7 509475 D2689C"),
    oneMode("espresso", "Espresso", "soft", "dark", "2c2525", "e6d9db", "f38d70", "f9cc6c", "fd6883 fb9a77 f9cc6c adda78 85dacc a8a9eb a8a9eb"),
    oneMode("starlit", "Starlit Peach", "glass", "dark", "060B1E", "ffcead", "7d82d9", "c89dc1", "ED5B5A eb8b54 E9BB4F 92a593 a3bfd1 7d82d9 c89dc1"),
    oneMode("icedesk", "Ice Desk", "tiled", "dark", "16242d", "d6e2ee", "8bc9eb", "b4e4f6", "4d86b0 8bc9eb 6fa4c9 5e95bc b4e4f6 6fb8e3 8bc9eb"),
    oneMode("ember", "Obsidian Ember", "tiled", "dark", "121212", "bebebe", "e68e0d", "D35F5F", "D35F5F c63d3d b91c1c FFC107 bebebe e68e0d D35F5F"),
    oneMode("bog", "Bog Lantern", "oldschool", "dark", "222222", "c2c2b0", "c9a554", "78824b", "685742 8d6242 b36d43 5f875f c9a554 78824b bb7744"),
    oneMode("lastlight", "Last Light", "glass", "dark", "0c0b0c", "FAFCFB", "b59790", "87a9b0", "c38b7b - 6B5E73 87a9b0 a5a0b6 b59790 c4d8e2"),
    oneMode("mission", "Mission Control", "tiled", "dark", "1c1c1e", "f5f5f7", "ff9f0a", "a6dcef", "ff5f4a ff6a3d ffc27a ff9f0a a6dcef 8fb8de ff8fa3"),
    oneMode("fogbank", "Fogbank", "glass", "dark", "101315", "cacccc", "9fa5a9", "798186", "565d60 - d9dbdc 9fa5a9 707070 798186 aeaeae"),
    oneMode("void", "Void", "tiled", "dark", "000000", "ffffff", "8d8d8d", "cecece", "a4a4a4 b9b9b9 cecece b6b6b6 b0b0b0 8d8d8d 9b9b9b"),
    oneMode("inkpaper", "Ink & Paper", "oldschool", "light", "FFFCF0", "100F0F", "205EA6", "CE5D97", "D14D41 d0772b D0A215 879A39 3AA99F 205EA6 CE5D97"),
    oneMode("petal", "Dawn Petal", "soft", "light", "faf4ed", "575279", "56949f", "d7827e", "b4637a cf8057 ea9d34 286983 56949f 286983 907aa9"),
    oneMode("wildflower", "Wildflower", "soft", "light", "fafafa", "212121", "3264eb", "8a4ad7", "c900c4 026fde 026fde 4a2fd0 0c67de 3264eb 8a4ad7"),
    oneMode("gallery", "Gallery White", "oldschool", "light", "ffffff", "000000", "6e6e6e", "3a3a3a", "2a2a2a - 4a4a4a 3a3a3a 3e3e3e 1a1a1a 2e2e2e"),
)

fun themeById(id: String) = Themes.firstOrNull { it.id == id } ?: Themes[0]

/** A background drawn in the palette's colours, so it suits every theme, light and dark. */
class Backdrop(val id: String, val name: String)

val Backdrops = listOf(
    Backdrop("glow", "Glow"), Backdrop("mesh", "Mesh"), Backdrop("aurora", "Aurora"), Backdrop("waves", "Waves"),
    Backdrop("dunes", "Dunes"), Backdrop("peaks", "Peaks"), Backdrop("stars", "Stars"), Backdrop("rain", "Rain"),
    Backdrop("sunset", "Sunset"), Backdrop("horizon", "Horizon"), Backdrop("embers", "Embers"), Backdrop("fog", "Fog"),
    Backdrop("topo", "Contours"), Backdrop("bauhaus", "Bauhaus"), Backdrop("frost", "Frost"), Backdrop("grid", "Grid"), Backdrop("dots", "Dots"), Backdrop("city", "Night city"),
)

/**
 * Painted backgrounds (made with an image generator), one per full theme in its colours, in assets/paintings.
 * Their ids are "p:<theme id>" wherever a [Backdrops] id goes.
 */
val Paintings = listOf(
    "tokyonight" to "Rooftop city", "neonrain" to "Rain alley", "mainframe" to "Data hall", "arcade" to "Synth sunset", "fjord" to "Fjord",
    "moss" to "Mossy glen", "campfire" to "Campfire", "greatwave" to "Great wave", "jadealley" to "Lantern alley", "espresso" to "Night café",
    "starlit" to "Milky Way", "icedesk" to "Ice cave", "ember" to "Lava", "bog" to "Bog lantern", "lastlight" to "Mesa dusk",
    "mission" to "Orbit", "fogbank" to "Lighthouse", "void" to "Black hole", "marshmallow" to "Cloud hills", "inkpaper" to "Ink wash",
    "petal" to "Blossom pond", "wildflower" to "Meadow", "gallery" to "Gallery",
).map { (id, name) -> Backdrop("p:$id", name) }

fun isPainting(a: String) = a.startsWith("p:") && Paintings.any { it.id == a }
/** A drawn or painted background id. */
fun isArt(a: String?) = a != null && (Backdrops.any { it.id == a } || isPainting(a))
fun artName(a: String) = (Backdrops + Paintings).firstOrNull { it.id == a }?.name ?: ""

/** Decoded paintings: full size for the home screen, small for the pickers. */
object PaintingCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(30)
    fun peek(id: String, small: Boolean): ImageBitmap? = cache.get(id + if (small) "@s" else "")
    fun get(context: Context, id: String, small: Boolean): ImageBitmap? {
        val key = id + if (small) "@s" else ""
        cache.get(key)?.let { return it }
        return runCatching {
            context.assets.open("paintings/${id.removePrefix("p:")}.jpg").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (small) 8 else 1 }) }?.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }
}

/** A painting, decoded off the main thread; null until it's ready. */
@Composable
fun rememberPainting(id: String, small: Boolean): ImageBitmap? {
    val context = LocalContext.current
    return produceState(PaintingCache.peek(id, small), id, small) {
        value = withContext(Dispatchers.IO) { PaintingCache.get(context, id, small) }
    }.value
}

/** Each shape's own background, used until one is picked. */
fun shapeBackdrop(st: LookStyle) = when (st.motion) { "snappy" -> "dots"; "pulse" -> "horizon"; "float" -> "frost"; else -> if (st.id == "tiled") "grid" else "glow" }

/** The background each full theme comes with: its own painting, or the drawn one that fits its name and mood. */
fun themeArt(id: String): String? = "p:$id".takeIf { isPainting(it) } ?: ThemeBackdrops[id]

val ThemeBackdrops = mapOf(
    "marshmallow" to "mesh", "neonrain" to "rain", "tokyonight" to "city", "mainframe" to "grid", "arcade" to "sunset", "fjord" to "peaks", "moss" to "dunes",
    "campfire" to "embers", "greatwave" to "waves", "jadealley" to "aurora", "espresso" to "topo", "starlit" to "stars", "icedesk" to "frost",
    "ember" to "embers", "bog" to "fog", "lastlight" to "aurora", "mission" to "topo", "fogbank" to "fog", "void" to "stars",
    "inkpaper" to "bauhaus", "petal" to "waves", "wildflower" to "bauhaus", "gallery" to "dots",
)

/**
 * A style: everything about the look except its colours.
 * borderColor: outline, text, accent, light or a #hex. font: sans, mono or serif. motion: smooth (springs),
 * snappy (instant, no easing), pulse (glowing edges that breathe, a flicker on entry) or float (slow drift).
 * clock: thin, mono, glow or light. dark: auto, always or never. frameAll: the clock gets a card too (the apps widget stays clear unless its own style frames it).
 */
class LookStyle(
    val id: String, val name: String, val description: String,
    val corner: Float, val border: Float, val borderColor: String, val glow: Boolean, val font: String,
    val uppercase: Boolean, val motion: String, val gap: Float, val cardOpacity: Float, val frameAll: Boolean,
    val dark: String, val clock: String,
    /** How opaque the border is, 0 (invisible) to 1 (solid); below 0: its colour's own. */
    val borderAlpha: Float = -1f,
) {
    /** The same style with the sliders' border width, card opacity and border opacity (each below 0: the style's own). */
    fun tuned(b: Float, fill: Float, edgeAlpha: Float) = LookStyle(id, name, description, corner, if (b >= 0f) b else border, borderColor, glow, font, uppercase, motion, gap,
        if (fill >= 0f) fill else cardOpacity, frameAll, dark, clock, if (edgeAlpha >= 0f) edgeAlpha else borderAlpha)
    val family: FontFamily? get() = when (font) { "mono" -> FontFamily.Monospace; "serif" -> FontFamily.Serif; else -> null }
}

val Styles = listOf(
    LookStyle("soft", "Soft", "Rounded, calm and airy", 26f, 0f, "outline", false, "sans", false, "smooth", 5f, 1f, false, "auto", "thin"),
    LookStyle("oldschool", "Old school", "Square edges, solid borders, mono type, no easing", 0f, 1f, "text", false, "mono", true, "snappy", 3f, 1f, true, "auto", "mono"),
    LookStyle("neon", "Neon", "Dark, with glowing edges that breathe", 14f, 1f, "accent", true, "mono", false, "pulse", 6f, 0.7f, true, "always", "glow"),
    LookStyle("glass", "Glass", "Frosted cards, fine light edges, slow floating motion", 30f, 0.75f, "light", false, "sans", false, "float", 7f, 0.5f, true, "auto", "light"),
    LookStyle("tiled", "Tiles", "Tight tiles with sharp accent edges, like a tiling desktop", 6f, 1f, "accent", false, "mono", false, "smooth", 4f, 0.92f, true, "auto", "mono"),
)

/** What a theme.json may hold. */
private val ThemeKeys = setOf("name", "description", "style", "dark", "colors", "corner", "border", "borderColor", "glow", "font", "uppercase",
    "motion", "gap", "cardOpacity", "frameAll", "clock", "background")

/** What the launcher will ignore in a theme.json, in words (the `theme check` command makes the same checks). */
fun checkTheme(j: JSONObject, dir: File): List<String> = buildList {
    if (j.optString("name").isBlank()) add("name is missing: the folder's name is shown")
    j.keys().forEach { if (it !in ThemeKeys) add("unknown key \"$it\": ignored") }
    fun oneOf(k: String, vals: List<String>) { if (j.has(k) && j.optString(k) !in vals) add("$k \"${j.opt(k)}\" isn't one of ${vals.joinToString()}: the style's is used") }
    oneOf("style", Styles.map { it.id }); oneOf("dark", listOf("auto", "always", "never")); oneOf("font", listOf("sans", "mono", "serif"))
    oneOf("motion", listOf("smooth", "snappy", "pulse", "float")); oneOf("clock", listOf("thin", "mono", "glow", "light"))
    fun num(k: String, lo: Double, hi: Double) {
        if (!j.has(k)) return
        val v = j.optDouble(k)
        if (v.isNaN()) add("$k must be a number: the style's is used") else if (v < lo || v > hi) add("$k is $v, outside $lo to $hi: clamped")
    }
    num("corner", 0.0, 40.0); num("border", 0.0, 6.0); num("gap", 0.0, 16.0); num("cardOpacity", 0.0, 1.0)
    listOf("glow", "uppercase", "frameAll").forEach { k -> if (j.has(k) && j.opt(k) !is Boolean) add("$k must be true or false: the style's is used") }
    if (j.has("borderColor")) j.optString("borderColor").let { b ->
        if (b !in listOf("outline", "text", "accent", "glow", "light") && runCatching { android.graphics.Color.parseColor(b) }.isFailure) add("borderColor \"$b\" isn't outline, text, accent, glow, light or a #hex: the faint outline is used")
    }
    if (j.has("colors")) {
        val c = j.optJSONObject("colors")
        if (c == null) add("colors must be an object with light and dark: the user's palette is used")
        else {
            c.keys().forEach { m -> if (m != "light" && m != "dark") add("colors.$m is unknown: use colors.light and colors.dark") }
            for (m in listOf("light", "dark")) {
                val o = c.optJSONObject(m) ?: continue
                o.keys().forEach { k ->
                    if (k !in listOf("surface", "text", "accent", "glow") + ToneNames) add("colors.$m.$k is unknown: ignored")
                    else if (runCatching { android.graphics.Color.parseColor(o.optString(k)) }.isFailure) add("colors.$m.$k \"${o.opt(k)}\" isn't a #RRGGBB colour: ignored")
                }
            }
        }
    }
    when (val bg = j.opt("background")) {
        null -> {}
        is String -> if (bg !in listOf("theme", "plain", "wallpaper") && !isArt(bg)) add("background \"$bg\" isn't theme, plain, wallpaper, a painting (p:…) or one of ${Backdrops.joinToString { it.id }}: ignored")
        is JSONObject -> {
            val img = bg.optString("image")
            if (img.isNotBlank()) { val f = File(dir, img); if (!f.isFile || !f.canonicalPath.startsWith(dir.canonicalPath)) add("background.image: there's no $img in the theme's folder: ignored") }
            else if (bg.optString("kind") !in listOf("theme", "plain", "wallpaper")) add("background needs kind (theme, plain, wallpaper) or image: ignored")
            bg.optString("art").takeIf { it.isNotBlank() && !isArt(it) }?.let { add("background.art \"$it\" isn't a painting (${Paintings.joinToString { b -> b.id }}) or one of ${Backdrops.joinToString { b -> b.id }}: the shape's own is used") }
        }
        else -> add("background must be theme, plain, wallpaper, an art name or {\"image\": \"file\"}: ignored")
    }
}

/** A theme an agent made: /root/themes/<id>/theme.json, a style (based on one of the built-in ones) and maybe colours. */
class CustomTheme(val id: String, val dir: File, val json: JSONObject, val stamp: Long) {
    val problems: List<String> = runCatching { checkTheme(json, dir) }.getOrDefault(emptyList())
    val name get() = json.optString("name").ifBlank { id }
    val style: LookStyle get() {
        val base = Styles.firstOrNull { it.id == json.optString("style") } ?: Styles[0]
        fun f(k: String, d: Float) = json.optDouble(k).takeIf { !it.isNaN() }?.toFloat() ?: d
        fun s(k: String, d: String, vals: List<String>? = null) = json.optString(k).takeIf { it.isNotBlank() && (vals == null || it in vals) } ?: d
        fun b(k: String, d: Boolean) = (json.opt(k) as? Boolean) ?: d
        return LookStyle(
            "custom:$id", name, json.optString("description"),
            f("corner", base.corner).coerceIn(0f, 40f), f("border", base.border).coerceIn(0f, 6f), s("borderColor", base.borderColor), b("glow", base.glow),
            s("font", base.font, listOf("sans", "mono", "serif")), b("uppercase", base.uppercase), s("motion", base.motion, listOf("smooth", "snappy", "pulse", "float")),
            f("gap", base.gap).coerceIn(0f, 16f), f("cardOpacity", base.cardOpacity).coerceIn(0f, 1f), b("frameAll", base.frameAll),
            s("dark", base.dark, listOf("auto", "always", "never")), s("clock", base.clock, listOf("thin", "mono", "glow", "light")),
        )
    }
    /** Its own colours, if it has them; tones it leaves out are made from its accent. */
    fun palette(): ThemeDef? {
        val c = json.optJSONObject("colors") ?: return null
        val l = c.optJSONObject("light"); val d = c.optJSONObject("dark") ?: l; val lt = l ?: d ?: return null
        fun col(o: JSONObject?, k: String): Color? = o?.optString(k)?.takeIf { it.isNotBlank() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        val fb = Themes[0]
        val ls = col(lt, "surface") ?: fb.surface; val ds = col(d, "surface") ?: fb.surfaceDark
        val la = col(lt, "accent") ?: fb.accent; val da = col(d, "accent") ?: fb.accentDark
        fun tones(o: JSONObject?, accent: Color, surface: Color): List<Color> {
            val derived = deriveTones(accent, surface)
            return ToneNames.mapIndexed { i, n -> col(o, n) ?: derived[i] }
        }
        return ThemeDef("custom:$id", name,
            ls, col(lt, "text") ?: fb.ink, la, col(lt, "glow") ?: fb.glow,
            ds, col(d, "text") ?: fb.inkDark, da, col(d, "glow") ?: fb.glowDark,
            tones(lt, la, ls), tones(d, da, ds))
    }
}

/** A full Material colour scheme from a palette. */
fun schemeFor(t: ThemeDef, dark: Boolean): ColorScheme {
    val s = if (dark) t.surfaceDark else t.surface
    val ink = if (dark) t.inkDark else t.ink
    val acc = if (dark) t.accentDark else t.accent
    val glow = if (dark) t.glowDark else t.glow
    fun on(c: Color) = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = acc, onPrimary = on(acc),
        primaryContainer = lerp(s, acc, if (dark) 0.32f else 0.16f), onPrimaryContainer = ink,
        inversePrimary = lerp(acc, ink, 0.3f),
        secondary = lerp(acc, ink, 0.45f), onSecondary = on(lerp(acc, ink, 0.45f)),
        secondaryContainer = lerp(s, acc, if (dark) 0.2f else 0.1f), onSecondaryContainer = ink,
        tertiary = glow, onTertiary = on(glow), tertiaryContainer = lerp(s, glow, 0.2f), onTertiaryContainer = ink,
        background = s, onBackground = ink, surface = s, onSurface = ink,
        surfaceVariant = lerp(s, ink, 0.09f), onSurfaceVariant = lerp(ink, s, 0.32f),
        surfaceTint = acc, surfaceBright = lerp(s, ink, if (dark) 0.14f else 0f), surfaceDim = lerp(s, ink, if (dark) 0f else 0.1f),
        surfaceContainerLowest = lerp(s, ink, 0.015f), surfaceContainerLow = lerp(s, ink, 0.035f),
        surfaceContainer = lerp(s, ink, 0.06f), surfaceContainerHigh = lerp(s, ink, 0.09f), surfaceContainerHighest = lerp(s, ink, 0.13f),
        outline = lerp(ink, s, 0.55f), outlineVariant = lerp(ink, s, 0.8f),
        inverseSurface = ink, inverseOnSurface = s,
    )
}

/** The chosen look. Read before the first screen (main thread); changed from Settings, the home menu or by an agent's theme. */
object Look {
    /** soft, oldschool, neon, glass, tiled, or custom:<id>. */
    var style by mutableStateOf("soft")
    /** The palette for the built-in styles. */
    var theme by mutableStateOf("indigo")
    /** theme (drawn art, see [art]), plain, wallpaper or image. */
    var background by mutableStateOf("theme")
    /** The drawn background (a [Backdrops] id) when background is "theme"; blank: the shape's own. */
    var art by mutableStateOf("")
    /** The border width in dp chosen with the slider; below 0: the style's own. */
    var edge by mutableStateOf(-1f)
    /** The widgets' background opacity chosen with its slider, 0 (clear) to 1 (solid); below 0: the style's own. */
    var fill by mutableStateOf(-1f)
    /** The borders' opacity chosen with its slider, 0 to 1; below 0: the style's own. */
    var edgeAlpha by mutableStateOf(-1f)
    /** system, light or dark (a style may insist on dark). */
    var mode by mutableStateOf("system")
    /** The background photo's file, and the photo itself, ready to draw. */
    var imagePath by mutableStateOf("")
    var image by mutableStateOf<ImageBitmap?>(null)
    val custom = mutableStateListOf<CustomTheme>()
    /** Theme folders whose theme.json can't be read at all, with why. */
    var broken by mutableStateOf<Map<String, String>>(emptyMap())
    /** A theme an agent is making or changing, and its file's time when last applied: chosen at every save. */
    var pending: Pair<String, Long>? = null
    private var prefs: SharedPreferences? = null
    private var lastErrors = ""

    fun load(context: Context) {
        val p = context.getSharedPreferences("look", 0).also { prefs = it }
        // The first start: Neon Rain, dark, with its painted rain alley.
        if (!p.contains("style") && !p.contains("theme"))
            p.edit().putString("style", "neon").putString("theme", "neonrain").putString("mode", "dark").putString("background", "theme")
                .putString("art", themeArt("neonrain") ?: "").commit()
        style = p.getString("style", "soft") ?: "soft"
        theme = p.getString("theme", "indigo") ?: "indigo"
        background = p.getString("background", "theme") ?: "theme"
        art = p.getString("art", "") ?: ""
        mode = p.getString("mode", "system") ?: "system"
        edge = p.getFloat("edge", -1f)
        fill = p.getFloat("fill", -1f)
        edgeAlpha = p.getFloat("edgeAlpha", -1f)
        imagePath = p.getString("image", "") ?: ""
        image = decode(imagePath)
        runCatching { scanCustom(context).let { (found, bad) -> custom += found; broken = bad } }
    }

    /** The style in use with the sliders' border width and opacities, the one everything draws with. */
    val shown: LookStyle get() = current.let { if (edge < 0f && fill < 0f && edgeAlpha < 0f) it else it.tuned(edge, fill, edgeAlpha) }
    fun chooseEdge(v: Float) { edge = v; prefs?.edit()?.putFloat("edge", v)?.apply() }
    fun chooseFill(v: Float) { fill = v; prefs?.edit()?.putFloat("fill", v)?.apply() }
    fun chooseEdgeAlpha(v: Float) { edgeAlpha = v; prefs?.edit()?.putFloat("edgeAlpha", v)?.apply() }
    val current: LookStyle get() = if (style.startsWith("custom:")) custom.firstOrNull { "custom:" + it.id == style }?.style ?: Styles[0] else Styles.firstOrNull { it.id == style } ?: Styles[0]
    val palette: ThemeDef get() = (if (style.startsWith("custom:")) custom.firstOrNull { "custom:" + it.id == style }?.palette() else null) ?: themeById(theme)
    /** The agent's theme in use, if one is. */
    val currentCustom: CustomTheme? get() = if (style.startsWith("custom:")) custom.firstOrNull { "custom:" + it.id == style } else null
    /** The drawn background in use. */
    val artNow: String get() = art.takeIf { isArt(it) } ?: shapeBackdrop(current)
    /** Text straight on the home background wants a shadow: over the wallpaper, a photo or a painting. */
    val photoLike: Boolean get() = background == "wallpaper" || background == "image" || (background == "theme" && isPainting(artNow))

    fun set(context: Context, style: String = this.style, theme: String = this.theme, background: String = this.background, mode: String = this.mode, art: String = this.art) {
        this.style = style; this.theme = theme; this.background = background; this.mode = mode; this.art = art
        save(); writeThemeFile(context); writeCurrent(context)
    }

    private fun save() {
        prefs?.edit()?.putString("style", style)?.putString("theme", theme)?.putString("background", background)?.putString("mode", mode)?.putString("image", imagePath)?.putString("art", art)?.apply()
    }

    /** An agent's theme: its style, and its background and photo when it names them. */
    fun choose(context: Context, t: CustomTheme) {
        when (val bg = t.json.opt("background")) {
            is String -> if (bg in setOf("theme", "plain", "wallpaper")) background = bg else if (isArt(bg)) { background = "theme"; art = bg }
            is JSONObject -> {
                val file = bg.optString("image").takeIf { it.isNotBlank() }?.let { File(t.dir, it) }?.takeIf { it.isFile && it.canonicalPath.startsWith(t.dir.canonicalPath) }
                if (file != null) { imagePath = file.path; image = decode(file.path); background = "image" }
                else bg.optString("kind").takeIf { it in setOf("theme", "plain", "wallpaper") }?.let { background = it }
                bg.optString("art").takeIf { isArt(it) }?.let { art = it }
            }
        }
        when (t.json.optString("dark")) { "always" -> mode = "dark"; "never" -> mode = "light" }
        set(context, style = "custom:" + t.id)
    }

    /** A full theme: its shape, its colours and its own background, in the mode it was designed in. */
    fun choosePreset(context: Context, p: ThemeDef) {
        set(context, style = p.style.ifBlank { style }, theme = p.id, mode = when (p.native) { "dark" -> "dark"; "light" -> "light"; else -> mode },
            background = "theme", art = themeArt(p.id) ?: "")
    }

    /** Moves an agent's theme to /root/themes/.trash (falls back to the default look if it was in use). */
    fun remove(context: Context, t: CustomTheme) {
        runCatching {
            val trash = File(t.dir.parentFile, ".trash").apply { mkdirs() }
            t.dir.renameTo(File(trash, "${t.id}-${System.currentTimeMillis() / 1000}"))
        }
        custom.remove(t)
        if (style == "custom:" + t.id) set(context, style = "soft")
    }

    /** A photo from the gallery or files, made screen-sized and kept in the app's own files. */
    suspend fun setImage(context: Context, uri: Uri): Boolean {
        val target = File(context.filesDir, "look/background-${System.currentTimeMillis() / 1000}.jpg")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
                val bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
                target.parentFile?.listFiles()?.forEach { it.delete() }
                target.parentFile?.mkdirs()
                target.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                true
            }.getOrDefault(false)
        }
        if (!ok) return false
        imagePath = target.path; image = decode(target.path)
        set(context, background = "image")
        return true
    }

    private fun decode(path: String): ImageBitmap? = path.takeIf { it.isNotBlank() }?.let { p ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
            BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }

    /** The themes on disk (unchanged files are kept as they are), and the folders whose theme.json can't be read. */
    private fun scanCustom(context: Context): Pair<List<CustomTheme>, Map<String, String>> {
        val linux = Linux(context)
        if (!linux.hasBasics) return emptyList<CustomTheme>() to emptyMap()
        val bad = LinkedHashMap<String, String>()
        val found = linux.hostFile(THEMES_DIR).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { d ->
            val f = File(d, "theme.json").takeIf { it.isFile } ?: return@mapNotNull null
            custom.firstOrNull { it.id == d.name && it.stamp == f.lastModified() }
                ?: runCatching { CustomTheme(d.name, d, JSONObject(f.readText()), f.lastModified()) }
                    .onFailure { bad[d.name] = "theme.json isn't valid JSON (${it.message?.take(160)}): the theme is left out until it's fixed" }.getOrNull()
        }.sortedBy { it.name.lowercase() }
        return found to bad
    }

    /**
     * Reads the agents' themes again (cheap: only changed files are parsed), like the launcher's own widgets:
     * the theme in use shows every save at once, a theme being made is chosen when it's saved, `theme use <id>`
     * switches, and errors.txt says what was ignored and why.
     */
    suspend fun reloadCustom(context: Context) {
        val (found, bad) = withContext(Dispatchers.IO) { runCatching { scanCustom(context) }.getOrDefault(emptyList<CustomTheme>() to emptyMap()) }
        val inUse = currentCustom
        if (found.map { it.id + it.stamp } != custom.map { it.id + it.stamp }) { custom.clear(); custom.addAll(found) }
        if (bad != broken) broken = bad
        withContext(Dispatchers.IO) { writeErrors(context, found, bad) }
        val use = withContext(Dispatchers.IO) {
            runCatching { Linux(context).hostFile("$THEMES_DIR/.use").takeIf { it.isFile }?.let { f -> f.readText().trim().also { f.delete() } } }.getOrNull()
        }
        if (!use.isNullOrBlank()) {
            found.firstOrNull { it.id == use }?.let { choose(context, it) } ?: Styles.firstOrNull { it.id == use }?.let { set(context, style = it.id) }
            return
        }
        if (inUse != null) found.firstOrNull { it.id == inUse.id && it.stamp != inUse.stamp }?.let { choose(context, it); return }
        val (id, since) = pending ?: return
        val t = found.firstOrNull { it.id == id && it.stamp > since } ?: return
        pending = id to t.stamp
        choose(context, t)
    }

    /** /root/themes/errors.txt: what was ignored in each theme, and why. Written only when it changes. */
    private fun writeErrors(context: Context, found: List<CustomTheme>, bad: Map<String, String>) {
        val text = buildString {
            appendLine("# What Hatchlybox ignored in the themes here, and why. Rewritten each time a theme is saved.")
            appendLine("# Check one yourself with: theme check <id>")
            appendLine()
            bad.forEach { (id, why) -> appendLine("$id: $why") }
            found.filter { it.problems.isNotEmpty() }.forEach { t -> appendLine("${t.id} (${t.name}):"); t.problems.forEach { appendLine("  - $it") } }
            if (bad.isEmpty() && found.none { it.problems.isNotEmpty() }) appendLine("No problems: ${found.size} theme(s) loaded as written.")
        }
        if (text == lastErrors) return
        runCatching { Linux(context).hostFile("$THEMES_DIR/errors.txt").apply { parentFile?.mkdirs() }.writeText(text); lastErrors = text }
    }

    /** THEMES.md, the `theme` command, the built-in styles and palettes, and the current look, for the agents: /root/themes. */
    fun installDocs(context: Context) = runCatching {
        val linux = Linux(context)
        if (!linux.hasBasics) return@runCatching
        val dir = linux.hostFile(THEMES_DIR).apply { mkdirs() }
        File(dir, "THEMES.md").writeBytes(context.assets.open("themes/THEMES.md").use { it.readBytes() })
        val pointer = "# Hatchlybox themes\n\nEach folder here is a launcher theme. Read THEMES.md (in this folder) before making or changing one, and check your work with `theme check`.\n"
        File(dir, "CLAUDE.md").writeText(pointer); File(dir, "AGENTS.md").writeText(pointer)
        val bin = linux.hostFile("/root/.local/bin/theme")
        bin.parentFile?.mkdirs()
        bin.writeBytes(context.assets.open("themes/theme.sh").use { it.readBytes() }); bin.setExecutable(true, false)
        File(dir, "STYLES.json").writeText(org.json.JSONArray(Styles.map { styleJson(it).put("id", it.id) }).toString(2))
        File(dir, "PALETTES.json").writeText(org.json.JSONArray(Themes.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("style", p.style.ifBlank { null }).put("native", p.native.ifBlank { null }).put("colors", colorsJson(p))
        }).toString(2))
        writeCurrent(context)
    }

    private fun writeCurrent(context: Context) = runCatching {
        val linux = Linux(context)
        if (!linux.hasBasics) return@runCatching
        File(linux.hostFile(THEMES_DIR).apply { mkdirs() }, "CURRENT.json").writeText(describe().put("id", style).toString(2))
    }

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun styleJson(s: LookStyle) = JSONObject().put("name", s.name).put("description", s.description)
        .put("dark", s.dark).put("corner", s.corner.toDouble()).put("border", s.border.toDouble()).put("borderColor", s.borderColor).put("glow", s.glow)
        .put("font", s.font).put("uppercase", s.uppercase).put("motion", s.motion).put("gap", s.gap.toDouble()).put("cardOpacity", s.cardOpacity.toDouble())
        .put("frameAll", s.frameAll).put("clock", s.clock)

    private fun colorsJson(p: ThemeDef): JSONObject {
        fun mode(dark: Boolean) = JSONObject().apply {
            put("surface", hex(if (dark) p.surfaceDark else p.surface)); put("text", hex(if (dark) p.inkDark else p.ink))
            put("accent", hex(if (dark) p.accentDark else p.accent)); put("glow", hex(if (dark) p.glowDark else p.glow))
            p.tones(dark).forEachIndexed { i, c -> put(ToneNames[i], hex(c)) }
        }
        return JSONObject().put("light", mode(false)).put("dark", mode(true))
    }

    /** The look now, as a theme.json (what an agent starts from when it changes a built-in style). */
    fun describe(): JSONObject {
        val s = current
        val base = currentCustom?.json?.optString("style")?.takeIf { b -> Styles.any { it.id == b } } ?: if (s.id.startsWith("custom:")) "soft" else s.id
        return styleJson(s).put("style", base).put("colors", colorsJson(palette))
            .put("background", JSONObject().put("kind", if (background == "image") "theme" else background).put("art", artNow))
    }

    /** /root/widgets/THEME.json: the look in words and colours, for the agents that make widgets. */
    fun writeThemeFile(context: Context) = runCatching {
        val linux = Linux(context)
        if (!linux.hasBasics) return@runCatching
        val t = palette; val s = current
        fun colours(dark: Boolean) = schemeFor(t, dark).let { c ->
            JSONObject().put("primary", hex(c.primary)).put("secondary", hex(c.secondary)).put("tertiary", hex(c.tertiary))
                .put("text", hex(c.onSurface)).put("muted", hex(c.onSurfaceVariant)).put("surface", hex(c.surface))
                .put("container", hex(c.primaryContainer)).put("card", hex(c.surfaceContainer)).put("outline", hex(c.outline))
                .apply { t.tones(dark).forEachIndexed { i, col -> put(ToneNames[i], hex(col)) } }
        }
        val json = JSONObject().put("palette", t.name).put("style", s.name).put("styleNotes", s.description)
            .put("corners", s.corner.toDouble()).put("font", s.font).put("uppercase", s.uppercase).put("background", if (background == "theme") "art:$artNow" else background).put("mode", mode)
            .put("light", colours(false)).put("dark", colours(true))
            .put("advice", "Use the named colours (primary, secondary, tertiary, text, muted, container, card, outline, and the tones red, orange, yellow, green, cyan, blue, magenta) in widget.json and style.json: they follow the theme, light and dark. The card around a widget (corners, border, glow) is drawn in the current style for you. Use a #hex only for an accent that sits well with these colours.")
        File(linux.hostFile(WIDGETS_DIR).apply { mkdirs() }, "THEME.json").writeText(json.toString(2))
    }
}

const val THEMES_DIR = "/root/themes"

/** The style in use, for every widget and surface that draws itself. */
val LocalLook = staticCompositionLocalOf { Styles[0] }

@Composable
fun isDarkLook(): Boolean = when {
    Look.current.dark == "always" -> true
    Look.current.dark == "never" -> false
    Look.mode == "light" -> false
    Look.mode == "dark" -> true
    else -> androidx.compose.foundation.isSystemInDarkTheme()
}

/** The style's border colour. */
@Composable
fun lookBorder(st: LookStyle = LocalLook.current): Color {
    val c = MaterialTheme.colorScheme
    val base = when (st.borderColor) {
        "text" -> c.onSurface; "accent" -> c.primary; "outline" -> c.outlineVariant; "glow" -> c.tertiary
        "light" -> if (c.surface.luminance() < 0.5f) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.75f)
        else -> runCatching { Color(android.graphics.Color.parseColor(st.borderColor)) }.getOrDefault(c.outlineVariant)
    }
    return if (st.borderAlpha >= 0f) base.copy(alpha = st.borderAlpha.coerceIn(0f, 1f)) else base
}

/**
 * A card in the current style: its corners, a fill at the style's opacity, its border, and for glowing styles
 * a soft halo that breathes. corner overrides the style's (a widget's own style.json may).
 */
@Composable
fun Modifier.lookFrame(fill: Color?, corner: Dp? = null): Modifier {
    val st = LocalLook.current
    val r = corner ?: st.corner.dp
    val shape = RoundedCornerShape(r)
    val bc = lookBorder(st)
    val breathe = if (st.motion == "pulse") rememberInfiniteTransition(label = "breathe").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow") else null
    var m = this
    if (st.glow) m = m.drawBehind {
        val a = breathe?.value ?: 1f
        val rad = r.toPx()
        for (i in 1..3) {
            val spread = i * 4.dp.toPx()
            drawRoundRect(bc.copy(alpha = 0.10f * a / i * (if (st.borderAlpha >= 0f) st.borderAlpha else 1f)), Offset(-spread / 2, -spread / 2), Size(size.width + spread, size.height + spread), CornerRadius(rad + spread / 2), style = Stroke(spread))
        }
    }
    m = m.clip(shape)
    if (fill != null && st.cardOpacity > 0f) m = m.background(fill.copy(alpha = fill.alpha * st.cardOpacity))
    if (st.border > 0f && bc.alpha > 0f) m = m.drawWithContent {
        drawContent()
        val w = st.border.dp.toPx()
        drawRoundRect(bc.copy(alpha = bc.alpha * (breathe?.value ?: 1f)), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius((r.toPx() - w / 2).coerceAtLeast(0f)), style = Stroke(w))
    }
    return m
}

/** The home background for the "theme" choice: a drawn [Backdrops] art in the palette's colours (tones: its seven). */
fun Modifier.themeBackdrop(scheme: ColorScheme, art: String, tones: List<Color> = emptyList()): Modifier = drawBehind { drawBackdrop(art, scheme, tones) }

/**
 * Any background art: a painting (cropped to fill, with a soft shade of the theme's surface at the top and bottom
 * so the clock and the create box stay readable), or a drawn one. Until a painting is decoded, its surface shows.
 */
@Composable
fun Modifier.artBackdrop(scheme: ColorScheme, art: String, tones: List<Color> = emptyList(), small: Boolean = false): Modifier {
    if (!isPainting(art)) return themeBackdrop(scheme, art, tones)
    val img = rememberPainting(art, small) ?: return background(scheme.surface)
    val shade = scheme.surface
    return drawBehind {
        val scale = maxOf(size.width / img.width, size.height / img.height)
        val w = (size.width / scale).toInt().coerceIn(1, img.width); val h = (size.height / scale).toInt().coerceIn(1, img.height)
        drawImage(img, IntOffset((img.width - w) / 2, (img.height - h) / 2), IntSize(w, h), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        if (!small) drawRect(Brush.verticalGradient(0f to shade.copy(alpha = 0.35f), 0.16f to Color.Transparent, 0.62f to Color.Transparent, 1f to shade.copy(alpha = 0.5f)))
    }
}

/** A small fixed sequence of numbers in 0..1, so the drawn backgrounds look the same at every draw. */
private class Seeded(seed: Int) {
    private var x = seed * 2654435761L + 1
    fun next(): Float { x = (x * 6364136223846793005L + 1442695040888963407L); return ((x ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(art: String, scheme: ColorScheme, tones: List<Color>) {
    val s = scheme.surface; val ink = scheme.onSurface; val p = scheme.primary; val g = scheme.tertiary
    fun tone(i: Int, d: Color) = tones.getOrNull(i) ?: d
    val dark = s.luminance() < 0.4f
    val w = size.width; val h = size.height
    fun glowAt(c: Color, a: Float, x: Float, y: Float, r: Float) =
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
    /** A filled band whose top edge is a sum of two waves. */
    fun band(top: Float, amp: Float, f1: Float, ph: Float, brush: Brush, bottom: Float = h) {
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(0f, bottom)
        var x = 0f; val step = w / 48f
        while (x <= w + step) {
            val t = x / w
            path.lineTo(x, top + amp * kotlin.math.sin(t * f1 * 6.283f + ph) + amp * 0.35f * kotlin.math.sin(t * f1 * 2.7f * 6.283f + ph * 1.7f))
            x += step
        }
        path.lineTo(w, bottom); path.close()
        drawPath(path, brush)
    }
    when (art) {
        "dots" -> {
            // A flat field with a faint dot grid.
            drawRect(s)
            val step = 22.dp.toPx(); val dot = lerp(s, ink, 0.12f)
            var y = step / 2; while (y < h) { var x = step / 2; while (x < w) { drawCircle(dot, 1.dp.toPx(), Offset(x, y)); x += step }; y += step }
        }
        "horizon" -> {
            // Near black, a horizon glow and a faint perspective grid.
            drawRect(s)
            drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to p.copy(alpha = 0.18f)))
            val line = g.copy(alpha = 0.10f); val horizon = h * 0.62f
            for (i in 0..9) { val t = i / 9f; val y = horizon + (h - horizon) * t * t; drawLine(line, Offset(0f, y), Offset(w, y), 1.dp.toPx()) }
            for (i in -6..6) { val x = w / 2 + i * w / 6; drawLine(line, Offset(w / 2 + i * 12.dp.toPx(), horizon), Offset(x, h), 1.dp.toPx()) }
        }
        "frost" -> {
            // Big soft colour fields for frosted cards to sit on.
            drawRect(s)
            glowAt(p, 0.35f, w * 0.1f, h * 0.2f, w * 0.8f)
            glowAt(g, 0.3f, w * 0.95f, h * 0.65f, w * 0.9f)
            glowAt(tone(6, g), 0.22f, w * 0.3f, h * 0.95f, w * 0.7f)
        }
        "mesh" -> {
            // A mesh gradient: four colours melting into each other.
            drawRect(s)
            glowAt(p, 0.42f, 0f, 0f, w * 1.1f)
            glowAt(tone(6, g), 0.34f, w, h * 0.35f, w * 1.0f)
            glowAt(tone(4, p), 0.32f, w * 0.1f, h * 0.75f, w * 1.0f)
            glowAt(g, 0.36f, w * 0.85f, h, w * 1.1f)
            drawRect(Brush.verticalGradient(listOf(s.copy(alpha = 0.15f), Color.Transparent, s.copy(alpha = 0.2f))))
        }
        "aurora" -> {
            // Curtains of light across a dark sky, over a still horizon.
            drawRect(Brush.verticalGradient(listOf(lerp(s, Color.Black, if (dark) 0.35f else 0f), s, lerp(s, p, 0.08f))))
            val cs = listOf(tone(3, p), tone(4, g), tone(6, g))
            cs.forEachIndexed { i, c ->
                val top = h * (0.14f + i * 0.09f); val depth = h * 0.32f
                band(top, h * 0.035f, 0.9f + i * 0.35f, i * 1.9f,
                    Brush.verticalGradient(0f to c.copy(alpha = 0f), 0.08f to c.copy(alpha = if (dark) 0.42f else 0.3f), 1f to Color.Transparent, startY = top - h * 0.05f, endY = top + depth),
                    bottom = top + depth)
            }
            band(h * 0.82f, h * 0.012f, 1.3f, 0.4f, androidx.compose.ui.graphics.SolidColor(lerp(s, ink, 0.07f)))
        }
        "waves" -> {
            // Layered waves under a pale sun.
            drawRect(Brush.verticalGradient(listOf(s, lerp(s, p, 0.1f))))
            glowAt(g, 0.28f, w * 0.78f, h * 0.2f, w * 0.34f)
            drawCircle(lerp(s, g, 0.32f), w * 0.11f, Offset(w * 0.78f, h * 0.2f))
            for (i in 0..4) {
                val c = lerp(s, if (i % 2 == 0) p else tone(4, p), 0.1f + i * 0.07f)
                band(h * (0.52f + i * 0.09f), h * 0.018f, 1.1f + i * 0.4f, i * 1.3f, androidx.compose.ui.graphics.SolidColor(c))
            }
        }
        "dunes" -> {
            // Soft hills fading toward the horizon, under a low sun.
            drawRect(Brush.verticalGradient(listOf(lerp(s, g, 0.16f), s, s)))
            drawCircle(lerp(s, tone(2, g), 0.4f), w * 0.13f, Offset(w * 0.3f, h * 0.4f))
            glowAt(tone(2, g), 0.25f, w * 0.3f, h * 0.4f, w * 0.45f)
            for (i in 0..3) band(h * (0.48f + i * 0.11f), h * 0.04f, 0.45f + i * 0.2f, 2f + i * 1.1f, androidx.compose.ui.graphics.SolidColor(lerp(s, p, 0.1f + i * 0.08f)))
        }
        "peaks" -> {
            // Mountain ranges in layers, nearer ones darker, mist between them.
            drawRect(Brush.verticalGradient(listOf(lerp(s, g, 0.14f), s)))
            val r = Seeded(11)
            for (layer in 0..2) {
                val path = androidx.compose.ui.graphics.Path(); val base = h * (0.5f + layer * 0.12f)
                path.moveTo(0f, h); path.lineTo(0f, base)
                var x = 0f
                while (x < w) {
                    val peak = base - h * (0.06f + r.next() * 0.12f) * (1f - layer * 0.2f)
                    val step = w * (0.12f + r.next() * 0.14f)
                    path.lineTo(x + step * 0.5f, peak); path.lineTo(x + step, base - h * r.next() * 0.03f); x += step
                }
                path.lineTo(w, h); path.close()
                drawPath(path, lerp(s, p, 0.12f + layer * 0.1f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, s.copy(alpha = 0.35f)), startY = base - h * 0.05f, endY = base + h * 0.06f), Offset(0f, base - h * 0.05f), Size(w, h * 0.11f))
            }
        }
        "stars" -> {
            // A night sky: a faint nebula and stars, a few with a glint.
            drawRect(Brush.verticalGradient(listOf(lerp(s, Color.Black, if (dark) 0.3f else 0f), lerp(s, p, 0.12f))))
            glowAt(g, 0.16f, w * 0.7f, h * 0.3f, w * 0.7f)
            glowAt(tone(6, p), 0.12f, w * 0.2f, h * 0.65f, w * 0.6f)
            val r = Seeded(5); val star = if (dark) ink else lerp(ink, p, 0.4f)
            repeat(150) {
                val x = r.next() * w; val y = r.next() * h; val big = r.next()
                drawCircle(lerp(star, tone((r.next() * 7).toInt(), p), 0.3f).copy(alpha = 0.25f + big * 0.6f), (0.5f + big * big * 1.3f).dp.toPx(), Offset(x, y))
            }
            repeat(4) {
                val x = r.next() * w; val y = r.next() * h * 0.7f; val l = 6.dp.toPx()
                drawLine(star.copy(alpha = 0.6f), Offset(x - l, y), Offset(x + l, y), 0.8.dp.toPx()); drawLine(star.copy(alpha = 0.6f), Offset(x, y - l), Offset(x, y + l), 0.8.dp.toPx())
                drawCircle(star, 1.6.dp.toPx(), Offset(x, y))
            }
        }
        "rain" -> {
            // A rainy night street: blurred city lights, slanting rain, a wet glow below.
            drawRect(Brush.verticalGradient(listOf(lerp(s, Color.Black, if (dark) 0.25f else 0f), s)))
            val r = Seeded(3)
            repeat(12) { glowAt(tone((r.next() * 7).toInt(), p), 0.12f + r.next() * 0.12f, r.next() * w, h * (0.15f + r.next() * 0.5f), (20f + r.next() * 50f).dp.toPx()) }
            drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to p.copy(alpha = 0.16f)))
            repeat(110) {
                val x = r.next() * w * 1.2f; val y = r.next() * h; val l = (10f + r.next() * 30f).dp.toPx()
                drawLine((if (r.next() > 0.7f) p else lerp(ink, tone(4, p), 0.5f)).copy(alpha = 0.10f + r.next() * 0.22f), Offset(x, y), Offset(x - l * 0.25f, y + l), 1.dp.toPx())
            }
        }
        "city" -> {
            // A city at night: a glowing sky, rows of towers with lit windows, their lights mirrored in the water.
            val water = h * 0.8f
            drawRect(Brush.verticalGradient(0f to lerp(s, Color.Black, if (dark) 0.35f else 0f), 0.75f to lerp(s, tone(6, g), 0.16f), 1f to s))
            glowAt(tone(6, g), 0.22f, w * 0.3f, water, w * 0.9f)
            glowAt(p, 0.16f, w * 0.85f, water * 0.9f, w * 0.6f)
            drawCircle(lerp(ink, tone(2, g), 0.3f).copy(alpha = 0.7f), 14.dp.toPx(), Offset(w * 0.78f, h * 0.14f))
            glowAt(ink, 0.12f, w * 0.78f, h * 0.14f, 60.dp.toPx())
            val r = Seeded(21)
            repeat(40) { drawCircle(ink.copy(alpha = 0.15f + r.next() * 0.35f), (0.5f + r.next()).dp.toPx(), Offset(r.next() * w, r.next() * h * 0.4f)) }
            for (layer in 0..1) {
                val body = lerp(s, Color.Black, if (dark) 0.45f - layer * 0.2f else 0.1f + layer * 0.08f).let { if (layer == 0) lerp(it, tone(6, g), 0.12f) else it }
                var x = -r.next() * 20.dp.toPx()
                while (x < w) {
                    val bw = (22f + r.next() * 34f).dp.toPx(); val bh = h * (if (layer == 0) 0.22f + r.next() * 0.3f else 0.1f + r.next() * 0.2f)
                    val top = water - bh
                    drawRect(body, Offset(x, top), Size(bw - 2.dp.toPx(), bh))
                    if (layer == 0 && r.next() > 0.7f) drawLine(tone(0, p).copy(alpha = 0.8f), Offset(x + bw / 2, top), Offset(x + bw / 2, top - 14.dp.toPx()), 1.5.dp.toPx())
                    val win = 3.dp.toPx(); var wy = top + 5.dp.toPx()
                    while (wy < water - 4.dp.toPx()) {
                        var wx = x + 4.dp.toPx()
                        while (wx < x + bw - 6.dp.toPx()) {
                            if (r.next() > 0.72f) drawRect((if (r.next() > 0.6f) tone(4, p) else tone(2, g)).copy(alpha = if (layer == 0) 0.75f else 0.4f), Offset(wx, wy), Size(win, win))
                            wx += win * 2.2f
                        }
                        wy += win * 2.4f
                    }
                    x += bw
                }
            }
            drawRect(lerp(s, Color.Black, if (dark) 0.3f else 0.05f), Offset(0f, water), Size(w, h - water))
            repeat(60) {
                val y = water + r.next() * (h - water); val x = r.next() * w; val l = (8f + r.next() * 40f).dp.toPx()
                drawLine((if (r.next() > 0.5f) tone(4, p) else tone(6, g)).copy(alpha = 0.1f + 0.25f * (1f - (y - water) / (h - water))), Offset(x, y), Offset(x + l, y), 1.5.dp.toPx())
            }
        }
        "sunset" -> {
            // A striped sun setting over a grid: the eighties arcade.
            val horizon = h * 0.62f
            drawRect(Brush.verticalGradient(0f to s, 0.62f to lerp(s, tone(1, g), 0.28f), 0.62f to lerp(s, Color.Black, if (dark) 0.2f else 0.04f), 1f to s))
            val cx = w / 2; val rr = w * 0.3f; val cy = horizon - rr * 0.35f
            glowAt(tone(6, g), 0.25f, cx, cy, rr * 1.8f)
            drawCircle(Brush.verticalGradient(listOf(tone(2, g), tone(6, p)), startY = cy - rr, endY = cy + rr), rr, Offset(cx, cy))
            var y = cy + rr * 0.1f; var gap = 2.dp.toPx()
            while (y < cy + rr) { drawRect(lerp(s, tone(1, g), 0.28f), Offset(cx - rr, y), Size(rr * 2, gap)); y += gap * 3f; gap *= 1.35f }
            drawRect(lerp(s, Color.Black, if (dark) 0.2f else 0.04f), Offset(0f, horizon), Size(w, h - horizon))
            val line = p.copy(alpha = 0.3f)
            for (i in 0..9) { val t = i / 9f; val yy = horizon + (h - horizon) * t * t; drawLine(line, Offset(0f, yy), Offset(w, yy), 1.dp.toPx()) }
            for (i in -8..8) drawLine(line, Offset(cx + i * 10.dp.toPx(), horizon), Offset(cx + i * w / 5, h), 1.dp.toPx())
        }
        "embers" -> {
            // Sparks rising from a fire's glow.
            drawRect(Brush.verticalGradient(listOf(lerp(s, Color.Black, if (dark) 0.3f else 0f), s, lerp(s, tone(1, p), 0.22f))))
            glowAt(tone(1, p), 0.3f, w * 0.5f, h * 1.05f, w * 0.9f)
            val r = Seeded(9)
            repeat(80) {
                val y = h * (1f - r.next() * r.next()); val x = r.next() * w; val big = r.next()
                val c = if (r.next() > 0.5f) tone(1, p) else tone(2, g)
                val rad = (1f + big * 2.2f).dp.toPx()
                drawCircle(c.copy(alpha = 0.18f * (y / h)), rad * 3f, Offset(x, y)); drawCircle(c.copy(alpha = 0.3f + 0.6f * (y / h) * big), rad * 0.6f, Offset(x, y))
            }
        }
        "fog" -> {
            // Hills dissolving into banks of fog.
            drawRect(Brush.verticalGradient(listOf(lerp(s, ink, 0.06f), s)))
            for (i in 0..3) {
                band(h * (0.42f + i * 0.13f), h * 0.03f, 0.6f + i * 0.3f, i * 2.1f, androidx.compose.ui.graphics.SolidColor(lerp(s, if (i % 2 == 0) p else ink, 0.08f + i * 0.04f)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, lerp(s, ink, 0.05f).copy(alpha = 0.7f), Color.Transparent), startY = h * (0.38f + i * 0.13f), endY = h * (0.5f + i * 0.13f)),
                    Offset(0f, h * (0.38f + i * 0.13f)), Size(w, h * 0.12f))
            }
        }
        "topo" -> {
            // A contour map: rings around two hills, every fifth line in the accent.
            drawRect(s)
            listOf(Offset(w * 0.25f, h * 0.3f) to 0.3f, Offset(w * 0.85f, h * 0.78f) to 2.1f).forEach { (c, ph) ->
                for (k in 1..16) {
                    val path = androidx.compose.ui.graphics.Path(); val base = k * 18.dp.toPx()
                    for (a in 0..72) {
                        val th = a / 72f * 6.283f
                        val rad = base * (1f + 0.13f * kotlin.math.sin(3 * th + k * 0.35f + ph) + 0.07f * kotlin.math.sin(5 * th + ph))
                        val pt = Offset(c.x + rad * kotlin.math.cos(th), c.y + rad * kotlin.math.sin(th))
                        if (a == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                    }
                    path.close()
                    drawPath(path, if (k % 5 == 0) p.copy(alpha = 0.3f) else lerp(s, ink, 0.1f), style = Stroke(1.dp.toPx()))
                }
            }
        }
        "bauhaus" -> {
            // Big flat shapes in the palette: a circle, a quarter, a bar and a few lines.
            drawRect(s)
            drawCircle(p.copy(alpha = 0.2f), w * 0.32f, Offset(w * 0.86f, h * 0.16f))
            drawArc(tone(0, g).copy(alpha = 0.18f), 180f, 90f, true, Offset(-w * 0.45f, h * 0.62f), Size(w * 0.9f, w * 0.9f))
            drawRect(tone(2, g).copy(alpha = 0.2f), Offset(w * 0.62f, h * 0.48f), Size(w * 0.38f, h * 0.06f))
            drawArc(g.copy(alpha = 0.16f), 0f, 180f, true, Offset(w * 0.08f, h * 0.2f), Size(w * 0.36f, w * 0.36f))
            drawCircle(ink.copy(alpha = 0.08f), w * 0.07f, Offset(w * 0.7f, h * 0.82f))
            val line = ink.copy(alpha = 0.09f)
            drawLine(line, Offset(w * 0.1f, h * 0.44f), Offset(w * 0.9f, h * 0.44f), 1.5.dp.toPx())
            drawLine(line, Offset(w * 0.55f, h * 0.05f), Offset(w * 0.55f, h * 0.95f), 1.5.dp.toPx())
        }
        "grid" -> {
            // A tiling desktop: a fine grid with a few lit tiles.
            drawRect(Brush.verticalGradient(listOf(s, lerp(s, p, 0.06f))))
            val step = 34.dp.toPx(); val line = lerp(s, ink, 0.06f)
            var x = 0f; while (x < w) { drawLine(line, Offset(x, 0f), Offset(x, h), 1f); x += step }
            var y = 0f; while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), 1f); y += step }
            val r = Seeded(2)
            repeat(9) {
                val cx = (r.next() * (w / step)).toInt() * step; val cy = (r.next() * (h / step)).toInt() * step
                drawRect((if (r.next() > 0.5f) p else g).copy(alpha = 0.06f + r.next() * 0.1f), Offset(cx + 1f, cy + 1f), Size(step - 2f, step - 2f))
            }
        }
        else -> {
            // Glow: a calm gradient with soft lights in the corners.
            drawRect(Brush.verticalGradient(listOf(s, lerp(s, p, 0.08f))))
            glowAt(g, 0.16f, w * 0.15f, h * 0.08f, w * 0.9f)
            glowAt(p, 0.12f, w * 0.95f, h * 0.85f, w)
            glowAt(tone(4, scheme.secondary), 0.09f, w * 0.9f, h * 0.3f, w * 0.6f)
        }
    }
}

/** Over the wallpaper or a photo: a light shade at the top and bottom so the clock, notices and the create box stay readable. */
fun Modifier.wallpaperScrim(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.18f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.38f)))
}

/** A photo filling the screen (cropped, never stretched), under the scrim. */
fun Modifier.photoBackdrop(img: ImageBitmap): Modifier = drawBehind {
    val scale = maxOf(size.width / img.width, size.height / img.height)
    val w = (size.width / scale).toInt().coerceIn(1, img.width); val h = (size.height / scale).toInt().coerceIn(1, img.height)
    drawImage(img, IntOffset((img.width - w) / 2, (img.height - h) / 2), IntSize(w, h), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
}.wallpaperScrim()

/**
 * The whole look on one panel: themes (the agents' and the ready-made ones), styles, palettes, background
 * (with a photo), light or dark, and a box to make or change a theme with the agent.
 */
@Composable
fun LookPicker(m: MakerModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Look.installDocs(context); while (true) { Look.reloadCustom(context); kotlinx.coroutines.delay(1500) } }
    val photo: (Uri?) -> Unit = { uri -> if (uri != null) scope.launch { if (!Look.setImage(context, uri)) m.notice = "Couldn't open that picture." } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), photo)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), photo)
    var ask by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<CustomTheme?>(null) }
    var removing by remember { mutableStateOf<CustomTheme?>(null) }
    val label: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val dark = isDarkLook()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Themes are whole looks; below them, each part changes on its own and keeps the rest.
        label("Themes")
        Muted("A complete look: shape, colours and background together.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Look.custom, key = { "custom:" + it.id }) { t ->
                Box {
                    val bg = t.json.opt("background")
                    val art = (bg as? String)?.takeIf { isArt(it) } ?: (bg as? JSONObject)?.optString("art")?.takeIf { isArt(it) } ?: shapeBackdrop(t.style)
                    StyleCard(t.style, Look.style == "custom:" + t.id, t.palette(), badge = if (t.problems.isEmpty()) "Yours" else "Yours · ${t.problems.size} ignored",
                        art = art, long = { menu = t }) { Look.choose(context, t) }
                    DropdownMenu(expanded = menu == t, onDismissRequest = { menu = null }) {
                        DropdownMenuItem(text = { Text("Change with ${agentName(m.agent)}") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = null; Look.choose(context, t); m.notice = "Describe the change in the box below, then tap Change this theme." })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = null; removing = t })
                    }
                }
            }
            items(Themes.filter { it.style.isNotBlank() }, key = { "preset:" + it.id }) { p ->
                val st = Styles.firstOrNull { it.id == p.style } ?: Styles[0]
                val art = themeArt(p.id) ?: shapeBackdrop(st)
                StyleCard(st, Look.style == p.style && Look.theme == p.id && Look.background == "theme" && Look.artNow == art, p, title = p.name,
                    badge = "${st.name} · ${artName(art)}", art = art) { Look.choosePreset(context, p) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Text("Make it your own", style = MaterialTheme.typography.titleSmall)
        Muted("Each part changes only itself: a new shape keeps your colours and background, and so on.")
        label("Shape")
        Muted("Corners, borders, type, the clock and how things move.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Styles, key = { it.id }) { s -> StyleCard(s, Look.style == s.id) { Look.set(context, style = s.id) } }
        }
        // Border width for every card and widget, on any theme; "Theme's" goes back to the shape's own.
        Row(verticalAlignment = Alignment.CenterVertically) {
            label("Borders")
            Spacer(Modifier.weight(1f))
            val w = Look.shown.border
            Text(if (w == 0f) "None" else "%.2f".format(w).trimEnd('0').trimEnd('.') + " dp", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { Look.chooseEdge(-1f) }, enabled = Look.edge >= 0f) { Text("Theme's") }
        }
        androidx.compose.material3.Slider(
            value = Look.shown.border, onValueChange = { Look.chooseEdge((it * 4).roundToInt() / 4f) },
            valueRange = 0f..3f, steps = 11, modifier = Modifier.fillMaxWidth(),
        )
        // How see-through the widgets' backgrounds and their borders are, from clear to solid.
        OpacitySlider("Widget background", Look.shown.cardOpacity, Look.fill >= 0f, Look::chooseFill)
        val borderNow = lookBorder(Look.shown).alpha
        OpacitySlider("Border opacity", borderNow, Look.edgeAlpha >= 0f, Look::chooseEdgeAlpha)
        if (Look.shown.border == 0f && borderNow > 0f) Muted("Borders are off: raise Borders above to see them.")
        if (Look.currentCustom?.palette() == null) {
            label("Colours")
            Muted("The palette for the shape you have.")
            LazyHorizontalGrid(
                rows = GridCells.Fixed(2), modifier = Modifier.height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                gridItems(Themes, key = { it.id }) { t -> Swatch(t, Look.theme == t.id) { Look.set(context, theme = t.id) } }
            }
        }
        label("Background")
        val scheme = MaterialTheme.colorScheme
        val tones = Look.palette.tones(dark)
        Muted("Paintings, one made for each theme in its colours.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Paintings, key = { it.id }) { b ->
                ArtTile(b, scheme, tones, Look.background == "theme" && Look.artNow == b.id) { Look.set(context, background = "theme", art = b.id) }
            }
        }
        Muted("Drawn in your colours, so each one suits every theme.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Backdrops, key = { it.id }) { b ->
                ArtTile(b, scheme, tones, Look.background == "theme" && Look.artNow == b.id) { Look.set(context, background = "theme", art = b.id) }
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("plain" to "Plain", "wallpaper" to "Phone wallpaper").forEach { (k, l) ->
                FilterChip(selected = Look.background == k, onClick = { Look.set(context, background = k) }, label = { Text(l) })
            }
            if (Look.image != null) FilterChip(selected = Look.background == "image", onClick = { Look.set(context, background = "image") }, label = { Text("Photo") })
            AssistChip(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, label = { Text("Gallery") }, leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { files.launch(arrayOf("image/*")) }, label = { Text("Files") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) })
        }
        if (Look.current.dark == "auto") label("Light or dark")
        if (Look.current.dark == "auto") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                FilterChip(selected = Look.mode == k, onClick = { Look.set(context, mode = k) }, label = { Text(l) })
            }
        }
        // Agents make and change themes: everything above, plus a background picture, in one file they check with `theme check`.
        val mine = Look.currentCustom
        label("Make or change a theme with ${agentName(m.agent)}")
        val go = { if (ask.isNotBlank() && m.makeTheme(ask, false)) ask = "" }
        OutlinedTextField(
            value = ask, onValueChange = { ask = it }, maxLines = 3, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (mine != null) "Warmer colours, square corners, a rainy photo…" else "Retro arcade, rainy Tokyo night, Bauhaus…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            trailingIcon = { IconButton(onClick = go, enabled = ask.isNotBlank() && !MakerRuntime.running) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Make a new theme") } },
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = go, enabled = ask.isNotBlank() && !MakerRuntime.running) { Text("New theme") }
            OutlinedButton(onClick = { if (m.makeTheme(ask, true)) ask = "" }, enabled = ask.isNotBlank() && !MakerRuntime.running) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (mine != null) "Change ${mine.name}" else "Change this look")
            }
        }
        if (m.themeTask) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Text("${agentName(m.agent)} is working on it. Every save shows here at once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { m.showConsole = true }) { Text("Watch") }
            }
        } else Muted(if (mine != null) "Changes go into ${mine.name}. Pick a built-in style first to start a new copy instead." else "A new theme is chosen as soon as it's saved. Changing a built-in look makes your own copy. Long-press one of your themes to change or delete it.")
        val issues = mine?.problems.orEmpty() + Look.broken.values
        if (issues.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Ignored in your themes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            issues.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (issues.size > 4) Text("…and ${issues.size - 4} more in /root/themes/errors.txt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { if (ask.isBlank()) ask = "Fix what the launcher ignores (theme check)" }) { Text("Ask the agent to fix it") }
        }
    }
    removing?.let { t ->
        AlertDialog(
            onDismissRequest = { removing = null }, title = { Text("Delete ${t.name}?") },
            text = { Text("It moves to /root/themes/.trash, so it can be brought back.") },
            confirmButton = { TextButton(onClick = { Look.remove(context, t); removing = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

/** A 0–100 % opacity slider with a button back to the theme's own value (choose(-1f)). */
@Composable
private fun OpacitySlider(title: String, value: Float, own: Boolean, choose: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            val pct = (value.coerceIn(0f, 1f) * 100).roundToInt()
            Text(when (pct) { 0 -> "Clear"; 100 -> "Solid"; else -> "$pct %" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { choose(-1f) }, enabled = own) { Text("Theme's") }
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(0f, 1f), onValueChange = { choose((it * 20).roundToInt() / 20f) },
            valueRange = 0f..1f, steps = 19, modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StyleCard(s: LookStyle, on: Boolean, colors: ThemeDef? = null, title: String = s.name, badge: String = "", art: String? = null, long: (() -> Unit)? = null, pick: () -> Unit) {
    val dark = isDarkLook() || s.dark == "always" || colors?.native == "dark"
    val p = colors ?: Look.palette
    val surface = if (dark) p.surfaceDark else p.surface
    val ink = if (dark) p.inkDark else p.ink
    val accent = if (dark) p.accentDark else p.accent
    val tones = p.tones(dark)
    val shape = RoundedCornerShape(minOf(s.corner, 18f).dp)
    Column(
        Modifier.width(118.dp).clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClickLabel = "Use $title", onLongClickLabel = if (long != null) "Change or delete" else null, onLongClick = long, onClick = pick).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(14.dp))
                .then(if (art != null) Modifier.artBackdrop(schemeFor(p, dark), art, tones, small = true) else Modifier.background(surface))
                .border(if (on) 2.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            // A tiny home screen in this style: a card with the palette's tones, and the create box.
            val border = when (s.borderColor) { "text" -> ink; "accent" -> accent; "light" -> Color.White.copy(alpha = 0.5f); else -> lerp(ink, surface, 0.8f) }
            Box(Modifier.fillMaxWidth().height(30.dp).clip(shape).background(lerp(surface, ink, 0.08f).copy(alpha = s.cardOpacity))
                .then(if (s.border > 0) Modifier.border(s.border.dp.coerceAtMost(2.dp), border, shape) else Modifier)
                .padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { tones.forEach { Box(Modifier.size(7.dp).clip(CircleShape).background(it)) } }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.8f).height(12.dp).clip(shape).background(lerp(surface, ink, 0.12f))
                .then(if (s.border > 0) Modifier.border(1.dp, border, shape) else Modifier)) {
                Box(Modifier.align(Alignment.CenterEnd).padding(2.dp).size(8.dp).clip(CircleShape).background(accent))
            }
            if (on) Icon(Icons.Default.Check, "Selected", Modifier.align(Alignment.TopEnd).size(14.dp), tint = accent)
        }
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(badge.ifBlank { s.description }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A drawn background, small, in the colours in use. */
@Composable
private fun ArtTile(b: Backdrop, scheme: ColorScheme, tones: List<Color>, on: Boolean, pick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Use the ${b.name} background", onClick = pick).padding(2.dp)) {
        Box(
            Modifier.size(52.dp, 84.dp).clip(RoundedCornerShape(12.dp)).artBackdrop(scheme, b.id, tones, small = true)
                .border(if (on) 2.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.TopEnd,
        ) { if (on) Icon(Icons.Default.Check, "Selected", Modifier.padding(4.dp).size(14.dp), tint = scheme.primary) }
        Text(b.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A palette as a disc: its surface, a ring of its seven tones, and its accent and glow in the middle. */
@Composable
private fun Swatch(t: ThemeDef, on: Boolean, pick: () -> Unit) {
    val dark = isDarkLook()
    val surface = if (dark) t.surfaceDark else t.surface
    val tones = t.tones(dark)
    val accent = if (dark) t.accentDark else t.accent; val glow = if (dark) t.glowDark else t.glow
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Use ${t.name}", onClick = pick).padding(4.dp)) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(surface)
                .border(if (on) 2.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .drawBehind {
                    val w = 4.dp.toPx(); val inset = 6.dp.toPx()
                    val sweep = 360f / tones.size
                    tones.forEachIndexed { i, c ->
                        drawArc(c, -90f + i * sweep + 4f, sweep - 8f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(w, cap = StrokeCap.Round))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                Box(Modifier.size(9.dp).clip(CircleShape).background(glow))
            }
        }
        Text(t.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
