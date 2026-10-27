package com.hatchlybox.nightly

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The widget engine: home-screen widgets that the agents write as JSON in /root/widgets/<id>/widget.json
// (the format is assets/widgets/WIDGETS.md, copied next to them), with live data from a shell command.

const val WIDGETS_DIR = "/root/widgets"

/** One widget as last read from disk. `spec` is null when widget.json doesn't parse (`error` says why). */
class HomeWidget(val id: String, val spec: JSONObject?, val state: JSONObject, val error: String, val stamp: String) {
    val name get() = spec?.optString("name").orEmpty().ifBlank { id }
    val size get() = spec?.optString("size").orEmpty().ifBlank { "auto" }
    /** A terminal widget: {"command", "title", look keys}. It runs that program instead of drawing a ui. */
    val terminal get() = spec?.optJSONObject("terminal")
    /** Set on the widgets placed from Add a widget's built-in list ("opencode", "system"…); they aren't listed under Your widgets. */
    val builtin get() = spec?.optString("builtin").orEmpty()
}

class WidgetEngine(private val context: Context, private val linux: Linux) {
    private val prefs = context.getSharedPreferences("widgets", 0)
    val widgets = mutableStateListOf<HomeWidget>()
    /** Widgets whose data command is running now. */
    val loading = mutableStateMapOf<String, Boolean>()
    /** The last data command's error, per widget. */
    val errors = mutableStateMapOf<String, String>()
    private val lastRun = HashMap<String, Long>()
    /** Where timed reactions finish, even when the widget's screen or page has gone. */
    private val later = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    /** The built-in widgets' looks, by placed id: /root/widgets/builtin-<id>/style.json, written by agents. */
    val styles = mutableStateMapOf<String, JSONObject>()
    private val styleStamps = HashMap<String, Long>()
    private val root get() = linux.hostFile(WIDGETS_DIR)

    /** The format and the `widget` command, refreshed when this version of the app brings new ones. */
    fun install() {
        if (!linux.hasBasics) return
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val mark = File(root, ".engine")
        if (mark.isFile && mark.readText() == version) return
        root.mkdirs()
        val doc = context.assets.open("widgets/WIDGETS.md").use { it.readBytes() }
        File(root, "WIDGETS.md").writeBytes(doc)
        val pointer = "# Hatchlybox widgets\n\nEach folder here is a home-screen widget. Read WIDGETS.md (in this folder) before making or changing one.\n"
        File(root, "CLAUDE.md").writeText(pointer); File(root, "AGENTS.md").writeText(pointer)
        // Working widgets to learn from or copy (`widget new <id> <example>`): replaced with each version.
        val examples = File(root, "examples")
        examples.deleteRecursively()
        context.assets.list("widgets/examples").orEmpty().forEach { ex ->
            context.assets.list("widgets/examples/$ex").orEmpty().forEach { f ->
                val out = File(examples, "$ex/$f"); out.parentFile?.mkdirs()
                out.writeBytes(context.assets.open("widgets/examples/$ex/$f").use { it.readBytes() })
                if (f.endsWith(".sh")) out.setExecutable(true, false)
            }
        }
        upgradeBuiltins()
        installGuide()
        PhoneBridge.install(context)
        val bin = linux.hostFile("/root/.local/bin/widget")
        bin.parentFile?.mkdirs()
        bin.writeBytes(context.assets.open("widgets/widget.sh").use { it.readBytes() }); bin.setExecutable(true, false)
        mark.writeText(version)
    }

    private fun order() = (prefs.getString("order", "") ?: "").split('\n').filter { it.isNotBlank() }

