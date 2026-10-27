package com.hatchlybox.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

// The pieces every widget is built from, drawn in the launcher's style, so a widget made by anyone looks like
// it belongs to the theme in use: titles, bars, rings and charts change with the shape (Soft, Old school,
// Neon, Glass, Tiles) and take the palette's colours.

/** How the style draws a widget's parts. */
private class Parts(val segmented: Boolean, val square: Boolean, val glow: Boolean, val caps: Boolean, val mono: Boolean, val bracket: Boolean, val bar: Boolean)

private fun parts(st: LookStyle) = Parts(
    segmented = st.motion == "snappy", square = st.corner < 8f, glow = st.glow, caps = st.uppercase || st.motion == "snappy",
    mono = st.font == "mono", bracket = st.motion == "snappy", bar = st.id == "tiled" || (st.borderColor == "accent" && !st.glow),
)

/** The title of a widget: an icon and a name, in the way the style writes its labels. trailing: a small value at the end. */
@Composable
fun WidgetHeader(text: String, icon: ImageVector?, tint: Color = MaterialTheme.colorScheme.primary, trailing: String = "", modifier: Modifier = Modifier) {
    val st = LocalLook.current
    val k = parts(st)
    val c = MaterialTheme.colorScheme
    val label = if (k.caps) text.uppercase(Locale.getDefault()) else text
    val style = MaterialTheme.typography.labelLarge.copy(
        letterSpacing = if (k.caps) 1.6.sp else 0.2.sp,
        fontWeight = if (k.bar) FontWeight.SemiBold else FontWeight.Medium,
        shadow = if (k.glow) Shadow(tint.copy(alpha = 0.8f), Offset.Zero, 14f) else null,
    )
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (k.bracket) Text("▌", style = style, color = tint)
            else if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Text(label, style = style, color = if (k.glow || k.bar) tint else c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (trailing.isNotBlank()) Text(if (k.caps) trailing.uppercase(Locale.getDefault()) else trailing, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1)
        }
        // Tiles underline their titles with a short accent bar.
        if (k.bar) Box(Modifier.padding(top = 5.dp).width(22.dp).height(2.dp).background(tint))
    }
}

/** A progress bar in the style: blocks (Old school), a glowing line (Neon), square (Tiles) or rounded. */
@Composable
fun ThemedBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val k = parts(LocalLook.current)
    val p = progress.coerceIn(0f, 1f)
    val track = color.copy(alpha = 0.16f)
    Canvas(modifier.fillMaxWidth().height(if (k.segmented) 9.dp else if (k.glow) 5.dp else 7.dp)) {
        val h = size.height
        if (k.segmented) {
            val n = 20; val gap = 2.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) drawRect(if ((i + 0.5f) / n <= p) color else track, Offset(i * (w + gap), 0f), Size(w, h))
            return@Canvas
        }
        val r = if (k.square) CornerRadius(1.dp.toPx()) else CornerRadius(h / 2)
        drawRoundRect(track, cornerRadius = r)
        if (p <= 0f) return@Canvas
        if (k.glow) for (i in 1..3) drawRoundRect(color.copy(alpha = 0.12f / i), Offset(0f, -i * 2.dp.toPx()), Size(size.width * p, h + i * 4.dp.toPx()), CornerRadius(h))
        drawRoundRect(color, size = Size(maxOf(size.width * p, h), h), cornerRadius = r)
    }
}