    /** Reads the folders again; only changed widgets are replaced. */
    suspend fun reload() {
        val found = withContext(Dispatchers.IO) {
            runCatching { install() }
            val dirs = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name != "examples" && File(it, "widget.json").isFile }
            val order = order()
            dirs.sortedWith(compareBy({ order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.lastModified() }))
                .map { d -> widgets.firstOrNull { it.id == d.name && it.stamp == stamp(d) } ?: load(d) }
        }
        if (found.map { it.id + it.stamp } != widgets.map { it.id + it.stamp }) { widgets.clear(); widgets.addAll(found) }
        val errorKey = found.joinToString { it.id + it.stamp } + errors.toMap().toString()
        if (errorKey != lastErrorKey) { withContext(Dispatchers.IO) { runCatching { writeErrors(found) } }; lastErrorKey = errorKey }
        val looks = withContext(Dispatchers.IO) {
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("builtin-") }.mapNotNull { d ->
                val f = File(d, "style.json").takeIf { it.isFile } ?: return@mapNotNull null
                val id = d.name.removePrefix("builtin-")
                if (styleStamps[id] == f.lastModified() && styles.containsKey(id)) null
                else runCatching { Triple(id, f.lastModified(), JSONObject(f.readText())) }.getOrNull()
            }
        }
        looks.forEach { (id, stamp, json) -> styleStamps[id] = stamp; styles[id] = json }
    }

    private fun stamp(d: File): String {
        val w = File(d, "widget.json"); val s = File(d, "state.json")
        return "${w.lastModified()}:${w.length()}:${s.lastModified()}:${s.length()}"
    }

    private fun load(d: File): HomeWidget {
        val st = stamp(d)
        val spec = runCatching { JSONObject(File(d, "widget.json").readText()) }
        val state = JSONObject()
        spec.getOrNull()?.optJSONObject("state")?.let { merge(state, it) }
        runCatching { merge(state, JSONObject(File(d, "state.json").readText())) }
        val error = spec.exceptionOrNull()?.let { "widget.json: ${it.message}" }
            ?: spec.getOrNull()?.let { j ->
                val t = j.optJSONObject("terminal")
                when {
                    t != null -> if (t.optString("command").isBlank()) "widget.json: \"terminal\" needs a \"command\"" else ""
                    j.optJSONObject("ui") == null -> "widget.json has no \"ui\" (or \"terminal\")"
                    else -> ""
                }
            }.orEmpty()
        return HomeWidget(d.name, spec.getOrNull(), state, error, st)
    }

    private fun merge(into: JSONObject, from: JSONObject) { from.keys().forEach { into.put(it, from.get(it)) } }

    private var lastErrors: String? = null
    private var lastErrorKey = ""

    /**
     * /root/widgets/errors.txt, like the launcher's: for each widget, what can't be shown or is ignored, and
     * why (a file that doesn't parse, unknown nodes, keys, colours or icons, a data command that failed).
     */
    private fun writeErrors(list: List<HomeWidget>) {
        val text = buildString {
            for (w in list) {
                val issues = (listOfNotNull(w.error.takeIf { it.isNotBlank() }) + (w.spec?.let { lintWidget(it, File(root, w.id)) }.orEmpty()) +
                    listOfNotNull(errors[w.id]?.takeIf { it.isNotBlank() }?.let { "data command: $it" }))
                issues.forEach { append(w.id).append(": ").append(it).append('\n') }
            }
        }.ifEmpty { "No problems: every widget is drawn as written.\n" }
        if (text == lastErrors) return
        File(root, "errors.txt").writeText(text); lastErrors = text
    }

    /** Merges keys into state.json. */
    private val stateLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val revisions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val refreshSlots = kotlinx.coroutines.sync.Semaphore(2)
    private fun writeState(id: String, values: JSONObject) = synchronized(stateLocks.getOrPut(id) { Any() }) {
        val f = File(root, "$id/state.json")
        f.parentFile?.mkdirs()
        java.io.RandomAccessFile(File(f.parentFile, ".state.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                val present = f.isFile || File(f.path + ".bak").isFile
                val state = if (present) JSONObject(SafeFiles.read(f)) else JSONObject()
                merge(state, values)
                SafeFiles.write(f, state.toString(2))
                revisions[id] = (revisions[id] ?: 0L) + 1L
            }
        }
    }

    /** Runs each widget's data command when it's due, or now when `widget refresh` asked for it. */
    suspend fun refreshDue(force: String? = null) {
        for (w in widgets.toList()) {
            val data = w.spec?.optString("data").orEmpty()
            if (data.isBlank() || loading[w.id] == true) continue
            val asked = withContext(Dispatchers.IO) { File(root, "${w.id}/.refresh").let { f -> f.exists().also { if (it) f.delete() } } }
            val every = w.spec!!.optInt("refresh", 900).coerceAtLeast(60) * 1000L
            if (force != w.id && !asked && System.currentTimeMillis() - (lastRun[w.id] ?: 0L) < every) continue
            loading[w.id] = true
            later.launch {
                refreshSlots.acquire()
                try { command(w.id, data) } finally { loading[w.id] = false; refreshSlots.release() }
            }
        }
    }

    /** A command in the widget's folder; a JSON object it prints goes into the state. */
    private val commandLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private suspend fun command(id: String, cmd: String) {
        val serial = commandLocks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }
        serial.lock()
        try {
            lastRun[id] = System.currentTimeMillis(); loading[id] = true
            val (code, out) = withContext(Dispatchers.IO) {
                runCatching { linux.run("cd ${quote("$WIDGETS_DIR/$id")} && timeout 30 sh -c ${quote(cmd)}") }.getOrElse { -1 to (it.message ?: "") }
            }
            val json = out.indexOf('{').takeIf { it >= 0 }?.let { a -> runCatching { JSONObject(out.substring(a, out.lastIndexOf('}') + 1)) }.getOrNull() }
            val responseError = json?.opt("error")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
            val failedWrite = if (json != null && responseError.isBlank() && code == 0) withContext(Dispatchers.IO) { runCatching { writeState(id, json) }.exceptionOrNull() } else null
            when {
                responseError.isNotBlank() -> errors[id] = responseError
                failedWrite != null -> errors[id] = "State was kept: ${failedWrite.message}"
                code != 0 -> errors[id] = out.lines().lastOrNull { it.isNotBlank() }.orEmpty().ifBlank { "Stopped with code $code" }
                else -> errors.remove(id)
            }
            reload()
        } finally { loading[id] = false; serial.unlock() }
    }

    /** What a tap does: open, run, set, toggle, refresh, or open and close the widget's screen. */
    suspend fun act(w: HomeWidget, action: JSONObject, open: (String) -> Unit) {
        if (action.optBoolean("screen") && w.spec?.optJSONObject("screen") != null) WidgetScreens.open = w.id
        if (action.optBoolean("close")) WidgetScreens.open = null
        action.optString("open").takeIf { it.isNotBlank() }?.let { open(fill(it, w.state, null)) }
        action.optJSONObject("set")?.let { v ->
            // With "for": seconds, the keys go back to what they were after that long: a reaction (a purr, a hop) that ends by itself.
            val back = action.optDouble("for", 0.0).takeIf { it > 0 }?.let { JSONObject().apply { v.keys().forEach { k -> put(k, w.state.opt(k) ?: JSONObject.NULL) } } }
            withContext(Dispatchers.IO) { writeState(w.id, v) }; reload()
            val revision = revisions[w.id]
            val stateStamp = File(root, "${w.id}/state.json").lastModified()
            if (back != null) later.launch {
                delay((action.optDouble("for") * 1000).toLong().coerceAtMost(600_000))
                withContext(Dispatchers.IO) { synchronized(stateLocks.getOrPut(w.id) { Any() }) {
                    if (revisions[w.id] == revision && File(root, "${w.id}/state.json").lastModified() == stateStamp) runCatching { writeState(w.id, back) }
                } }; reload()
            }
        }
        action.optString("toggle").takeIf { it.isNotBlank() }?.let { k ->
            withContext(Dispatchers.IO) { writeState(w.id, JSONObject().put(k, !truthy(w.state.opt(k)))) }; reload()
        }
        action.optString("run").takeIf { it.isNotBlank() }?.let { cmd ->
            command(w.id, fill(cmd, w.state, null))
            w.spec?.optString("data")?.takeIf { it.isNotBlank() }?.let { command(w.id, it) }
        }
        if (action.optBoolean("refresh")) refreshDue(force = w.id)
    }

    /**
     * A built-in widget the user asks to change becomes theirs: it loses its `builtin` mark (and the copy that
     * tells whether it was changed), so it's listed under Your widgets and never replaced by a newer example.
     * The built-in list keeps placing fresh copies of the example.
     */
    fun adopt(id: String) {
        val f = File(root, "$id/widget.json")
        runCatching {
            val j = JSONObject(f.readText())
            if (j.optString("builtin").isBlank()) return
            j.remove("builtin")
            f.writeText(j.toString(2)); File(root, "$id/.origin").delete()
        }
    }

    fun refreshNow(id: String) { File(root, "$id/.refresh").runCatching { createNewFile() } }

    /** Removing keeps a copy in /root/widgets/.trash. */
    suspend fun remove(id: String) {
        withContext(Dispatchers.IO) { File(root, ".trash").mkdirs(); File(root, id).renameTo(File(root, ".trash/$id-${System.currentTimeMillis() / 1000}")) }
        reload()
    }

    suspend fun moveFirst(id: String) {
        prefs.edit().putString("order", (listOf(id) + widgets.map { it.id }.filter { it != id }).joinToString("\n")).apply()
        reload()
    }

    /**
     * Where the agents are and what they can make here (assets/agents/ENVIRONMENT.md), in each agent's own
     * global instructions, so a session started in a terminal widget knows it too. Only our marked part of
     * each file is replaced; anything the user wrote there stays.
     */
    /** The agents' instructions and the phone command, current with this version of the app (home runs it when it opens). */
    fun refreshAgents() {
        if (!linux.hasBasics) return
        installGuide()
        PhoneBridge.install(context)
    }

    private fun installGuide() {
        val guide = context.assets.open("agents/ENVIRONMENT.md").use { it.readBytes() }.decodeToString().trim()
        val start = "<!-- hatchlybox: start -->"; val end = "<!-- hatchlybox: end -->"
        val block = "$start\n$guide\n$end"
        for (path in listOf("/root/.claude/CLAUDE.md", "/root/.codex/AGENTS.md", "/root/.config/opencode/AGENTS.md")) runCatching {
            val f = linux.hostFile(path); f.parentFile?.mkdirs()
            val old = if (f.isFile) f.readText() else ""
            val i = old.indexOf(start); val j = old.indexOf(end)
            f.writeText(when {
                i >= 0 && j > i -> old.substring(0, i) + block + old.substring(j + end.length)
                old.isBlank() -> block + "\n"
                else -> block + "\n\n" + old
            })
        }
    }

    /** A copy of one of the examples for Add a widget's built-in list; returns its id. */
    fun newFromExample(example: String, name: String): String {
        runCatching { install() }
        val id = newId(name)
        copyExample(File(root, id), example, name)
        return id
    }

    /** The example's files into d, named and marked as built in; .origin remembers the copy, to tell later if anyone changed it. */
    private fun copyExample(d: File, example: String, name: String) {
        File(root, "examples/$example").copyRecursively(d, overwrite = true)
        d.listFiles().orEmpty().filter { it.name.endsWith(".sh") }.forEach { it.setExecutable(true, false) }
        val w = File(d, "widget.json")
        val text = JSONObject(w.readText()).put("name", name).put("builtin", example).toString(2)
        w.writeText(text); File(d, ".origin").writeText(text)
    }

    /**
     * Built-in widgets nobody has changed since they were placed get the new version of their example
     * (the System widget from before .origin existed too). Changed ones are left as they are.
     */
    private fun upgradeBuiltins() {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name != "examples" && !it.name.startsWith(".") }.forEach { d ->
            runCatching {
                val w = File(d, "widget.json").takeIf { it.isFile } ?: return@runCatching
                val text = w.readText(); val j = JSONObject(text)
                val ex = j.optString("builtin").takeIf { it.isNotBlank() && File(root, "examples/$it/widget.json").isFile } ?: return@runCatching
                val origin = File(d, ".origin")
                val untouched = if (origin.isFile) origin.readText() == text else ex == "system" && j.optString("description").startsWith("Numbers from this Linux")
                val newer = JSONObject(File(root, "examples/$ex/widget.json").readText()).optInt("version", 1) > j.optInt("version", 1)
                if (untouched && newer) copyExample(d, ex, j.optString("name").let { if (ex == "system" && it == "System") "System" else it.ifBlank { ex } })
            }
        }
    }

    /** A free folder name for a new widget, from its description. */
    fun newId(description: String): String {
        val base = description.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').split('-').filter { it.length > 2 }.take(3).joinToString("-").ifBlank { "widget" }
        var id = base; var n = 2
        while (File(root, id).exists()) id = "$base-${n++}"
        return id
    }
}

// ---------- templates ----------

/** A value by path: "key", "key.sub", "item", "item.key", "index", or a list index "list.0". */
private fun lookup(path: String, state: JSONObject, item: Pair<Any?, Int>?): Any? {
    val parts = path.trim().split('.')
    var v: Any? = when (parts[0]) {
        "item" -> item?.first
        "index" -> item?.second?.plus(1)
        "phone" -> if (state.has("phone")) state.opt("phone") else PhoneInfo.json
        else -> state.opt(parts[0])
    }
    for (p in parts.drop(1)) v = when (v) { is JSONObject -> v.opt(p); is JSONArray -> p.toIntOrNull()?.let { v.opt(it) }; else -> null }
    return if (v == JSONObject.NULL) null else v
}

private val placeholder = Regex("""\{([^{}]+)\}""")

private fun show(v: Any?): String = when (v) {
    null -> ""
    is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
    else -> v.toString()
}

/** Text with {key}, {item.key}, {index} and {now:pattern} filled in. */
fun fill(text: String, state: JSONObject, item: Pair<Any?, Int>?): String = placeholder.replace(text) { m ->
    val key = m.groupValues[1]
    if (key.startsWith("now:")) runCatching { SimpleDateFormat(key.removePrefix("now:"), Locale.getDefault()).format(Date()) }.getOrDefault("")
    else show(lookup(key, state, item))
}

/** A property that may be a whole "{key}" (the raw value: a list, a number) or a literal. */
internal fun raw(v: Any?, state: JSONObject, item: Pair<Any?, Int>?): Any? {
    if (v !is String) return v
    val m = placeholder.matchEntire(v.trim()) ?: return v
    return lookup(m.groupValues[1], state, item)
}

private fun truthy(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is JSONArray -> v.length() > 0
    is String -> v.isNotBlank() && v != "false" && v != "0"
    else -> true
}

private fun number(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.trim().removeSuffix("%").toDoubleOrNull(); else -> null }

// ---------- drawing ----------

/**
 * What a node draws with. level: how lean the layout is (0 = everything; 1 leaves out priority 3; 2 leaves out
 * priority 2 too and shortens lists). fit: the card's shape and size, for `when`, `minWidth` and the like.
 */
private class Ctx(val w: HomeWidget, val dir: File, val item: Pair<Any?, Int>?, val act: (JSONObject) -> Unit, val level: Int = 0, val fit: Fit = Fit.Any) {
    fun with(item: Pair<Any?, Int>) = Ctx(w, dir, item, act, level, fit)
    /** An action as written, with {item.…} filled in inside a list (`"run": "./game.sh accuse {item.id}"`). */
    fun bind(a: JSONObject): JSONObject = if (item == null) a else bound(a)
    private fun bound(o: JSONObject): JSONObject = JSONObject().also { out ->
        o.keys().forEach { k -> out.put(k, when (val v = o.opt(k)) { is String -> text(v); is JSONObject -> bound(v); else -> v }) }
    }
    fun text(s: String) = fill(s, w.state, item)
    fun raw(v: Any?) = raw(v, w.state, item)
}

/**
 * A card's size in dp and its shape: tiny (about 2×2 cells or less), wide (at least 1.8 times as wide as tall),
 * tall (at least 1.5 times as tall as wide), large (roomy both ways) or small (the rest, about square).
 */
class Fit(val width: Float, val height: Float) {
    val shape: String = when {
        width < 125f && height < 125f -> "tiny"
        width >= height * 1.8f -> "wide"
        height >= width * 1.5f -> "tall"
        width >= 240f && height >= 240f -> "large"
        else -> "small"
    }
    /** A view's or node's conditions: `when` (shapes), `minWidth`, `maxWidth`, `minHeight`, `maxHeight` (dp of the card). */
    fun matches(n: JSONObject): Boolean {
        val shapes = when (val v = n.opt("when")) {
            is JSONArray -> (0 until v.length()).map { v.optString(it).trim() }
            is String -> v.split(' ', ',', '|').map { it.trim() }.filter { it.isNotEmpty() }
            else -> null
        }
        if (shapes != null && shape !in shapes) return false
        fun num(k: String) = n.optDouble(k).takeIf { !it.isNaN() }?.toFloat()
        num("minWidth")?.let { if (width < it) return false }
        num("maxWidth")?.let { if (width > it) return false }
        num("minHeight")?.let { if (height < it) return false }
        num("maxHeight")?.let { if (height > it) return false }
        return true
    }
    companion object { val Any = Fit(360f, 360f) }
}

val FitShapes = listOf("tiny", "small", "wide", "tall", "large")

/** The first of `views` whose conditions fit the card, or null for the widget's own `ui`. */
private fun viewFor(spec: JSONObject?, fit: Fit): JSONObject? {
    val views = spec?.optJSONArray("views") ?: return null
    return (0 until views.length()).mapNotNull { views.optJSONObject(it) }.firstOrNull { fit.matches(it) && it.optJSONObject("ui") != null }
}

/**
 * Lays a widget's content out so nothing is ever cut off: it tries the full layout, then leaner ones (levels),
 * and takes the first whose natural size fits the card. When even the leanest doesn't fit, it is drawn smaller,
 * just enough to fit. Weighted parts (a chart with `weight`) take no room while measuring and fill what's left.
 */
@Composable
private fun FitContent(levels: Int, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        if (!c.hasBoundedHeight || !c.hasBoundedWidth) {
            val ps = subcompose("free") { content(0) }.map { it.measure(c) }
            return@SubcomposeLayout layout(ps.maxOfOrNull { it.width } ?: c.minWidth, ps.maxOfOrNull { it.height } ?: c.minHeight) { ps.forEach { it.place(0, 0) } }
        }
        fun natural(key: String, level: Int, width: Int): IntSize {
            val ms = subcompose(key) { content(level) }
            val minW = ms.maxOfOrNull { m -> runCatching { m.minIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) }.getOrDefault(0) } ?: 0
            val h = ms.maxOfOrNull { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = width)).height } ?: 0
            return IntSize(minW, h)
        }
        fun fits(s: IntSize) = s.width <= c.maxWidth && s.height <= c.maxHeight
        var pick = levels - 1
        var need = IntSize.Zero
        for (l in 0 until levels) { need = natural("probe$l", l, c.maxWidth); if (fits(need)) { pick = l; break } }
        var scale = 1f
        if (!fits(need)) {
            // Smaller by what the width needs, then by the height at that wider width.
            val byWidth = minOf(1f, c.maxWidth.toFloat() / need.width.coerceAtLeast(1))
            val wider = (c.maxWidth / byWidth).toInt()
            val h = natural("scaled", pick, wider).height
            scale = minOf(byWidth, c.maxHeight.toFloat() / h.coerceAtLeast(1)).coerceIn(0.3f, 1f)
        }
        val fixed = androidx.compose.ui.unit.Constraints.fixed((c.maxWidth / scale).toInt(), (c.maxHeight / scale).toInt())
        val ps = subcompose("final$pick") { content(pick) }.map { it.measure(fixed) }
        layout(c.maxWidth, c.maxHeight) {
            ps.forEach { p -> if (scale == 1f) p.place(0, 0) else p.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) } }
        }
    }
}

/** How a part of the title and body sits: the title on top, the body below in what's left (all of it while measuring freely). */
@Composable
private fun TitleAndBody(title: (@Composable () -> Unit)?, gap: androidx.compose.ui.unit.Dp, body: @Composable () -> Unit) {
    // The body gets the whole remaining room as its minimum too, so a root column's `arrange` and spacers work.
    androidx.compose.ui.layout.Layout({ if (title != null) Box { title() }; Box(propagateMinConstraints = true) { body() } }) { ms, c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val t = if (title != null) ms[0].measure(loose) else null
        val used = t?.let { it.height + gap.roundToPx() } ?: 0
        val bc = if (c.hasBoundedHeight) androidx.compose.ui.unit.Constraints(c.minWidth, c.maxWidth, (c.minHeight - used).coerceAtLeast(0), (c.maxHeight - used).coerceAtLeast(0))
            else loose.copy(minWidth = c.minWidth)
        val b = ms.last().measure(bc)
        layout(maxOf(t?.width ?: 0, b.width, c.minWidth), (used + b.height).coerceAtLeast(c.minHeight)) { t?.place(0, 0); b.place(0, used) }
    }
}