/** A ring in the style: ticks (Old school), glowing (Neon), flat ends (Tiles) or round ends. */
@Composable
fun ThemedRing(progress: Float, color: Color, size: Dp, modifier: Modifier = Modifier, thickness: Dp = (size * 0.1f).coerceIn(4.dp, 9.dp)) {
    val k = parts(LocalLook.current)
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier.size(size)) {
        val t = thickness.toPx(); val inset = t / 2 + if (k.glow) 3.dp.toPx() else 0f
        val box = Size(this.size.width - inset * 2, this.size.height - inset * 2); val at = Offset(inset, inset)
        if (k.segmented) {
            val n = 24; val sweep = 360f / n
            for (i in 0 until n) drawArc(if ((i + 0.5f) / n <= p) color else color.copy(alpha = 0.16f), -90f + i * sweep + 2f, sweep - 4f, false, at, box, style = Stroke(t))
            return@Canvas
        }
        drawArc(color.copy(alpha = 0.16f), 0f, 360f, false, at, box, style = Stroke(t))
        if (p <= 0f) return@Canvas
        val cap = if (k.square) StrokeCap.Butt else StrokeCap.Round
        if (k.glow) for (i in 1..3) drawArc(color.copy(alpha = 0.12f / i), -90f, 360f * p, false, at, box, style = Stroke(t + i * 4.dp.toPx(), cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * p, false, at, box, style = Stroke(t, cap = cap))
    }
}

/** A ring with its value in the middle, and a label and a caption beneath. */
@Composable
fun ThemedMeter(progress: Float, value: String, label: String, caption: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val k = parts(LocalLook.current)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(contentAlignment = Alignment.Center) {
            ThemedRing(progress, color, size)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontSize = (size.value * 0.24f).coerceIn(11f, 26f).sp,
                fontFamily = if (k.mono) FontFamily.Monospace else MaterialTheme.typography.titleMedium.fontFamily,
                shadow = if (k.glow) Shadow(color, Offset.Zero, 12f) else null), maxLines = 1)
        }
        if (label.isNotBlank()) Text(if (k.caps) label.uppercase(Locale.getDefault()) else label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = if (k.caps) 1.2.sp else 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A labelled number: a small label, the value large, a caption. */
@Composable
fun ThemedStat(label: String, value: String, caption: String, icon: ImageVector?, color: Color, modifier: Modifier = Modifier) {
    val k = parts(LocalLook.current)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = color)
            Text(if (k.caps) label.uppercase(Locale.getDefault()) else label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = if (k.caps) 1.2.sp else 0.3.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", shadow = if (k.glow) Shadow(color, Offset.Zero, 14f) else null),
            color = if (k.glow) color else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Whether lines and bars in charts glow, and whether bars are square, in the style in use. */
@Composable
internal fun chartLook(): Pair<Boolean, Boolean> = parts(LocalLook.current).let { it.glow to it.square }

/**
 * The phone's own numbers for widgets, as {phone.key}: battery (0-100), charging, batteryStatus, batteryTone
 * (green, yellow or red), batteryTemp, storage (% used), storageFree. Read at most every 15 s.
 */
object PhoneInfo {
    var json by mutableStateOf(JSONObject())
        private set
    private var last = 0L

    fun update(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - last < 15_000) return
        last = now
        val j = JSONObject()
        runCatching {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
                val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                j.put("battery", pct).put("charging", charging)
                    .put("batteryStatus", when { status == BatteryManager.BATTERY_STATUS_FULL -> "Full"; charging -> "Charging"; pct <= 15 -> "Low"; else -> "On battery" })
                    .put("batteryTone", when { charging -> "green"; pct <= 15 -> "red"; pct <= 35 -> "yellow"; else -> "green" })
                    .put("batteryTemp", if (temp > 0) String.format(java.util.Locale.getDefault(), "%.1f°C", temp) else "Unavailable")
                    .put("batteryHealth", when (b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) { BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"; BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; BatteryManager.BATTERY_HEALTH_DEAD -> "Poor"; else -> "Unknown" })
                    .put("powerSource", when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) { BatteryManager.BATTERY_PLUGGED_AC -> "AC power"; BatteryManager.BATTERY_PLUGGED_USB -> "USB power"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless power"; else -> "Battery power" })
            }
        }
        runCatching {
            val fs = StatFs(context.filesDir.path)
            val total = fs.totalBytes.toDouble(); val free = fs.availableBytes.toDouble()
            j.put("storage", if (total > 0) ((total - free) * 100 / total).toInt() else 0).put("storageFree", "%.0f GB".format(free / 1e9))
        }
        if (j.toString() != json.toString()) json = j
    }
}