/** A widget's card: its content, or what's wrong with it. Sizes: small (half width), wide, large, auto. */
@Composable
fun WidgetCard(w: HomeWidget, dir: File, loading: Boolean, error: String?, act: (JSONObject) -> Unit, menu: () -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    // On the home grid (fill) the size is the place it was given; elsewhere it follows the widget's size.
    val height = if (fill) Modifier else when (w.size) { "small", "wide" -> Modifier.height(164.dp); "large" -> Modifier.height(336.dp); else -> Modifier.heightIn(min = 72.dp) }
    // Without a tap of its own, a widget with a screen opens it.
    val tap = w.spec?.optJSONObject("tap") ?: w.spec?.optJSONObject("screen")?.let { JSONObject().put("screen", true) }
    // The card in the launcher's style (corners, border, glow, opacity); the widget draws inside it.
    // "background": "none" leaves the card out (the widget sits on the home background), a colour fills it.
    val back = w.spec?.optString("background").orEmpty()
    val frame = when (back) {
        "none", "transparent" -> Modifier
        "" -> Modifier.lookFrame(MaterialTheme.colorScheme.surfaceContainer)
        else -> Modifier.lookFrame(color(fill(back, w.state, null), MaterialTheme.colorScheme.surfaceContainer))
    }
    WidgetTime(w) { Box(
        modifier.then(height).then(frame)
            .combinedClickableCompat(onClick = { tap?.let(act) }, onLongClick = menu),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          // The card's shape picks the view (and what `when` and minWidth see); small cards get a slimmer margin.
          val fit = Fit(maxWidth.value, if (constraints.hasBoundedHeight) maxHeight.value else maxWidth.value)
          val pad = when { fit.shape == "tiny" -> 10.dp; minOf(fit.width, fit.height) < 170f -> 13.dp; else -> 17.dp }
          val view = viewFor(w.spec, fit)
          val ui = view?.optJSONObject("ui") ?: w.spec?.optJSONObject("ui")
          val term = w.terminal
          // "title" (and "icon"): the widget's name at the top, written the way the theme writes titles. A view can
          // leave it out ("title": false); tiny cards and the leanest level leave it out to make room.
          val title = w.spec?.optString("title").orEmpty().takeIf { it.isNotBlank() && term == null && view?.opt("title") != false }
          // A pet lives in the whole card, edges and all: it scratches and climbs them.
          if (w.error.isBlank() && term == null && ui?.optString("type") == "pet") PetNode(ui, w.id, Modifier.fillMaxSize())
          else Box(Modifier.fillMaxSize().padding(pad)) {
            if (w.error.isBlank() && term != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Only seen in pictures and lists: on the home screen a terminal widget runs its program.
                Text(term.optString("title").ifBlank { w.name }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$ " + term.optString("command"), style = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurface)
            } else if (w.error.isNotBlank() || ui == null) FitContent(1, Modifier.fillMaxSize()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(w.name, style = MaterialTheme.typography.labelLarge)
                    Text(w.error.ifBlank { "Nothing to show yet." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            } else FitContent(3, Modifier.fillMaxSize()) { level ->
                val head: (@Composable () -> Unit)? = title?.takeIf { fit.shape != "tiny" && level < 2 }?.let { t ->
                    { WidgetHeader(fill(t, w.state, null), w.spec?.optString("icon")?.takeIf { it.isNotBlank() }?.let { icon(fill(it, w.state, null)) }) }
                }
                TitleAndBody(head, if (fit.shape == "tiny") 6.dp else 10.dp) { Node(ui, Ctx(w, dir, null, act, level, fit), Modifier) }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.outline)
            else if (!error.isNullOrBlank()) Text("Refresh failed · showing saved data", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).padding(4.dp).semantics { contentDescription = "Refresh failed: $error. Showing saved data." })
          }
        }
    } }
}

/** The widget whose screen is open (its id), over the home screen. */
object WidgetScreens {
    var open by mutableStateOf<String?>(null)
}

/**
 * A widget's screen: a full-screen view of it with more than the tile has room for (a forecast behind a weather
 * tile), opened by a tap. Same widget: same state and data, which keep refreshing while it's open. Back and ✕ close it.
 */
@Composable
fun WidgetScreen(w: HomeWidget, dir: File, act: (JSONObject) -> Unit, close: () -> Unit, modifier: Modifier = Modifier) {
    val screen = w.spec?.optJSONObject("screen") ?: return
    androidx.activity.compose.BackHandler(onBack = close)
    WidgetTime(w) { Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    WidgetHeader(fill(screen.optString("title").ifBlank { w.spec.optString("title").ifBlank { w.name } }, w.state, null),
                        w.spec.optString("icon").takeIf { it.isNotBlank() }?.let { icon(fill(it, w.state, null)) })
                }
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                val fit = Fit(maxWidth.value, maxHeight.value)
                val ui = screen.optJSONObject("ui")
                if (ui == null || w.error.isNotBlank()) Text(w.error.ifBlank { "This screen has nothing to show yet." }, color = MaterialTheme.colorScheme.error)
                else Box(Modifier.fillMaxSize(), propagateMinConstraints = true) { Node(ui, Ctx(w, dir, null, act, 0, fit), Modifier) }
            }
        }
    } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

@Composable
internal fun color(name: String?, fallback: Color = MaterialTheme.colorScheme.onSurface): Color {
    val c = MaterialTheme.colorScheme
    return when (name?.trim()) {
        null, "" -> fallback
        "none", "transparent" -> Color.Transparent
        "primary" -> c.primary; "secondary" -> c.secondary; "tertiary" -> c.tertiary; "error" -> c.error
        "card" -> c.surfaceContainer
        "text" -> c.onSurface; "muted" -> c.onSurfaceVariant; "surface" -> c.surface; "container" -> c.primaryContainer
        "onPrimary" -> c.onPrimary; "onContainer" -> c.onPrimaryContainer; "outline" -> c.outline
        "accent" -> c.primary; "glow" -> c.tertiary
        in ToneNames -> Look.palette.tone(name.trim(), isDarkLook()) ?: fallback
        else -> runCatching { Color(android.graphics.Color.parseColor(name.trim())) }.getOrDefault(fallback)
    }
}

/** The common properties: padding, size, background and a tap. */
@Composable
private fun Modifier.common(n: JSONObject, ctx: Ctx): Modifier {
    var m = this.widgetAnim(n)
    if (n.has("width")) m = m.width(n.optInt("width").dp)
    if (n.has("height")) m = m.height(n.optInt("height").dp)
    if (n.has("background")) { val shape = RoundedCornerShape(n.optInt("corner", 12).dp); m = m.clip(shape).background(color(ctx.text(n.optString("background"))), shape) }
    n.optJSONObject("tap")?.let { a -> m = m.clickable { ctx.act(ctx.bind(a)) } }
    if (n.has("padding")) m = m.padding(n.optInt("padding").dp)
    return m
}

/** How optional a node is: 1 always shows, 2 goes at the leanest level, 3 goes first. Without `priority`, dividers and fixed spacers are 3, captions and charts 2. */
private fun priority(n: JSONObject): Int = n.optInt("priority", 0).takeIf { it in 1..3 } ?: when (n.optString("type")) {
    "divider" -> 3
    "spacer" -> if (n.has("size")) 3 else 1
    "chart" -> 2
    "text" -> if (n.optString("style") == "caption") 2 else 1
    else -> 1
}

private fun visible(n: JSONObject, ctx: Ctx): Boolean {
    if (priority(n) > 3 - ctx.level) return false
    if (!ctx.fit.matches(n)) return false
    if (!n.has("if")) return true
    val cond = n.optString("if").trim()
    return if (cond.startsWith("!")) !truthy(ctx.raw(cond.drop(1))) else truthy(ctx.raw(cond))
}

private fun children(n: JSONObject): List<JSONObject> = n.optJSONArray("children")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

@Composable
private fun Node(n: JSONObject, ctx: Ctx, modifier: Modifier) {
    if (!visible(n, ctx)) return
    val m = modifier.common(n, ctx)
    val gap = n.optInt("gap", 0).dp
    when (n.optString("type")) {
        "column" -> Column(
            if (n.optBoolean("scroll")) m.verticalScroll(rememberScrollState()) else m, horizontalAlignment = when (n.optString("align")) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start },
            verticalArrangement = vArrangement(n.optString("arrange"), gap),
        ) { children(n).forEach { c -> Node(c, ctx, if (c.has("weight") && visible(c, ctx)) Modifier.weight(c.optDouble("weight", 1.0).toFloat()) else Modifier) } }
        "row" -> Row(
            m, verticalAlignment = when (n.optString("align")) { "center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top },
            horizontalArrangement = hArrangement(n.optString("arrange"), gap),
        ) { children(n).forEach { c -> Node(c, ctx, if (c.has("weight") && visible(c, ctx)) Modifier.weight(c.optDouble("weight", 1.0).toFloat()) else Modifier) } }
        "box" -> Box(m, contentAlignment = boxAlign(n.optString("align"))) { children(n).forEach { Node(it, ctx, Modifier) } }
        "text" -> Text(
            ctx.text(n.optString("text")).let { t -> if (n.optString("style") == "label" && LocalLook.current.uppercase) t.uppercase(Locale.getDefault()) else t }, m,
            style = textStyle(n.optString("style")).let { s -> if (n.has("size")) s.copy(fontSize = n.optInt("size").sp, lineHeight = (n.optInt("size") * 1.25f).sp) else s }
                .let { s -> when (n.optString("font")) { "mono" -> s.copy(fontFamily = FontFamily.Monospace); "serif" -> s.copy(fontFamily = FontFamily.Serif); "sans" -> s.copy(fontFamily = FontFamily.SansSerif); else -> s } },
            softWrap = n.optBoolean("wrap", true),
            color = color(ctx.text(n.optString("color")), if (n.optString("style") in setOf("caption", "label")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface),
            fontWeight = if (n.optBoolean("bold")) FontWeight.SemiBold else null,
            textAlign = when (n.optString("align")) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> null },
            maxLines = n.optInt("lines", Int.MAX_VALUE).coerceAtLeast(1), overflow = TextOverflow.Ellipsis,
        )
        "icon" -> Icon(icon(ctx.text(n.optString("name"))), null, m.size(n.optInt("size", 20).dp), tint = color(ctx.text(n.optString("color")), MaterialTheme.colorScheme.onSurfaceVariant))
        "progress" -> {
            val v = number(ctx.raw(n.opt("value"))) ?: 0.0
            val max = number(ctx.raw(n.opt("max"))) ?: if (v > 1) 100.0 else 1.0
            val p = (v / max).toFloat().coerceIn(0f, 1f)
            val c = color(ctx.text(n.optString("color")), MaterialTheme.colorScheme.primary)
            if (n.optString("style") == "ring") ThemedRing(p, c, n.optInt("size", 48).dp, m)
            else ThemedBar(p, c, m)
        }
        "meter" -> {
            val v = number(ctx.raw(n.opt("value"))) ?: 0.0
            val max = number(ctx.raw(n.opt("max"))) ?: 100.0
            val p = if (max > 0) (v / max).toFloat() else 0f
            val shown = if (n.has("text")) ctx.text(n.optString("text")) else show(v.let { if (it % 1.0 == 0.0) it else Math.round(it * 10) / 10.0 }) + ctx.text(n.optString("unit", if (max == 100.0) "%" else ""))
            ThemedMeter(p, shown, ctx.text(n.optString("label")), ctx.text(n.optString("caption")),
                color(ctx.text(n.optString("color")), MaterialTheme.colorScheme.primary), n.optInt("size", 64).dp, m)
        }
        "stat" -> ThemedStat(ctx.text(n.optString("label")), ctx.text(n.optString("value")), ctx.text(n.optString("caption")),
            n.optString("icon").takeIf { it.isNotBlank() }?.let { icon(ctx.text(it)) }, color(ctx.text(n.optString("color")), MaterialTheme.colorScheme.primary), m)
        "header" -> WidgetHeader(ctx.text(n.optString("text")), n.optString("icon").takeIf { it.isNotBlank() }?.let { icon(ctx.text(it)) },
            color(ctx.text(n.optString("color")), MaterialTheme.colorScheme.primary), ctx.text(n.optString("trailing")), m)
        "chart" -> Chart(n, ctx, m)
        "list" -> {
            val items = ctx.raw(n.opt("items")) as? JSONArray ?: JSONArray()
            val template = n.optJSONObject("item") ?: JSONObject().put("type", "text").put("text", "{item}")
            Column(m, verticalArrangement = Arrangement.spacedBy(gap)) {
                // Leaner levels show fewer items, so a list shortens before anything shrinks.
                val max = n.optInt("max", 50).let { mx -> when (ctx.level) { 0 -> mx; 1 -> (mx * 0.6f).toInt(); else -> (mx * 0.35f).toInt() }.coerceAtLeast(1) }
                for (i in 0 until minOf(items.length(), max)) Node(template, ctx.with(items.opt(i) to i), Modifier)
            }
        }
        "clock" -> {
            val format = ctx.text(n.optString("format").ifBlank { "HH:mm" })
            val now = rememberWidgetTime(if (format.contains("s")) 1000L else 60_000L)
            Text(runCatching { SimpleDateFormat(format, Locale.getDefault()).format(now) }.getOrDefault(""), m,
                style = textStyle(n.optString("style").ifBlank { "display" }), color = color(ctx.text(n.optString("color"))))
        }
        "canvas" -> CanvasNode(n, ctx.w.state, ctx.item, ctx.dir, ctx.act, m)
        "pet" -> PetNode(n, ctx.w.id, if (n.has("height") || n.has("weight")) m.fillMaxWidth() else m.fillMaxWidth().aspectRatio(2f))
        "image" -> {
            // `frames`: pictures shown in turn, `fps` a second (default 6), for a picture that moves.
            val frames = n.optJSONArray("frames")?.let { fr -> (0 until fr.length()).map { fr.optString(it) } }.orEmpty()
            val time = LocalWidgetTime.current
            val src = if (frames.isEmpty()) ctx.text(n.optString("src"))
                else ctx.text(frames[((time?.value ?: 0f) * n.optDouble("fps", 6.0)).toInt().coerceAtLeast(0) % frames.size])
            val file = File(ctx.dir, src).takeIf { src.isNotBlank() && it.canonicalPath.startsWith(ctx.dir.canonicalPath) }
            val bitmap = remember(file?.path, file?.lastModified()) { file?.let { f -> WidgetImages.get(f) } }
            if (bitmap != null) {
                val size = if (n.has("size")) Modifier.size(n.optInt("size").dp) else Modifier
                Image(bitmap, null, m.then(size).clip(RoundedCornerShape(n.optInt("corner", 12).dp)), contentScale = ContentScale.Crop)
            }
        }
        "button" -> {
            val a = ctx.bind(n.optJSONObject("action") ?: JSONObject())
            val label = ctx.text(n.optString("text"))
            val pad = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            when (n.optString("style")) {
                "filled" -> Button(onClick = { ctx.act(a) }, modifier = m, contentPadding = pad) { Text(label) }
                "text" -> TextButton(onClick = { ctx.act(a) }, modifier = m, contentPadding = pad) { Text(label) }
                else -> FilledTonalButton(onClick = { ctx.act(a) }, modifier = m, contentPadding = pad) { Text(label) }
            }
        }
        "spacer" -> Spacer(if (n.has("size")) m.size(n.optInt("size").dp) else m)
        "divider" -> HorizontalDivider(m, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

private fun hArrangement(name: String, gap: androidx.compose.ui.unit.Dp): Arrangement.Horizontal = when (name) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    "between" -> Arrangement.SpaceBetween
    else -> Arrangement.spacedBy(gap)
}
private fun vArrangement(name: String, gap: androidx.compose.ui.unit.Dp): Arrangement.Vertical = when (name) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
    "between" -> Arrangement.SpaceBetween
    else -> Arrangement.spacedBy(gap)
}

private fun boxAlign(name: String) = when (name) {
    "top" -> Alignment.TopCenter; "bottom" -> Alignment.BottomCenter; "start" -> Alignment.CenterStart; "end" -> Alignment.CenterEnd
    "topStart" -> Alignment.TopStart; "topEnd" -> Alignment.TopEnd; "bottomStart" -> Alignment.BottomStart; "bottomEnd" -> Alignment.BottomEnd
    "center" -> Alignment.Center; else -> Alignment.TopStart
}

@Composable
private fun textStyle(name: String): TextStyle = with(MaterialTheme.typography) {
    when (name) {
        "display" -> displaySmall.copy(fontWeight = FontWeight.Light); "headline" -> headlineSmall; "title" -> titleMedium
        "label" -> labelMedium; "caption" -> bodySmall; else -> bodyMedium
    }
}

/** A small line or bar chart of numbers. */
@Composable
private fun Chart(n: JSONObject, ctx: Ctx, m: Modifier) {
    val values = (ctx.raw(n.opt("values")) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { number(a.opt(it)) } }.orEmpty()
    if (values.isEmpty()) return
    val c = color(ctx.text(n.optString("color")), MaterialTheme.colorScheme.primary)
    val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val (glow, square) = chartLook()
    val area = n.optBoolean("area", false)
    Canvas(m.fillMaxWidth().height(n.optInt("height", 48).dp)) {
        if (n.optString("kind") == "bars") {
            val w = size.width / values.size
            val base = minOf(lo, 0.0); val range = (hi - base).takeIf { it > 0 } ?: 1.0
            values.forEachIndexed { i, v ->
                val h = ((v - base) / range * size.height).toFloat().coerceAtLeast(2f)
                drawRoundRect(c.copy(alpha = if (i == values.lastIndex) 1f else 0.45f), Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h),
                    androidx.compose.ui.geometry.CornerRadius(if (square) 0f else w * 0.2f))
            }
        } else {
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = if (values.size == 1) size.width / 2 else i * size.width / (values.size - 1)
                val y = size.height - ((v - lo) / span * (size.height - 4f)).toFloat() - 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (area && values.size > 1) {
                val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.copy(alpha = 0.32f), c.copy(alpha = 0.02f))))
            }
            if (glow) for (i in 1..3) drawPath(path, c.copy(alpha = 0.12f / i), style = Stroke((2 + i * 3).dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawPath(path, c, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** The icons a widget can name. */
internal val WidgetIcons: Map<String, ImageVector> by lazy { mapOf(
    "add" to Icons.Outlined.Add, "alarm" to Icons.Outlined.Alarm, "battery" to Icons.Outlined.BatteryFull, "bolt" to Icons.Outlined.Bolt,
    "book" to Icons.Outlined.MenuBook, "calendar" to Icons.Outlined.CalendarMonth, "call" to Icons.Outlined.Call, "camera" to Icons.Outlined.PhotoCamera,
    "check" to Icons.Outlined.CheckCircle, "circle" to Icons.Outlined.Circle, "close" to Icons.Outlined.Close, "cloud" to Icons.Outlined.Cloud, "code" to Icons.Outlined.Code,
    "coffee" to Icons.Outlined.Coffee, "download" to Icons.Outlined.Download, "edit" to Icons.Outlined.Edit, "favorite" to Icons.Outlined.FavoriteBorder,
    "fitness" to Icons.Outlined.FitnessCenter, "flag" to Icons.Outlined.Flag, "folder" to Icons.Outlined.Folder, "home" to Icons.Outlined.Home,
    "language" to Icons.Outlined.Language, "light" to Icons.Outlined.Lightbulb, "location" to Icons.Outlined.Place, "lock" to Icons.Outlined.Lock,
    "mail" to Icons.Outlined.Mail, "map" to Icons.Outlined.Map, "mic" to Icons.Outlined.Mic, "moon" to Icons.Outlined.DarkMode,
    "music" to Icons.Outlined.MusicNote, "notes" to Icons.Outlined.StickyNote2, "pause" to Icons.Outlined.Pause, "person" to Icons.Outlined.Person,
    "photo" to Icons.Outlined.Image, "play" to Icons.Outlined.PlayArrow, "refresh" to Icons.Outlined.Refresh, "rain" to Icons.Outlined.WaterDrop,
    "search" to Icons.Outlined.Search, "settings" to Icons.Outlined.Settings, "shopping" to Icons.Outlined.ShoppingCart, "skip" to Icons.Outlined.SkipNext,
    "snow" to Icons.Outlined.AcUnit, "star" to Icons.Outlined.StarOutline, "sun" to Icons.Outlined.WbSunny, "terminal" to Icons.Outlined.Terminal,
    "timer" to Icons.Outlined.Timer, "todo" to Icons.Outlined.Checklist, "trending" to Icons.Outlined.TrendingUp, "wallet" to Icons.Outlined.AccountBalanceWallet,
    "water" to Icons.Outlined.WaterDrop, "wifi" to Icons.Outlined.Wifi,
    "memory" to Icons.Outlined.Memory, "storage" to Icons.Outlined.Storage, "speed" to Icons.Outlined.Speed, "phone" to Icons.Outlined.PhoneAndroid,
    "charging" to Icons.Outlined.BatteryChargingFull, "today" to Icons.Outlined.Today, "news" to Icons.Outlined.Newspaper, "thermometer" to Icons.Outlined.Thermostat,
    "chart" to Icons.Outlined.Insights, "schedule" to Icons.Outlined.Schedule, "pets" to Icons.Outlined.Pets,
) }

private fun icon(name: String): ImageVector = WidgetIcons[name.trim().lowercase()] ?: Icons.Outlined.Circle

/** Decoded widget pictures by file and time, shared by the layouts a widget tries while fitting. */
internal object WidgetImages {
    private val cache = android.util.LruCache<String, ImageBitmap>(48)
    fun get(f: File): ImageBitmap? {
        val key = f.path + "@" + f.lastModified()
        return cache.get(key) ?: runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()?.also { cache.put(key, it) }
    }
}

// ---------- checking ----------

private val ColourNames = setOf("primary", "accent", "secondary", "tertiary", "glow", "error", "text", "muted", "surface", "container", "card",
    "outline", "onPrimary", "onContainer", "none", "transparent")
private val CommonKeys = setOf("type", "if", "anim", "padding", "background", "corner", "width", "height", "weight", "tap", "priority", "when", "minWidth", "maxWidth", "minHeight", "maxHeight")
private val NodeKeys = mapOf(
    "column" to setOf("children", "gap", "align", "arrange", "scroll"), "row" to setOf("children", "gap", "align", "arrange"),
    "box" to setOf("children", "align"),
    "text" to setOf("text", "style", "color", "bold", "align", "lines", "size", "font", "wrap"),
    "icon" to setOf("name", "color", "size"),
    "progress" to setOf("value", "max", "style", "color", "size"),
    "meter" to setOf("value", "max", "text", "unit", "label", "caption", "color", "size"),
    "stat" to setOf("label", "value", "caption", "icon", "color"),
    "header" to setOf("text", "icon", "color", "trailing"),
    "chart" to setOf("values", "kind", "color", "area"),
    "list" to setOf("items", "item", "max", "gap"),
    "clock" to setOf("format", "style", "color"),
    "image" to setOf("src", "size", "corner", "frames", "fps"),
    "canvas" to setOf("view", "fit", "shapes"),
    "pet" to setOf("animal", "coat"),
    "button" to setOf("text", "action", "style"),
    "spacer" to setOf("size"), "divider" to emptySet(),
)
private val TopKeys = setOf("name", "size", "refresh", "data", "state", "tap", "ui", "terminal", "description", "builtin", "title", "icon", "background", "version", "views", "screen")
private val TerminalKeys = setOf("command", "script", "title", "fontSize", "background", "text", "prompt", "accent", "corner", "opacity", "font")

private fun knownColour(v: String) = v.isBlank() || v.contains('{') || v.trim() in ColourNames || v.trim() in ToneNames ||
    runCatching { android.graphics.Color.parseColor(v.trim()) }.isSuccess

/** Programs a terminal widget is for: ones the user types into or that draw their own live screen. */
private val InteractivePrograms = setOf("opencode", "claude", "codex", "htop", "btop", "top", "bash", "sh", "ash", "zsh", "fish", "vim", "vi", "nvim", "nano", "micro", "hx",
    "mc", "ranger", "nnn", "lf", "lazygit", "tig", "tmux", "screen", "ssh", "mosh", "python3", "python", "node", "irb", "lua", "sqlite3", "psql", "mysql", "less", "man",
    "w3m", "lynx", "ncdu", "nmtui", "cmus", "ncmpcpp", "weechat", "irssi", "newsboat", "calcurse", "bc", "gdu", "glances", "aider", "gemini", "crush", "goose")

/** Whether a terminal command runs one of those (after any `cd … &&`), on its own: `python3 cat.py` or `sh ./draw.sh` is a script, not a program to use. */
private fun interactive(command: String): Boolean {
    val parts = command.split("&&", "||", ";").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("cd ") && it != "cd" }
    return parts.isEmpty() || parts.all { p ->
        val words = p.split(Regex("\\s+")).dropWhile { it == "exec" || it.contains('=') }
        val prog = words.firstOrNull()?.substringAfterLast('/') ?: return@all true
        prog in InteractivePrograms && !(prog in setOf("sh", "bash", "ash", "zsh", "python3", "python", "node", "lua") && words.drop(1).any { !it.startsWith("-") })
    }
}

/** What in a widget.json can't be drawn as written: unknown nodes, keys, colours, icons, styles, actions or images. */
fun lintWidget(spec: JSONObject, dir: File): List<String> {
    val out = mutableListOf<String>()
    spec.keys().forEach { if (it !in TopKeys) out += "\"$it\" isn't a widget.json key, so it's ignored" }
    val size = spec.optString("size")
    if (size.isNotBlank() && size !in setOf("small", "wide", "large", "auto")) out += "size \"$size\" isn't small, wide, large or auto"
    if (spec.has("refresh") && spec.optInt("refresh", 900) < 60) out += "refresh is at least 60 seconds; ${spec.opt("refresh")} counts as 60"
    spec.optJSONObject("terminal")?.let { t ->
        t.keys().forEach { if (it !in TerminalKeys) out += "terminal: \"$it\" isn't a terminal key, so it's ignored" }
        listOf("background", "text", "prompt", "accent").forEach { k -> t.optString(k).takeIf { !knownColour(it) }?.let { out += "terminal: $k colour \"$it\" isn't known" } }
        if (!t.optBoolean("script") && !interactive(t.optString("command"))) out += "terminal: \"${t.optString("command")}\" doesn't look like an interactive program (an agent, a shell, htop, an editor). " +
            "A terminal widget is a real terminal: every tap opens the keyboard and its key bar, and it shows the terminal's bars. " +
            "For a picture, a character, a pet, an animation or a game, make a drawn widget with a \"canvas\" and \"anim\" (Pictures and motion in WIDGETS.md) and buttons or taps for its actions. " +
            "If the user really wants this script in a terminal, set \"script\": true in \"terminal\""
        return out
    }
    fun action(where: String, a: Any?) {
        val o = a as? JSONObject ?: run { if (a != null) out += "$where: an action must be an object like {\"open\": \"…\"}"; return }
        if (o.keys().asSequence().none { it in setOf("open", "run", "set", "toggle", "refresh", "screen", "close") }) out += "$where: the action has none of open, run, set, toggle, refresh, screen or close"
        if (o.has("for") && o.optJSONObject("set") == null) out += "$where: \"for\" goes with \"set\": the keys set go back after that many seconds"
        if (o.optBoolean("screen") && spec.optJSONObject("screen") == null) out += "$where: {\"screen\": true} opens the widget's screen, but widget.json has no \"screen\""
    }
    action("tap", spec.opt("tap"))
    spec.optString("background").takeIf { spec.has("background") && it !in setOf("none", "transparent") && !knownColour(it) }?.let { out += "background \"$it\" isn't none or a known colour" }
    spec.optString("icon").takeIf { it.isNotBlank() && !it.contains('{') && it.trim().lowercase() !in WidgetIcons }?.let { out += "there's no icon \"$it\" (a circle is drawn)" }
    fun walk(n: JSONObject, path: String) {
        val type = n.optString("type")
        val keys = NodeKeys[type]
        if (type == "terminal") { out += "$path: a terminal isn't a node: put \"terminal\": {\"command\": …} at the top of widget.json in place of \"ui\""; return }
        if (keys == null) { out += "$path: unknown node type \"$type\" (it isn't drawn)"; return }
        val here = "$path ($type)"
        n.keys().forEach { if (it !in CommonKeys && it !in keys) out += "$here: \"$it\" isn't a $type property, so it's ignored" }
        listOf("color", "background").forEach { k -> n.optString(k).takeIf { n.has(k) && !knownColour(it) }?.let { out += "$here: colour \"$it\" isn't known (use a named colour or #RRGGBB)" } }
        if (type in setOf("stat", "header")) n.optString("icon").takeIf { it.isNotBlank() && !it.contains('{') && it.trim().lowercase() !in WidgetIcons }?.let { out += "$here: there's no icon \"$it\" (a circle is drawn)" }
        if (type == "icon") n.optString("name").takeIf { !it.contains('{') && it.trim().lowercase() !in WidgetIcons }?.let { out += "$here: there's no icon \"$it\" (a circle is drawn)" }
        if (type == "text" && n.has("style") && n.optString("style") !in setOf("display", "headline", "title", "body", "label", "caption")) out += "$here: style \"${n.optString("style")}\" isn't known"
        out += lintAnim(n.opt("anim"), here)
        if (type == "canvas") out += lintCanvas(n, here, dir, ::knownColour)
        if (type == "image" && n.has("frames")) (n.optJSONArray("frames")?.let { fr -> (0 until fr.length()).map { fr.optString(it) } }.orEmpty())
            .filter { it.isNotBlank() && !it.contains('{') && !File(dir, it).isFile }.forEach { out += "$here: there's no image \"$it\" in the widget's folder" }
        if (type == "image") n.optString("src").takeIf { it.isNotBlank() && !it.contains('{') && !File(dir, it).isFile }?.let { out += "$here: there's no image \"$it\" in the widget's folder" }
        if (n.has("tap")) action("$here tap", n.opt("tap"))
        if (type == "button") action("$here action", n.opt("action"))
        n.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { walk(it, "$path.children[$i]") } ?: run { out += "$path.children[$i] isn't a node object" } }
        n.optJSONObject("item")?.let { walk(it, "$path.item") }
    }
    spec.optJSONObject("ui")?.let { walk(it, "ui") }
    spec.opt("screen")?.let { sc ->
        val o = sc as? JSONObject
        if (o == null) out += "screen must be an object like {\"title\": \"…\", \"ui\": {…}}"
        else {
            o.keys().forEach { if (it !in setOf("title", "ui")) out += "screen: \"$it\" isn't a screen key (title, ui), so it's ignored" }
            o.optJSONObject("ui")?.let { walk(it, "screen.ui") } ?: run { out += "screen has no ui" }
        }
    }
    spec.optJSONArray("views")?.let { vs ->
        for (i in 0 until vs.length()) {
            val v = vs.optJSONObject(i)
            if (v == null) { out += "views[$i] isn't an object"; continue }
            v.keys().forEach { if (it !in setOf("when", "minWidth", "maxWidth", "minHeight", "maxHeight", "ui", "title")) out += "views[$i]: \"$it\" isn't a view key, so it's ignored" }
            v.optJSONObject("ui")?.let { walk(it, "views[$i].ui") } ?: run { out += "views[$i] has no ui" }
        }
    }
    fun shapes(where: String, n: JSONObject) {
        val v = n.opt("when") ?: return
        val list = if (v is JSONArray) (0 until v.length()).map { v.optString(it) } else v.toString().split(' ', ',', '|').filter { it.isNotBlank() }
        list.filter { it.trim() !in FitShapes }.forEach { out += "$where: when \"$it\" isn't one of ${FitShapes.joinToString()}" }
    }
    fun walkWhen(n: JSONObject, path: String) {
        shapes(path, n)
        n.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { walkWhen(it, "$path.children[$i]") } }
        n.optJSONObject("item")?.let { walkWhen(it, "$path.item") }
    }
    spec.optJSONObject("ui")?.let { walkWhen(it, "ui") }
    spec.optJSONArray("views")?.let { vs -> for (i in 0 until vs.length()) vs.optJSONObject(i)?.let { v -> shapes("views[$i]", v); v.optJSONObject("ui")?.let { walkWhen(it, "views[$i].ui") } } }
    return out
}

// ---------- pictures for `widget check` ----------

private class CheckJob(val n: Int, val w: HomeWidget, val width: Float, val height: Float, val where: String, val sheet: Boolean = false, val screen: Boolean = false,
                       val times: List<Float> = emptyList(), val cell: Pair<Float, Float> = 0f to 0f)

/** `widget check <id> all`: rows of sizes in cells (width to height), from tiny to large, in one picture. */
private val SheetRows = listOf(listOf(2 to 2, 2 to 4, 4 to 4), listOf(8 to 2), listOf(4 to 2, 4 to 3), listOf(8 to 4), listOf(3 to 6, 5 to 6))

/**
 * `widget check` asks for a picture by leaving `.check` in the widget's folder (holding a size, or nothing for
 * the size it has on the home screen). This draws the widget out of sight with its current data, in the
 * launcher's look, and saves `.check.png` and `.check.txt` (what can't be drawn as written) next to it.
 * It works while Hatchlybox is on screen. `cells` gives the widget's size in home-grid cells.
 */
@Composable
fun WidgetChecks(engine: WidgetEngine, linux: Linux, cells: (HomeWidget, String) -> Pair<Pair<Int, Int>, String>) {
    val root = remember { linux.hostFile(WIDGETS_DIR) }
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    var job by remember { mutableStateOf<CheckJob?>(null) }
    var count by remember { mutableIntStateOf(0) }
    val dark = isDarkLook()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            if (job != null || !linux.hasBasics || !Gold.front) continue
            val req = withContext(Dispatchers.IO) {
                root.listFiles().orEmpty().firstOrNull { File(it, ".check").isFile }?.let { d ->
                    val f = File(d, ".check"); val size = runCatching { f.readText().trim() }.getOrDefault(""); f.delete(); d to size
                }
            } ?: continue
            val (dir, size) = req
            engine.reload()
            val w = engine.widgets.firstOrNull { it.id == dir.name }
            if (w == null || w.spec == null) {
                withContext(Dispatchers.IO) { runCatching { File(dir, ".check.txt").writeText("No picture: ${w?.error?.ifBlank { null } ?: "widget.json didn't load"}\n") } }
                continue
            }
            PhoneInfo.update(context)
            val cell = screenWidth / GRID_COLS.toFloat()
            if (size == "screen") {
                if (w.spec.optJSONObject("screen") == null) {
                    withContext(Dispatchers.IO) { runCatching { File(dir, ".check.txt").writeText("No picture: widget.json has no \"screen\"\n" + lintWidget(w.spec, dir).joinToString("") { "! $it\n" }) } }
                    continue
                }
                job = CheckJob(++count, w, screenWidth.toFloat(), screenHeight.toFloat(), "its screen, as a tap opens it", screen = true)
            } else if (size == "motion") {
                // Six moments across the longest motion, side by side, so a check can see what moves and how far.
                val (c, where) = cells(w, "")
                val (parts, longest) = motionOf(w.spec)
                val period = longest.coerceIn(1f, 6f)
                val cw = c.first * cell - 10f; val ch = c.second * cell - 10f
                job = CheckJob(++count, w, cw * 3 + 20f, (ch + 16f) * 2 + 10f, "six moments over ${"%.1f".format(period)} s, $where; $parts moving part" + (if (parts == 1) "" else "s"),
                    times = (0 until 6).map { it * period / 6f }, cell = cw to ch)
            } else if (size == "all") {
                val label = 16f
                job = CheckJob(++count, w, screenWidth.toFloat(), SheetRows.sumOf { r -> r.maxOf { it.second } * cell.toDouble() + label }.toFloat(), "nine sizes, from 2×2 to 8×6 cells", sheet = true)
            } else {
                val (c, where) = cells(w, size)
                job = CheckJob(++count, w, c.first * cell - 10f, c.second * cell - 10f, where)
            }
        }
    }
    val j = job ?: return
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    // Recorded, never drawn: nothing shows on screen.
    Box(Modifier.size(0.dp)) {
        Box(
            Modifier.requiredSize(j.width.dp, j.height.dp)
                .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
        ) {
            if (j.screen) WidgetScreen(j.w, File(root, j.w.id), act = {}, close = {}, modifier = Modifier.fillMaxSize())
            else if (j.times.isNotEmpty()) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(5.dp)) {
                j.times.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { t ->
                            Column(Modifier.width(j.cell.first.dp)) {
                                Text("%.2f s".format(t), Modifier.height(16.dp).padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val fixed = remember(t) { mutableFloatStateOf(t) }
                                CompositionLocalProvider(LocalWidgetTime provides fixed) {
                                    WidgetCard(j.w, File(root, j.w.id), false, null, act = {}, menu = {}, modifier = Modifier.size(j.cell.first.dp, j.cell.second.dp), fill = true)
                                }
                            }
                        }
                    }
                }
            }
            else if (!j.sheet) WidgetCard(j.w, File(root, j.w.id), false, null, act = {}, menu = {}, modifier = Modifier.fillMaxSize(), fill = true)
            else Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                val cell = screenWidth / GRID_COLS.toFloat()
                SheetRows.forEach { row ->
                    Row {
                        row.forEach { (cw, ch) ->
                            Column(Modifier.width((cw * cell).dp)) {
                                Text("$cw×$ch · ${Fit(cw * cell - 10f, ch * cell - 10f).shape}", Modifier.height(16.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.size((cw * cell).dp, (ch * cell).dp).padding(5.dp)) {
                                    WidgetCard(j.w, File(root, j.w.id), false, null, act = {}, menu = {}, modifier = Modifier.fillMaxSize(), fill = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(j.n) {
        delay(900) // images decode, a clock ticks, the layout settles
        val bitmap = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
        withContext(Dispatchers.IO) {
            val dir = File(root, j.w.id)
            val issues = j.w.spec?.let { lintWidget(it, dir) }.orEmpty()
            val text = buildString {
                if (bitmap != null && bitmap.width > 1) {
                    // Small enough to look at: at most 720 pixels wide.
                    val scale = minOf(1f, 720f / bitmap.width)
                    val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                    File(dir, ".check.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    append("picture: $WIDGETS_DIR/${j.w.id}/.check.png (${j.width.toInt()}×${j.height.toInt()} dp, ${j.where}, ${if (dark) "dark" else "light"})\n")
                } else append("No picture: Hatchlybox isn't on screen, so it can't draw the widget. The checks still ran.\n")
                if (j.w.terminal != null) append("A terminal widget: the picture shows its frame; the program itself runs on the home screen. Every tap on it opens the keyboard.\n")
                else motionOf(j.w.spec).first.let { parts ->
                    if (parts > 0) append("moves: $parts part" + (if (parts == 1) "" else "s") + " (see them move with: widget check ${j.w.id} motion)\n")
                    else if (Regex("(?i)\\b(pet|cat|kitten|dog|puppy|fish|aquarium|bird|owl|bunny|rabbit|hamster|creature|character|mascot|animal|fire|rain|snow|ocean|waves?|scene|animat\\w*|alive|moving)\\b").containsMatchIn(j.w.name + " " + j.w.spec?.optString("description")))
                        append("note: nothing in this widget moves, but it sounds like something that should (a pet, a scene, an animation): give its parts \"anim\" (Pictures and motion in WIDGETS.md)\n")
                }
                issues.forEach { append("! ").append(it).append('\n') }
            }
            runCatching { File(dir, ".check.txt").writeText(text) }
        }
        job = null
    }
}
