package com.hatchlybox.app

import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Description
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// The home screen: pages of widgets placed freely on a grid (long-press one to move or resize it, long-press
// empty space to add widgets and pages), the create box pinned to the bottom, and the library that follows
// the finger up.

/** Columns of the home grid; rows are square cells, as many as fit. */
const val GRID_COLS = 8

/** A widget on a page. kind: clock, terminal, agent (ref = its program), apps or custom (ref = its folder in /root/widgets). Grid cells. */
class Placed(val id: String, val kind: String, val ref: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("ref", ref).put("x", x).put("y", y).put("w", w).put("h", h)
    fun at(x: Int, y: Int, w: Int, h: Int) = Placed(id, kind, ref, x, y, w, h)
    fun overlaps(o: Placed) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    companion object {
        fun of(o: JSONObject) = Placed(o.optString("id"), o.optString("kind"), o.optString("ref"), o.optInt("x").coerceIn(0, GRID_COLS - 1),
            o.optInt("y").coerceAtLeast(0), o.optInt("w", 4).coerceIn(1, GRID_COLS), o.optInt("h", 3).coerceAtLeast(1))
    }
}

/** The pages and what's on them, saved in prefs "launcher". Created with the ViewModel, on the main thread. */
class LauncherLayout(private val prefs: SharedPreferences, linux: Linux) {
    val pages = mutableStateListOf<SnapshotStateList<Placed>>()
    /** The rows that fit on this screen, measured by the home screen; new widgets are placed within them. */
    var rows = 14
        set(v) { field = v; settleBottom() }
    private var counter = 0
    /** Widgets of the first page that go to the bottom, stacked up from just above the create box (the first
     *  lowest), once the rows are known. */
    private var bottom: String? = prefs.getString("bottom", null)

    init {
        val saved = runCatching { JSONObject(prefs.getString("layout", "") ?: "").getJSONArray("pages") }.getOrNull()
        if (saved != null && saved.length() > 0) {
            for (i in 0 until saved.length()) pages += mutableStateListOf<Placed>().apply {
                addAll(saved.optJSONArray(i)?.objects().orEmpty().map(Placed::of).filter { it.id.isNotBlank() })
            }
        } else {
            // First install: clock at the top, apps above Create. Saved layouts are preserved.
            val apps = Placed(newId(), "apps", "", 0, 10, 8, 2)
            val clock = Placed(newId(), "clock", "", 0, 0, 8, 3)
            pages += mutableStateListOf(clock, apps)
            bottom = apps.id; prefs.edit().putString("bottom", bottom).apply()
            // Widgets from the old widgets page stay, on a page of their own.
            val old = runCatching {
                linux.hostFile(WIDGETS_DIR).listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "today" && File(it, "widget.json").isFile }.map { it.name }.sorted()
            }.getOrDefault(emptyList())
            if (old.isNotEmpty()) {
                val page = mutableStateListOf<Placed>()
                old.forEach { ref -> page += freeSpot(page, Placed(newId(), "custom", ref, 0, 0, 8, 4)) }
                pages += page
            }
            save()
        }
    }

    private fun newId() = "w" + System.currentTimeMillis().toString(36) + (counter++)

    /**
     * A new widget's widget.json is in: its tile takes the size it asks for (small, wide, tall, large), where it is
     * if there's room, else in the nearest free place on its page; with no room it keeps the size it has.
     */
    fun sizeNew(ref: String, size: String) {
        if (ref !in unsized) return
        unsized -= ref; prefs.edit().putStringSet("unsized", unsized.toSet()).apply()
        val (w, h) = defaultSize("custom", size)
        pages.forEachIndexed { page, list ->
            val p = list.firstOrNull { it.kind == "custom" && it.ref == ref } ?: return@forEachIndexed
            if (p.w == w && p.h == h) return
            val want = p.at(p.x.coerceAtMost(GRID_COLS - w), p.y.coerceAtMost((rows - h).coerceAtLeast(0)), w, h)
            val spot = want.takeIf { c -> list.none { it.id != p.id && it.overlaps(c) } } ?: nearest(list, want)
            spot?.let { update(page, it) }
            return
        }
    }

    /** Moves the widgets waiting for the bottom there, stacked up from the rows above the create box (about two). */
    private fun settleBottom() {
        val ids = bottom?.split(',')?.filter { it.isNotBlank() } ?: return
        bottom = null; prefs.edit().remove("bottom").apply()
        val list = pages.firstOrNull() ?: return
        var floor = rows - 3
        ids.forEach { id ->
            val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@forEach
            val p = list[i]
            val moved = p.at(p.x, (floor - p.h).coerceAtLeast(0), p.w, p.h)
            if (list.none { it.id !in ids && it.overlaps(moved) }) { list[i] = moved; floor = moved.y }
        }
        save()
    }

    private fun save() {
        prefs.edit().putString("layout", JSONObject().put("pages", JSONArray(pages.map { p -> JSONArray(p.map { it.json() }) })).toString()).apply()
    }

    /** The first place on the page where it fits without covering anything, top to bottom; else the top. */
    private fun freeSpot(page: List<Placed>, p: Placed): Placed = fits(page, p) ?: p.at(0, 0, p.w, p.h)

    /** The first free place on the page that holds it, top to bottom, or null when the page is full. */
    private fun fits(page: List<Placed>, p: Placed): Placed? {
        for (y in 0..(rows - p.h)) for (x in 0..GRID_COLS - p.w) {
            val c = p.at(x, y, p.w, p.h)
            if (page.none { it.overlaps(c) }) return c
        }
        return null
    }

    /** The free place nearest to where it was dropped, or null when the page is full. */
    private fun nearest(page: List<Placed>, p: Placed): Placed? {
        var best: Placed? = null; var dist = Int.MAX_VALUE
        for (y in 0..(rows - p.h)) for (x in 0..GRID_COLS - p.w) {
            val c = p.at(x, y, p.w, p.h)
            val d = (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y)
            if (d < dist && page.none { it.id != p.id && it.overlaps(c) }) { best = c; dist = d }
        }
        return best
    }

    fun defaultSize(kind: String, size: String = ""): Pair<Int, Int> = when (kind) {
        "clock" -> 8 to 3; "terminal" -> 8 to 5; "agent" -> 8 to 7; "apps" -> 8 to 2; "analog" -> 4 to 4; "calendar" -> 4 to 4; "site", "app" -> 2 to 2
        else -> when (size) { "small" -> 4 to 4; "wide" -> 8 to 4; "tall" -> 8 to 5; "large" -> 8 to 7; else -> 8 to 3 }
    }

    /** Widgets placed before their agent wrote widget.json: they take the size it asks for once it's there. */
    private val unsized = (prefs.getStringSet("unsized", emptySet()) ?: emptySet()).toMutableSet()

    /** Adds it to the page in the first free place; when the page is full, to a new page of its own. Returns the page it went to. */
    fun add(page: Int, kind: String, ref: String = "", size: String = "", sizeLater: Boolean = false): Int {
        val (w, h) = defaultSize(kind, size)
        val fresh = Placed(newId(), kind, ref, 0, 0, w, h)
        if (sizeLater) { unsized += ref; prefs.edit().putStringSet("unsized", unsized.toSet()).apply() }
        val list = pages.getOrNull(page)
        val spot = list?.let { fits(it, fresh) }
        if (list != null && spot != null) { list += spot; save(); return page }
        pages += mutableStateListOf(fresh); save()
        return pages.lastIndex
    }

    /** The widget being carried between pages: the page and place it was picked up from, to go back to. */
    private var carry: Pair<Int, Placed>? = null

    /**
     * Moves a widget to another page (to == pages.size: a new one), as near to where it was as there's room for.
     * On a full page it hovers over the others, so it can be carried on to the next page; dropped there,
     * it goes back (drop). Its terminal keeps running. Returns the page it went to, or null when nothing moved.
     */
    fun moveToPage(from: Int, id: String, to: Int, at: Placed): Int? {
        val list = pages.getOrNull(from) ?: return null
        if (to < 0 || to > pages.size || to == from) return null
        val was = list.firstOrNull { it.id == id } ?: return null
        if (carry?.second?.id != id) carry = from to was
        if (to == pages.size) pages += mutableStateListOf<Placed>()
        val spot = nearest(pages[to], at) ?: at.at(at.x, at.y.coerceAtMost((rows - at.h).coerceAtLeast(0)), at.w, at.h)
        list.removeAll { it.id == id }
        pages[to] += spot
        // A page left empty at the end, made on the way (not the one it came from), goes again.
        if (list.isEmpty() && from == pages.lastIndex && from != carry?.first && to < from) pages.removeAt(from)
        save()
        return to
    }

    /**
     * The carried widget let go on this page: it stays where it is if that's free, else in the nearest free
     * place; on a page with no room, it goes back where it was picked up. Returns that page when it went back.
     */
    fun drop(page: Int, id: String): Int? {
        val (home, was) = carry?.takeIf { it.second.id == id } ?: return null
        carry = null
        val list = pages.getOrNull(page) ?: return null
        val p = list.firstOrNull { it.id == id } ?: return null
        if (list.none { it.id != id && it.overlaps(p) }) return null
        nearest(list, p)?.let { update(page, it); return null }
        list.removeAll { it.id == id }
        val back = home.coerceIn(0, pages.lastIndex)
        pages[back] += (if (pages[back].none { it.overlaps(was) }) was else nearest(pages[back], was) ?: was)
        save()
        return back
    }

    /** Forgets the carried widget (it was removed). */
    fun forget() { carry = null }

    /** Agent terminals placed as widget folders (before they were built in) become built-in agent widgets; their sessions go on. */
    fun adoptAgents(folders: Map<String, String>) {
        var changed = false
        pages.forEach { list ->
            list.indices.forEach { i ->
                val p = list[i]
                val bin = folders[p.ref]?.takeIf { p.kind == "custom" } ?: return@forEach
                list[i] = Placed(p.id, "agent", bin, p.x, p.y, p.w, p.h); changed = true
            }
        }
        if (changed) save()
    }

    /** Takes these places off every page (an app that was removed), keeping the pages. */
    fun removeRef(kind: String, ref: String) {
        pages.forEach { p -> p.removeAll { it.kind == kind && it.ref == ref } }
        save()
    }

    /** Saves a moved or resized widget and draws it on top of the others. */
    fun update(page: Int, p: Placed) {
        val list = pages.getOrNull(page) ?: return
        val i = list.indexOfFirst { it.id == p.id }.takeIf { it >= 0 } ?: return
        list.removeAt(i); list += p; save()
    }

    fun remove(page: Int, id: String) {
        pages.getOrNull(page)?.removeAll { it.id == id }
        Shells.close(id)
        save()
    }

    fun addPage(): Int { pages += mutableStateListOf<Placed>(); save(); return pages.lastIndex }

    fun removePage(page: Int) {
        if (pages.size <= 1 || page !in pages.indices) return
        pages.removeAt(page).forEach { Shells.close(it.id) }
        save()
    }
}

// ---------- the terminal widget's shell ----------

/** One shell per terminal widget, kept by the process so it survives page swipes and screen changes. */
object Shells {
    private val sessions = HashMap<String, Shell>()
    val count get() = sessions.size
    val active get() = sessions.values.any { it.alive }
    fun get(context: Context, id: String) = sessions.getOrPut(id) { Shell(context.applicationContext, id) }
    fun close(id: String) { sessions.remove(id)?.stop() }
    /** Those AI helpers were updated: widgets running one restart on the new version once they're idle. */
    fun agentsUpdated(bins: List<String>) = sessions.values.forEach { it.agentUpdated(bins) }
}

/**
 * A terminal widget's session on a real terminal (pty) in the private Linux. With no command it's an
 * interactive bash drawn as scrolling text (Screen); with a command (opencode, claude, htop…) the program
 * gets a full screen of the widget's size, drawn from a Grid, and is told when the widget is resized.
 */
class Shell(private val context: Context, private val id: String) {
    var text by mutableStateOf("")
    var alive by mutableStateOf(false)
    /** The program's screen (command sessions): runs of one style per line, and the cursor (col, row; -1 hidden). */
    var lines by mutableStateOf<List<List<Grid.Run>>>(emptyList())
    var cursor by mutableStateOf(-1 to -1)
    /** How far the view is scrolled back into the program's history (lines; 0 = the live screen), and how much there is. */
    var back by mutableStateOf(0); private set
    var historySize by mutableStateOf(0); private set
    /** The program is on its own full screen (no history to scroll), and whether it takes the mouse wheel. */
    var fullScreen by mutableStateOf(false); private set
    var wantsMouse by mutableStateOf(false); private set
    /** It printed something in the last moment or so: an agent at work (or a spinner). */
    var working by mutableStateOf(false); private set
    private var seenHistory = 0L
    private val quiet = Runnable { working = false }
    /** The command this session runs ("" = bash), and whether it has ended. */
    var command = ""; private set
    var ended by mutableStateOf(false)
    val history = mutableListOf<String>()
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var grid: Grid? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private val input = Executors.newSingleThreadExecutor { r -> Thread(r, "terminal-input").apply { isDaemon = true } }
    private val drawing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val ptyFile get() = "/tmp/.hatchlybox-pty-$id"
    @Volatile private var lastInput = 0L
    @Volatile private var lastOutput = 0L
    /** Runs a helper that has since been updated; restarted (by bumping restarts) when idle. */
    private var stale = false
    private var resume = false
    var restarts by mutableStateOf(0)

    /** Starts the session, or a new one when the command changed. Colours are the widget's (ARGB), cell size in px. */
    fun start(cols: Int, rows: Int = 40, command: String = "", fg: Int = 0, bg: Int = 0, cell: Pair<Int, Int> = 10 to 20) {
        if (alive && command == this.command) return
        if (alive) stop()
        val linux = Linux(context)
        if (!linux.hasBasics) { text = "Linux is still installing. The terminal starts by itself when it's ready."; return }
        Agent.entries.firstOrNull { it.bin == command.trim().substringBefore(' ') }?.takeIf { !linux.hasAgents }?.let { ag ->
            text = "${ag.label} is still installing with the other AI helpers. It starts by itself when it's ready."; return
        }
        this.command = command
        // A restart after an update carries on where the helper was ("opencode" → "opencode -c").
        val run = Agent.entries.firstOrNull { it.bin == command.trim() }?.takeIf { resume }?.let { "${command.trim()} ${it.continueFlag}" } ?: command
        resume = false; stale = false
        val gen = ++generation
        alive = true; ended = false; text = ""; lines = emptyList(); cursor = -1 to -1; back = 0; historySize = 0; seenHistory = 0L
        if (command.isBlank()) {
            grid = null
            thread(name = "terminal", isDaemon = true) {
                val screen = Screen(cols, 40)
                try {
                    val p = linux.start("cd /root && exec bash --norc --noprofile -i", extraEnv = mapOf("PS1" to "\\W › "), pty = true, cols = cols)
                    process = p
                    val reader = p.inputStream.reader()
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf); if (n < 0) break
                        val chunk = String(buf, 0, n)
                        screen.feed(chunk)
                        TermSignIn.saw(context, id, chunk)
                        val t = screen.text().takeLast(30000)
                        main.post { if (gen == generation) text = t }
                    }
                    p.waitFor()
                } catch (e: Exception) { main.post { if (gen == generation) text += "\n${e.message}" } }
                TermSignIn.ended(context, id)
                main.post { if (gen == generation) { process?.destroy(); process = null; alive = false; ended = true; text = (text + "\n\n[session ended · send to start again]").trimStart() } }
            }
            return
        }
        val g = Grid(cols, rows) { answer -> send(answer, raw = true) }.apply {
            if (fg != 0) defaultFg = fg; if (bg != 0) defaultBg = bg; cellW = cell.first; cellH = cell.second
        }
        grid = g
        thread(name = "terminal-program", isDaemon = true) {
            try {
                // The pty's name is kept so a resize can reach the program (stty -F sends it SIGWINCH).
                val p = linux.start("tty > $ptyFile 2>/dev/null; cd /root; $run", extraEnv = mapOf("COLORTERM" to "truecolor"), pty = true, cols = cols, rows = rows, cwd = "/root")
                process = p
                val reader = p.inputStream.reader()
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf); if (n < 0) break
                    val chunk = String(buf, 0, n)
                    synchronized(g) { g.feed(chunk) }
                    TermSignIn.saw(context, id, chunk)
                    lastOutput = System.currentTimeMillis()
                    redraw(g, gen)
                }
                p.waitFor()
            } catch (e: Exception) { synchronized(g) { g.feed("\r\n${e.message}") }; redraw(g, gen) }
            TermSignIn.ended(context, id)
            main.post { if (gen == generation) { process?.destroy(); process = null; alive = false; ended = true } }
        }
    }

    /** At most one redraw per frame or so, however fast the program writes. */
    private fun redraw(g: Grid, gen: Int) {
        if (!drawing.compareAndSet(false, true)) return
        main.postDelayed({
            drawing.set(false)
            if (gen != generation) return@postDelayed
            show(g)
            working = true; main.removeCallbacks(quiet); main.postDelayed(quiet, 1500)
        }, 24)
    }

    /** Draws the grid as the view stands; scrolled back, the view stays on the same lines while new ones arrive. */
    private fun show(g: Grid) = synchronized(g) {
        if (back > 0) back = (back + (g.historyAdded - seenHistory).toInt()).coerceIn(0, g.historySize)
        seenHistory = g.historyAdded
        historySize = g.historySize; fullScreen = g.altScreen; wantsMouse = g.mouse
        val b = if (g.altScreen) 0 else back
        lines = g.snapshot(b)
        cursor = if (g.cursorVisible && b == 0) g.cx to g.cy else -1 to -1
    }

    /** Scrolls the view [n] lines back into the history (negative: towards the live screen). */
    fun scrollBy(n: Int) {
        val g = grid ?: return
        val to = synchronized(g) { (back + n).coerceIn(0, g.historySize) }
        if (to != back) { back = to; show(g) }
    }

    /** A program with history to scroll (not on its own full screen). */
    val scrollable get() = grid != null && !fullScreen

    fun toLatest() { if (back != 0) { back = 0; grid?.let { show(it) } } }

    /** Everything the session has shown (its history and screen), as text. */
    fun copyText(): String = grid?.let { g -> synchronized(g) { g.allText() } } ?: text

    /** Text from the clipboard, as the program expects a paste (bracketed when it asked for that). */
    fun paste(value: String) {
        val t = value.replace("\r\n", "\r").replace('\n', '\r')
        val bracket = grid?.let { g -> synchronized(g) { g.bracketedPaste } } == true
        send(if (bracket) "\u001B[200~$t\u001B[201~" else t)
    }

    /** The widget changed size: the program's screen follows (it redraws itself on SIGWINCH). */
    fun resize(cols: Int, rows: Int) {
        val g = grid ?: return
        val changed = synchronized(g) { (g.cols != cols || g.rows != rows).also { if (it) g.resize(cols, rows) } }
        if (!changed) return
        redraw(g, generation)
        thread(isDaemon = true) { runCatching { Linux(context).run("f=\$(cat $ptyFile 2>/dev/null) && stty -F \"\$f\" rows $rows cols $cols") } }
    }

    /** Keys and text for the program; arrows follow its cursor-key mode. `raw` is for replies to its questions. */
    fun send(value: String, raw: Boolean = false) {
        val p = process ?: return
        if (!raw) { lastInput = System.currentTimeMillis(); main.post { toLatest() } }
        val v = if (!raw && grid?.appCursor == true && value.length == 3 && value.startsWith("\u001B[") && value[2] in 'A'..'D') "\u001BO" + value[2] else value
        input.execute { runCatching { p.outputStream.apply { write(v.toByteArray()); flush() } } }
    }

    /** A tap or a scroll at a cell, when the program asked for the mouse. Returns whether it was sent. */
    fun mouse(button: Int, col: Int, row: Int, release: Boolean = true): Boolean {
        val g = grid ?: return false
        if (!g.mouse) return false
        val c = col + 1; val r = row + 1
        if (g.sgrMouse) send("\u001B[<$button;$c;${r}M" + if (release) "\u001B[<$button;$c;${r}m" else "", raw = true)
        else if (c < 224 && r < 224) send("\u001B[M" + (32 + button).toChar() + (32 + c).toChar() + (32 + r).toChar() +
            if (release) "\u001B[M" + (32 + 3).toChar() + (32 + c).toChar() + (32 + r).toChar() else "", raw = true)
        return true
    }

    fun stop() { generation++; process?.destroy(); process = null; alive = false; runCatching { java.io.File(ptyFile).delete() }; TermSignIn.ended(context, id) }

    fun agentUpdated(bins: List<String>) {
        if (!alive || command.trim().substringBefore(' ') !in bins) return
        stale = true
        restartWhenIdle()
    }

    // Idle: nothing typed for 3 minutes and nothing printed for one (an agent at work keeps printing).
    private fun restartWhenIdle() {
        if (!stale || !alive) return
        val now = System.currentTimeMillis()
        if (now - lastInput > 180_000 && now - lastOutput > 60_000) { stop(); resume = true; restarts++ }
        else main.postDelayed({ restartWhenIdle() }, 60_000)
    }
}

// ---------- the home screen ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LauncherHome(m: MakerModel, web: AppModel, openWeb: (Project) -> Unit, createWeb: (String) -> Boolean, allow: (String) -> Unit) {
    val context = LocalContext.current
    // Phone apps: reloaded whenever Hatchlybox comes back to the front (one may have been installed or removed).
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner, m.showPhoneApps) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) m.loadPhoneApps() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    // Widgets: read again every two seconds while the home screen is in front, live data when it's due.
    // Kept (hidden) under other pages: back on home, its widgets catch up at once.
    LaunchedEffect(m.page == "apps" && !m.showConsole) { if (m.page == "apps" && !m.showConsole) { m.widgetEngine.reload(); m.widgetEngine.refreshDue() } }
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) { Look.installDocs(context) }
        launch { while (true) { if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && m.page == "apps" && !m.showConsole) { m.widgetEngine.reload(); Look.reloadCustom(context) }; delay(if (m.themeTask) 1000 else 2000) } }
        while (true) { if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && m.page == "apps" && !m.showConsole) { PhoneInfo.update(context); m.widgetEngine.refreshDue() }; delay(5000) }
    }
    val layout = m.launcher
    LaunchedEffect(m.widgetEngine.widgets) {
        layout.adoptAgents(m.widgetEngine.widgets.mapNotNull { w -> agentOf(w)?.let { w.id to it.bin } }.toMap())
    }
    // One page more than there are: swiping past the last one shows a note offering to make it a new page.
    val pager = rememberPagerState(initialPage = m.homePage.coerceIn(0, layout.pages.lastIndex)) { layout.pages.size + 1 }
    LaunchedEffect(m.homePage) { val t = m.homePage.coerceIn(0, layout.pages.size); if (pager.currentPage != t) pager.animateScrollToPage(t) }
    LaunchedEffect(pager.currentPage) { m.homePage = pager.currentPage }
    // A widget made from the create box takes the size its widget.json asks for, once the agent has written it.
    LaunchedEffect(Unit) {
        snapshotFlow { m.widgetEngine.widgets.mapNotNull { w -> w.spec?.optString("size")?.takeIf { it.isNotBlank() }?.let { w.id to it } } }
            .collect { it.forEach { (id, size) -> layout.sizeNew(id, size) } }
    }
    var edit by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf<Placed?>(null) }
    var confirmPage by remember { mutableStateOf(false) }
    LaunchedEffect(HomeSignal.presses) { edit = null; sheet = null }
    BackHandler(enabled = edit != null && !m.library && m.page == "apps" && !m.showConsole) { edit = null }

    // The apps carousel: favourites first, then the most used, topped up with the newest of your own apps.
    val items = libraryItems(m, web, openWeb)
    // Agents' requests for the phone's actions (the `phone` command) are answered while home is up.
    val latestItems by rememberUpdatedState(items)
    val bridgeContext = LocalContext.current
    LaunchedEffect(Unit) {
        // An updated app brings new instructions for the agents; without this they'd keep the old ones until a widget was made.
        withContext(Dispatchers.IO) { runCatching { m.widgetEngine.refreshAgents() } }
        PhoneBridge.keepServing(bridgeContext, m) { latestItems }
    }
    PhoneAsk()
    ActionReviewDialog()
    // A widget's screen, opened by a tap on it: over everything, full screen.
    WidgetScreens.open?.let { id ->
        val w = m.widgetEngine.widgets.firstOrNull { it.id == id }
        val screenScope = rememberCoroutineScope()
        if (w == null) SideEffect { WidgetScreens.open = null }
        else androidx.compose.ui.window.Dialog(onDismissRequest = { WidgetScreens.open = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            WidgetScreen(w, m.linux.hostFile("$WIDGETS_DIR/$id"), act = { a -> screenScope.launch { m.widgetEngine.act(w, a, m::openTarget) } },
                close = { WidgetScreens.open = null })
        }
    }
    val favourites = m.favourites.mapNotNull { k -> items.firstOrNull { it.key == k } }
    val newApp = m.busy && m.job.text("kind", "app") == "app" && m.apps.none { it.text("slug") == m.job.text("slug") }
    val shown = (favourites + items.filter { (m.uses[it.key] ?: 0) > 0 }.sortedByDescending { m.uses[it.key] ?: 0 } +
        items.filter { it.type == "web" || it.type == "apk" }.sortedByDescending { it.sort }).distinctBy { it.key }.take(16)

    val scope = rememberCoroutineScope()
    // Any tap on the home screen wakes the create box with a small nudge; it fades back after a while.
    val bump = remember { Animatable(0f) }
    var awakeAt by remember { mutableLongStateOf(0L) }
    val awake by animateFloatAsState(if (awakeAt > 0) 1f else 0f, tween(if (awakeAt > 0) 160 else 900), label = "awake")
    LaunchedEffect(awakeAt) { if (awakeAt > 0) { delay(2600); awakeAt = 0 } }
    // The library: 0 = closed, 1 = open; it follows the finger both ways.
    val progress = remember { Animatable(if (m.library) 1f else 0f) }
    val libraryShown by remember { derivedStateOf { progress.value > 0.001f } }
    var dragging by remember { mutableStateOf(false) }
    var flingVelocity by remember { mutableFloatStateOf(0f) }
    val look = LocalLook.current
    val libSpec: AnimationSpec<Float> = when (look.motion) {
        "snappy" -> tween(150, easing = LinearEasing)
        "float" -> spring(dampingRatio = 0.95f, stiffness = 170f)
        else -> spring(dampingRatio = 0.86f, stiffness = 420f)
    }
    LaunchedEffect(m.library) {
        if (dragging) return@LaunchedEffect
        val v = flingVelocity; flingVelocity = 0f
        progress.animateTo(if (m.library) 1f else 0f, libSpec, initialVelocity = v)
    }

    // The terminal keyboard: hiding the keyboard (back, or its own button) also lets go of the terminal.
    val focusManager = LocalFocusManager.current
    val imeOpen = WindowInsets.isImeVisible
    var imeWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) { if (imeWasOpen && !imeOpen && TermInput.focused != null) focusManager.clearFocus(); imeWasOpen = imeOpen }
    val typing = TermInput.focused != null

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (progress.value == 0f) { awakeAt = System.currentTimeMillis(); scope.launch { bump.snapTo(0f); bump.animateTo(1f, tween(420)) } }
        }
    }) {
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val cell = constraints.maxWidth / GRID_COLS.toFloat()
        val rows = (height / cell).toInt().coerceAtLeast(4)
        SideEffect { layout.rows = rows }
        val dragBy: (Float) -> Unit = { dy -> dragging = true; scope.launch { progress.snapTo((progress.value - dy / height).coerceIn(0f, 1f)) } }
        val settle: (Float, Boolean) -> Unit = { v, opening ->
            dragging = false
            val open = when { v < -900f -> true; v > 900f -> false; else -> progress.value > (if (opening) 0.22f else 0.78f) }
            if (open == m.library) scope.launch { progress.animateTo(if (open) 1f else 0f, libSpec, initialVelocity = -v / height) }
            else { flingVelocity = -v / height; m.library = open }
        }
        // While a terminal has the keyboard, it moves up (and shrinks if it must) to sit above the key bar.
        val fit = TermInput.focused?.takeIf { imeOpen }?.let { it to height - with(LocalDensity.current) { (KeyBarHeight + 6.dp).toPx() } }
        val tracker = remember { VelocityTracker() }
        val dragNow by rememberUpdatedState(dragBy)
        val settleNow by rememberUpdatedState(settle)
        Box(Modifier.fillMaxSize().pointerInput(edit == null && !typing) {
            if (edit != null || typing) return@pointerInput
            // Swipe up anywhere: the library comes up with the finger.
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onDragEnd = { settleNow(tracker.calculateVelocity().y, true) },
                onDragCancel = { settleNow(0f, true) },
            ) { change, dy -> tracker.addPosition(change.uptimeMillis, change.position); change.consume(); dragNow(dy) }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer { val p = progress.value; alpha = 1f - 0.9f * p; val s = 1f - 0.05f * p; scaleX = s; scaleY = s }) {
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = edit == null, beyondViewportPageCount = 1) { page ->
                    if (page >= layout.pages.size) NewPageHint { val i = layout.addPage(); m.homePage = i }
                    else HomePage(m, page, cell, rows, edit, { edit = it }, { sheet = "menu" }, { changing = it }, fit,
                        jump = { p, side -> layout.moveToPage(page, p.id, page + side, p)?.let { to -> m.homePage = to } }) { p -> WidgetContent(m, p, shown, items, newApp) }
                }
                // Setup, permissions and messages: slim lines at the top, out of the way.
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SetupNotice(m, Modifier)
                    PermissionNotice(m, allow, Modifier)
                    if (m.notice.isNotBlank() || m.links.isNotEmpty() || Act.waiting.isNotEmpty()) Messages(m)
                }
                // Settings live in the library and the long-press menu, so the home screen stays clear.
                if (edit != null) FilledTonalButton(onClick = { edit = null }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Done") }
                if (typing) KeyBar(Modifier.align(Alignment.BottomCenter))
                else if (edit == null) CreateBar(
                    m, web, createWeb, items, bump = { bump.value }, awake = { awake }, pages = layout.pages.size, page = pager.currentPage,
                    openLibrary = { m.library = true }, modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        // Closing follows the finger too. Measured here, on a layer that doesn't move, so the drag stays steady
        // while the library slides: a pull down from the top of the list (or anywhere above it) takes over.
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        if (libraryShown || m.library) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!m.library || progress.value < 0.98f || grid.canScrollBackward) return@awaitEachGesture
                    var dyTotal = 0f; var dxTotal = 0f; var taking = false
                    tracker.resetTracking()
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val d = c.position - c.previousPosition
                        if (!taking) {
                            dyTotal += d.y; dxTotal += d.x
                            if (dyTotal < -viewConfiguration.touchSlop || abs(dxTotal) > viewConfiguration.touchSlop) return@awaitEachGesture
                            if (dyTotal > viewConfiguration.touchSlop) taking = true else continue
                        }
                        c.consume(); tracker.addPosition(c.uptimeMillis, c.position); dragNow(d.y)
                    }
                    if (taking) settleNow(tracker.calculateVelocity().y, false)
                }
            },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { val p = progress.value; translationY = (1f - p) * size.height; alpha = (p * 3f).coerceAtMost(1f) }) {
                Library(m, web, openWeb, createWeb, grid)
            }
        }
        // The chat window, over the home screen and the library alike; a tap outside puts it away.
        LaunchedEffect(Unit) { AgentChat.load(context) }
        // Paired devices sync by themselves once the tools are in; a scanned sync link opens the library's sync.
        LaunchedEffect(m.toolsInstalled) { if (m.toolsInstalled) DeviceSync.loadDevices(context) { DeviceSync.keepServing(context, m) } }
        LaunchedEffect(DeviceSync.pendingLink) { if (DeviceSync.pendingLink != null) m.library = true }
        if (AgentChat.shown) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { AgentChat.shown = false } })
        AnimatedVisibility(
            AgentChat.shown, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it / 3 } + fadeIn(), exit = androidx.compose.animation.slideOutVertically { it / 3 } + fadeOut(),
        ) { ChatWindow(m, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) }
        // The gold thread an action draws to where it happens, over everything.
        GoldOverlay()
    }

    val page = pager.currentPage.coerceAtMost(layout.pages.lastIndex)
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }) {
        if (sheet == "menu") Column(Modifier.padding(bottom = 24.dp)) {
            SheetItem(Icons.Outlined.Widgets, "Add a widget", "A clock, a terminal, your apps, or one an agent makes") { sheet = "add" }
            SheetItem(Icons.Outlined.Add, "Add a page", "An empty page to the right of the others") { val i = layout.addPage(); sheet = null; m.homePage = i }
            if (layout.pages.size > 1) SheetItem(Icons.Outlined.Delete, "Remove this page", if (layout.pages.getOrNull(page).isNullOrEmpty()) "It's empty" else "With its widgets") {
                sheet = null
                if (layout.pages.getOrNull(page).isNullOrEmpty()) { layout.removePage(page); m.homePage = (page - 1).coerceAtLeast(0) } else confirmPage = true
            }
            SheetItem(Icons.Outlined.Palette, "Theme and background", "${Look.current.name} · ${Look.palette.name}") { sheet = "look" }
            SheetItem(Icons.Outlined.Settings, "Settings", "Agents, tools, home screen") { sheet = null; m.page = "settings" }
        } else if (sheet == "look") Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Theme and background", style = MaterialTheme.typography.titleMedium)
            LookPicker(m)
        } else AddWidget(m, add = { kind, ref, size -> val to = layout.add(page, kind, ref, size); sheet = null; if (to != page) { m.homePage = to; m.notice = "This page was full, so it went on a new page." } })
    }
    if (confirmPage) AlertDialog(
        onDismissRequest = { confirmPage = false }, title = { Text("Remove this page?") },
        text = { Text("Its widgets go from the home screen. Widgets an agent made stay in the list, ready to add again.") },
        confirmButton = { TextButton(onClick = { confirmPage = false; layout.removePage(page); m.homePage = (page - 1).coerceAtLeast(0) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmPage = false }) { Text("Cancel") } },
    )
    changing?.let { p ->
        var what by rememberSaveable { mutableStateOf("") }
        val name = when (p.kind) { "clock" -> "the clock"; "terminal" -> "the terminal"; "agent" -> Agent.entries.firstOrNull { it.bin == p.ref }?.label ?: p.ref; "apps" -> "your apps"; else -> m.widgetEngine.widgets.firstOrNull { it.id == p.ref }?.name ?: p.ref }
        AlertDialog(
            onDismissRequest = { changing = null }, title = { Text("Change $name") },
            text = {
                OutlinedTextField(what, { what = it }, placeholder = { Text(if (p.kind == "custom") "What should change?" else "Its look: colours, font, size, format…") }, minLines = 2, maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), shape = RoundedCornerShape(16.dp))
            },
            confirmButton = { TextButton(enabled = what.isNotBlank(), onClick = { if (p.kind == "custom") m.makeWidget(what, p.ref) else m.styleWidget(if (p.kind == "agent") "terminal" else p.kind, p.id, what); what = ""; changing = null; edit = null }) { Text("Ask ${agentName(m.agent)}") } },
            dismissButton = { TextButton(onClick = { changing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * A page: its widgets at their grid places. Long-press a widget to edit it (keep the finger down to move it at once).
 * Held against the left or right edge while moving, it goes to the page on that side (past the last: a new page).
 */
@Composable
private fun HomePage(
    m: MakerModel, page: Int, cell: Float, rows: Int, edit: String?, setEdit: (String?) -> Unit, openMenu: () -> Unit,
    change: (Placed) -> Unit, fit: Pair<String, Float>?, jump: (Placed, Int) -> Unit, content: @Composable (Placed) -> Unit,
) {
    val items = m.launcher.pages.getOrNull(page) ?: return
    var move by remember { mutableStateOf(Offset.Zero) }
    var grow by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // The edge the moving widget is held against (-1 left, 1 right); after a moment there it changes page.
    var edge by remember { mutableIntStateOf(0) }
    val jumpNow by rememberUpdatedState(jump)
    val haptic = LocalHapticFeedback.current
    val now by rememberUpdatedState(Triple(cell, rows, edit))
    fun rectOf(p: Placed): Rect {
        val c = now.first
        val r = Rect(p.x * c, p.y * c, (p.x + p.w) * c, (p.y + p.h) * c)
        return if (p.id != now.third) r else Rect(r.left + move.x, r.top + move.y, r.right + move.x + grow.x, r.bottom + move.y + grow.y)
    }
    fun snapped(p: Placed): Placed {
        val (c, rs) = now.first to now.second
        val w = ((p.w * c + grow.x) / c).roundToInt().coerceIn(2, GRID_COLS)
        val h = ((p.h * c + grow.y) / c).roundToInt().coerceIn(2, maxOf(2, rs))
        val x = ((p.x * c + move.x) / c).roundToInt().coerceIn(0, GRID_COLS - w)
        val y = ((p.y * c + move.y) / c).roundToInt().coerceIn(0, maxOf(0, rs - h))
        return p.at(x, y, w, h)
    }
    /** A carried widget dropped on a page without room goes back to where it was picked up. */
    fun dropped(id: String) {
        m.launcher.drop(page, id)?.let { back -> setEdit(null); m.homePage = back; m.notice = "That page has no room, so the widget went back." }
    }
    fun commit(p: Placed) {
        val s = snapped(p); move = Offset.Zero; grow = Offset.Zero
        if (s.x != p.x || s.y != p.y || s.w != p.w || s.h != p.h) m.launcher.update(page, s)
        dropped(p.id)
    }
    fun begin(id: String?) { now.third?.takeIf { id == null }?.let { dropped(it) }; move = Offset.Zero; grow = Offset.Zero; edge = 0; setEdit(id) }
    /** x: the finger, across the page. Near an edge (and with a page that way, or room for a new one) it starts the wait. */
    fun edgeAt(x: Float) {
        val zone = maxOf(cell * 0.45f, with(density) { 22.dp.toPx() })
        edge = when { x < zone && page > 0 -> -1; x > cell * GRID_COLS - zone -> 1; else -> 0 }
    }
    LaunchedEffect(edge) {
        val side = edge
        if (side == 0) return@LaunchedEffect
        delay(650)
        val p = items.firstOrNull { it.id == now.third } ?: return@LaunchedEffect
        val s = snapped(p)
        move = Offset.Zero; grow = Offset.Zero; edge = 0
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        jumpNow(s, side)
    }
    /** Where the edit toolbar sits: above the widget, or below it when there's no room above. */
    fun toolbarTop(p: Placed): Float {
        val r = rectOf(p)
        return if (r.top > with(density) { 64.dp.toPx() }) r.top - with(density) { 56.dp.toPx() } else r.bottom + with(density) { 8.dp.toPx() }
    }
    val look = LocalLook.current
    val dot = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                if (edit == null) return@drawBehind
                // The grid, faintly, while a widget is being placed.
                for (gx in 0..GRID_COLS) for (gy in 0..rows) drawCircle(dot, 1.5.dp.toPx(), Offset(gx * cell, gy * cell))
            }
            .pointerInput(page) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val hit = items.lastOrNull { rectOf(it).contains(down.position) }
                    val editing = now.third
                    if (editing != null) {
                        if (hit?.id == editing) return@awaitEachGesture
                        // The toolbar's buttons (delete, edit, refresh, done) take their own taps.
                        val bar = items.firstOrNull { it.id == editing }?.let { toolbarTop(it) }
                        if (bar != null && down.position.y in bar..(bar + with(density) { 48.dp.toPx() })) return@awaitEachGesture
                        // A tap beside the widget being edited finishes editing; nothing underneath gets it.
                        do { val e = awaitPointerEvent(PointerEventPass.Initial); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                        begin(null); return@awaitEachGesture
                    }
                    val long = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        }
                        false
                    } == null
                    if (!long) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (hit == null) {
                        openMenu()
                        do { val e = awaitPointerEvent(PointerEventPass.Initial); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    // Edit it, and keep following the finger: lift-free moving.
                    begin(hit.id)
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        e.changes.forEach { it.consume() }
                        if (!c.pressed) break
                        // Gone to another page: the rest of this touch does nothing here.
                        if (items.none { it.id == hit.id }) continue
                        move += c.position - c.previousPosition
                        edgeAt(c.position.x)
                    }
                    edge = 0
                    if (move != Offset.Zero && items.any { it.id == hit.id }) commit(hit)
                }
            },
    ) {
        items.forEachIndexed { index, p ->
            key(p.id) {
                // Each style brings its widgets in its own way: a spring, a stepped reveal, a neon flicker, a slow drift.
                val appear = remember(p.id, look.id) { Animatable(0f) }
                LaunchedEffect(p.id, look.id) {
                    delay(index * if (look.motion == "snappy") 70L else 45L)
                    when (look.motion) {
                        "snappy" -> appear.snapTo(1f)
                        "pulse" -> appear.animateTo(1f, keyframes { durationMillis = 520; 0.85f at 60; 0.1f at 130; 0.9f at 210; 0.35f at 280; 1f at 420 })
                        "float" -> appear.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
                        else -> appear.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 260f))
                    }
                }
                val placed = rectOf(p)
                val editing = p.id == edit
                // Above the keyboard: the terminal being typed in slides up, and shrinks only if it must.
                val lifted = fit != null && fit.first == p.id && placed.bottom > fit.second
                val r = if (!lifted) placed else {
                    val top = maxOf(with(density) { 8.dp.toPx() }, fit!!.second - placed.height)
                    Rect(placed.left, top, placed.right, fit.second)
                }
                Box(
                    Modifier.zIndex(if (lifted || editing) 1f else 0f).offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.coerceAtLeast(cell).toDp() }, with(density) { r.height.coerceAtLeast(cell).toDp() })
                        .padding(look.gap.dp)
                        .graphicsLayer {
                            val a = appear.value
                            alpha = (if (edit != null && !editing) 0.5f else 1f) * a.coerceIn(0f, 1f)
                            when (look.motion) {
                                "smooth" -> { val sc = 0.92f + 0.08f * a; scaleX = sc; scaleY = sc }
                                "float" -> translationY = (1f - a) * 18.dp.toPx()
                            }
                        },
                ) {
                    content(p)
                    if (editing) EditFrame(
                        onMove = { d, at -> move += d; edgeAt(rectOf(p).left + with(density) { look.gap.dp.toPx() } + at.x) },
                        onGrow = { grow += it }, onEnd = { edge = 0; if (items.any { it.id == p.id }) commit(p) },
                    )
                }
            }
        }
        // Held against an edge: a glow on that side, the page it's about to go to.
        if (edge != 0) {
            val glow = MaterialTheme.colorScheme.primary
            Box(
                Modifier.align(if (edge < 0) Alignment.CenterStart else Alignment.CenterEnd).zIndex(2f).fillMaxHeight().width(28.dp)
                    .background(Brush.horizontalGradient(if (edge < 0) listOf(glow.copy(alpha = 0.45f), Color.Transparent) else listOf(Color.Transparent, glow.copy(alpha = 0.45f)))),
            )
        }
        // Where it lands, and what can be done with it.
        items.firstOrNull { it.id == edit }?.let { p ->
            val s = snapped(p)
            if (move != Offset.Zero || grow != Offset.Zero) Box(
                Modifier.offset { IntOffset((s.x * cell).roundToInt(), (s.y * cell).roundToInt()) }
                    .size(with(density) { (s.w * cell).toDp() }, with(density) { (s.h * cell).toDp() }).padding(5.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
            )
            val y = toolbarTop(p)
            Box(Modifier.fillMaxWidth().offset { IntOffset(0, y.roundToInt()) }, contentAlignment = Alignment.TopCenter) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            m.launcher.forget(); begin(null); m.launcher.remove(page, p.id)
                            // A page left empty goes too (not the first), and home shows the one before it.
                            if (page > 0 && m.launcher.pages.getOrNull(page)?.isEmpty() == true) { m.launcher.removePage(page); m.homePage = page - 1 }
                        }) { Icon(Icons.Outlined.Delete, "Remove from the home screen") }
                        if (p.kind != "site" && p.kind != "app") IconButton(onClick = { change(p) }) { Icon(Icons.Outlined.Edit, "Change with ${agentName(m.agent)}") }
                        if (p.kind == "custom" && m.widgetEngine.widgets.any { it.id == p.ref && it.spec?.optString("data").orEmpty().isNotBlank() })
                            IconButton(onClick = { m.widgetEngine.refreshNow(p.ref) }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                        IconButton(onClick = { begin(null) }) { Icon(Icons.Default.Check, "Done") }
                    }
                }
            }
        }
    }
}

/** Past the last page: a small note offering to make it a new page. Nothing is made until the button is tapped. */
@Composable
private fun NewPageHint(create: () -> Unit) {
    val look = LocalLook.current
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 300.dp).lookFrame(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ring = MaterialTheme.colorScheme.outline
            Box(
                Modifier.size(48.dp).drawBehind {
                    drawRoundRect(ring, cornerRadius = androidx.compose.ui.geometry.CornerRadius(minOf(look.corner.dp.toPx(), size.minDimension / 2)),
                        style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary) }
            Text(if (look.uppercase) "NO PAGE HERE YET" else "No page here yet", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text("Make it a new page for more widgets, or swipe back.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            FilledTonalButton(onClick = create, modifier = Modifier.padding(top = 4.dp)) { Text("Create page") }
        }
    }
}

/** The frame of the widget being edited: drag it to move (onMove: the change and the finger in the frame), drag the corner to resize. */
@Composable
private fun BoxScope.EditFrame(onMove: (Offset, Offset) -> Unit, onGrow: (Offset) -> Unit, onEnd: () -> Unit) {
    val c = MaterialTheme.colorScheme.primary
    val moved by rememberUpdatedState(onMove)
    val grown by rememberUpdatedState(onGrow)
    val ended by rememberUpdatedState(onEnd)
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.matchParentSize().clip(shape).background(c.copy(alpha = 0.07f)).border(2.dp, c, shape)
            .pointerInput(Unit) { detectDragGestures(onDragEnd = { ended() }, onDragCancel = { ended() }) { ch, d -> ch.consume(); moved(d, ch.position) } },
    )
    Box(
        Modifier.align(Alignment.BottomEnd).padding(4.dp).size(32.dp).clip(CircleShape).background(c)
            .pointerInput(Unit) { detectDragGestures(onDragEnd = { ended() }, onDragCancel = { ended() }) { ch, d -> ch.consume(); grown(d) } },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.OpenInFull, "Resize", Modifier.size(14.dp).rotate(90f), tint = MaterialTheme.colorScheme.onPrimary) }
}

@Composable
private fun WidgetContent(m: MakerModel, p: Placed, shown: List<LibItem>, all: List<LibItem>, newApp: Boolean) {
    when (p.kind) {
        "clock" -> ClockWidget(m, p.id)
        "analog" -> AnalogClockWidget(m)
        "calendar" -> CalendarWidget(m)
        "terminal" -> WStyle(m.widgetEngine.styles[p.id]).let { st -> TerminalWidget(m, p.id, st.str("command"), st.str("title")) }
        "agent" -> WStyle(m.widgetEngine.styles[p.id]).let { st -> TerminalWidget(m, p.id, st.str("command").ifBlank { p.ref }, st.str("title").ifBlank { p.ref }) }
        "apps" -> AppsCarousel(m, p.id, shown, all, newApp)
        "site" -> SiteTile(m, p.ref, wallShadow())
        "app" -> AppTile(m, all.firstOrNull { it.key == p.ref }, wallShadow())
        else -> CustomWidget(m, p.ref, p.id)
    }
}

/** A library app's icon on the home screen: a tap does what it does in the library. Gone from the library: a faded mark to remove. */
@Composable
private fun AppTile(m: MakerModel, item: LibItem?, shadow: androidx.compose.ui.graphics.Shadow?) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth * 0.62f, maxHeight - 22.dp, 64.dp).coerceAtLeast(24.dp)
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).then(if (item != null) Modifier.goldTarget(item.key).clickable(onClickLabel = "Open ${item.label}") { m.used(item.key); item.open() } else Modifier)
                .padding(4.dp).graphicsLayer { alpha = if (item == null) 0.4f else 1f },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                if (item != null) item.icon(side.value.roundToInt())
                else Box(Modifier.size(side).clip(RoundedCornerShape(side * 0.26f)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                if (item?.word != null) CircularProgressIndicator(Modifier.size(side), strokeWidth = 2.dp, color = typeColor(item.type))
            }
            Text(item?.let { it.word ?: it.label } ?: "Not found", style = MaterialTheme.typography.labelSmall.copy(shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

// ---------- widgets ----------

/** A built-in widget's look from its style.json (written by an agent), with the widget's own defaults. */
private class WStyle(val j: JSONObject?) {
    fun str(k: String) = j?.optString(k).orEmpty()
    fun num(k: String, d: Float) = j?.optDouble(k)?.takeIf { !it.isNaN() }?.toFloat() ?: d
    fun bool(k: String, d: Boolean) = if (j?.has(k) == true) j.optBoolean(k, d) else d
    fun family(d: FontFamily) = when (str("font")) { "mono" -> FontFamily.Monospace; "serif" -> FontFamily.Serif; "" -> d; else -> FontFamily.SansSerif }
    fun weight(d: FontWeight) = when (str("font")) {
        "thin" -> FontWeight.Thin; "light" -> FontWeight.Light; "regular" -> FontWeight.Normal; "medium" -> FontWeight.Medium; "bold" -> FontWeight.Bold; else -> d
    }
    fun align() = when (str("align")) { "start" -> Alignment.Start; "end" -> Alignment.End; else -> Alignment.CenterHorizontally }
}

/** The background a style asks for (with its opacity), or the widget's own. */
@Composable
private fun WStyle.back(default: Color): Color = color(str("background"), default).let { c -> if (j?.has("opacity") == true) c.copy(alpha = c.alpha * num("opacity", 1f).coerceIn(0f, 1f)) else c }

/** Soft shadow under text drawn straight on the phone's wallpaper, so it stays readable. */
private fun wallShadow() = if (Look.photoLike) androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), 10f) else null

/** A calm clock: the time in thin numerals, the day beneath in small spaced capitals. Tap for alarms. */
@Composable
private fun ClockWidget(m: MakerModel, id: String) {
    val context = LocalContext.current
    val st = WStyle(m.widgetEngine.styles[id])
    val now = rememberWidgetTime(if (LocalLook.current.clock == "mono" || st.str("format").contains("s")) 1000L else 60_000L)
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val time = runCatching { SimpleDateFormat(st.str("format").ifBlank { if (h24) "HH:mm" else "h:mm" }, Locale.getDefault()).format(now) }.getOrDefault("--:--")
    val day = runCatching { SimpleDateFormat(st.str("dateFormat").ifBlank { "EEEE d MMMM" }, Locale.getDefault()).format(now) }.getOrDefault("")
    val date = if (st.bool("caps", true)) day.uppercase(Locale.getDefault()) else day
    val look = LocalLook.current
    // The style's clock: thin numerals, terminal digits with a blinking colon, lit numerals, or light ones.
    val kind = look.clock
    val tick = now.time / 1000L % 2L == 0L
    val shownTime = if (kind == "mono" && !tick) time.replace(':', ' ') else time
    val shownDate = if (kind == "mono") "[ $date ]" else date
    val framed = look.frameAll && st.str("background").isBlank()
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .then(if (framed) Modifier.lookFrame(MaterialTheme.colorScheme.surfaceContainer, if (st.j?.has("corner") == true) st.num("corner", 24f).dp else null)
                else Modifier.clip(RoundedCornerShape(st.num("corner", look.corner).dp)).background(st.back(Color.Transparent)))
            .clickable(onClickLabel = "Alarms") { m.openClock() }.padding(st.num("padding", 0f).dp),
        contentAlignment = Alignment.Center,
    ) {
        val size = (minOf(maxHeight.value * 0.56f, maxWidth.value / (time.length * 0.58f)) * st.num("scale", 1f)).coerceIn(20f, 150f)
        val roomy = maxHeight > 96.dp && st.bool("date", true)
        val detailed = maxHeight > 150.dp
        val alarm = remember(now.time / 60_000L) { runCatching { context.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull() }
        Column(Modifier.fillMaxWidth().padding(horizontal = if (maxWidth > 200.dp) 12.dp else 4.dp), horizontalAlignment = st.align(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val lit = MaterialTheme.colorScheme.primary
            Text(
                shownTime, maxLines = 1,
                color = color(st.str("text"), if (kind == "glow") lerp(lit, Color.White, 0.25f) else MaterialTheme.colorScheme.onSurface),
                style = TextStyle(
                    fontFamily = st.family(if (kind == "mono") FontFamily.Monospace else FontFamily.SansSerif),
                    fontWeight = st.weight(when (kind) { "mono" -> FontWeight.Normal; "glow", "light" -> FontWeight.Light; else -> FontWeight.Thin }),
                    fontSize = (if (kind == "mono") size * 0.82f else size).sp, lineHeight = size.sp,
                    letterSpacing = (if (kind == "mono") 0f else -size * 0.02f).sp, fontFeatureSettings = "tnum",
                    shadow = if (kind == "glow") androidx.compose.ui.graphics.Shadow(lit, Offset.Zero, size * 0.45f) else wallShadow(),
                ),
            )
            if (roomy) Text(
                shownDate, maxLines = 1, color = color(st.str("accent"), MaterialTheme.colorScheme.onSurfaceVariant),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = if (st.bool("caps", true)) 2.4.sp else 0.4.sp, fontWeight = FontWeight.Normal,
                    fontFamily = st.family(FontFamily.SansSerif), shadow = wallShadow()),
            )
            if (detailed) {
                val zone = java.util.TimeZone.getDefault().let { it.getDisplayName(it.inDaylightTime(now), java.util.TimeZone.SHORT) }
                Text(if (alarm != null) "Next alarm · " + android.text.format.DateFormat.format(if (h24) "EEE HH:mm" else "EEE h:mm a", alarm) else zone,
                    style = MaterialTheme.typography.labelSmall.copy(shadow = wallShadow()), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

        }
    }
}

/** A clock face in the style: ticks or numerals, hands in the palette, a glow for the glowing styles. Tap for alarms. */
@Composable
private fun AnalogClockWidget(m: MakerModel) {
    val time = rememberWidgetTime(1000L)
    val now = remember(time) { java.util.Calendar.getInstance().apply { this.time = time } }
    val look = LocalLook.current
    val c = MaterialTheme.colorScheme
    val ink = c.onSurface; val accent = c.primary; val second = c.tertiary
    val square = look.corner < 8f; val glow = look.glow
    // Wide: the face on the left and the time and date in words beside it; otherwise the face alone, as big as fits.
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = android.text.format.DateFormat.format("h:mm a, EEEE d MMMM", now).toString() }.lookFrame(c.surfaceContainer).clickable(onClickLabel = "Alarms") { m.openClock() }.padding(12.dp)) {
    val wide = maxWidth >= maxHeight * 1.8f
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, if (wide) Alignment.Start else Alignment.CenterHorizontally)) {
        androidx.compose.foundation.Canvas(if (wide) Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.94f; val o = center
            fun at(frac: Float, len: Float) = Offset(o.x + len * kotlin.math.sin(frac * 6.2832f), o.y - len * kotlin.math.cos(frac * 6.2832f))
            drawCircle(c.primary.copy(alpha = 0.04f), r, o)
            drawCircle(ink.copy(alpha = 0.1f), r * 0.98f, o, style = Stroke(1.dp.toPx()))
            for (i in 0 until 60) {
                val major = i % 5 == 0
                if (!major && r < 60.dp.toPx()) continue
                val len = if (major) r * 0.12f else r * 0.05f
                drawLine(if (major) ink.copy(alpha = 0.75f) else ink.copy(alpha = 0.25f), at(i / 60f, r - len), at(i / 60f, r), (if (major) 2f else 1f).dp.toPx(),
                    cap = if (square) androidx.compose.ui.graphics.StrokeCap.Butt else androidx.compose.ui.graphics.StrokeCap.Round)
            }
            val h = (now.get(java.util.Calendar.HOUR) + now.get(java.util.Calendar.MINUTE) / 60f) / 12f
            val mi = (now.get(java.util.Calendar.MINUTE) + now.get(java.util.Calendar.SECOND) / 60f) / 60f
            val se = now.get(java.util.Calendar.SECOND) / 60f
            val cap = if (square) androidx.compose.ui.graphics.StrokeCap.Square else androidx.compose.ui.graphics.StrokeCap.Round
            fun hand(frac: Float, len: Float, w: Float, col: Color) {
                if (glow) for (i in 1..3) drawLine(col.copy(alpha = 0.12f / i), o, at(frac, len), w + i * 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(col, o, at(frac, len), w, cap = cap)
            }
            hand(h, r * 0.5f, 5.dp.toPx(), ink)
            hand(mi, r * 0.76f, 3.5.dp.toPx(), accent)
            drawLine(second, at(se + 0.5f, r * 0.14f), at(se, r * 0.84f), 1.5.dp.toPx(), cap = cap)
            drawCircle(second, 3.5.dp.toPx(), o); drawCircle(c.surfaceContainer, 1.5.dp.toPx(), o)
        }
        if (wide) Column(Modifier.weight(1f)) {
            val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
            Text(SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(now.time), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Light), maxLines = 1)
            Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.time).let { if (look.uppercase) it.uppercase() else it }, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    }
}

/** This month as a grid, weeks starting on the phone's first day, today marked in the accent. Tap for the calendar. */
@Composable
private fun CalendarWidget(m: MakerModel) {
    val currentTime = rememberWidgetTime(60_000L)
    val today = remember(currentTime) { java.util.Calendar.getInstance().apply { time = currentTime } }
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    val displayed = remember(today, monthOffset) { (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, monthOffset) } }
    val look = LocalLook.current
    val c = MaterialTheme.colorScheme
    val first = remember(displayed) { (displayed.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) } }
    val start = first.firstDayOfWeek
    val lead = (first.get(java.util.Calendar.DAY_OF_WEEK) - start + 7) % 7
    val days = displayed.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    val day = today.get(java.util.Calendar.DAY_OF_MONTH)
    val names = remember(start, Locale.getDefault()) {
        val f = SimpleDateFormat("EEEEE", Locale.getDefault()); val cal = java.util.Calendar.getInstance()
        (0 until 7).map { i -> cal.set(java.util.Calendar.DAY_OF_WEEK, (start - 1 + i) % 7 + 1); f.format(cal.time) }
    }
    val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(displayed.time)
    // Three views by size: a date tile when tiny, this week as a strip when short, the whole month otherwise.
    BoxWithConstraints(Modifier.fillMaxSize().lookFrame(c.surfaceContainer).clickable(onClickLabel = "Open the calendar") { m.openTarget("phone:calendar") }) {
    val fit = Fit(maxWidth.value, maxHeight.value)
    val short = maxHeight < 170.dp; val roomy = maxHeight >= 96.dp
    if (fit.shape == "tiny" || (fit.shape == "tall" && maxWidth < 140.dp)) Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(SimpleDateFormat("EEE", Locale.getDefault()).format(today.time).let { if (look.uppercase) it.uppercase() else it }, style = MaterialTheme.typography.labelMedium, color = c.primary, maxLines = 1)
        Text(day.toString(), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        Text(SimpleDateFormat("MMM", Locale.getDefault()).format(today.time), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
    } else if (short) Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        if (roomy) WidgetHeader(SimpleDateFormat("MMMM", Locale.getDefault()).format(today.time), Icons.Outlined.CalendarMonth, trailing = today.get(java.util.Calendar.YEAR).toString())
        val weekStart = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -((get(java.util.Calendar.DAY_OF_WEEK) - start + 7) % 7)) }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val d = (weekStart.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, i) }
                val on = d.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
                val shape = if (look.corner < 8f) RoundedCornerShape(2.dp) else RoundedCornerShape(12.dp)
                Column(Modifier.weight(1f).padding(horizontal = 2.dp).clip(shape).then(if (on) Modifier.background(c.primary) else Modifier).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(names[i], style = MaterialTheme.typography.labelSmall, color = if (on) c.onPrimary else c.onSurfaceVariant, maxLines = 1)
                    Text(d.get(java.util.Calendar.DAY_OF_MONTH).toString(), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (on) c.onPrimary else if (d.before(today)) c.onSurfaceVariant.copy(alpha = 0.6f) else c.onSurface, maxLines = 1)
                }
            }
        }
    } else Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthOffset = (monthOffset - 1).coerceAtLeast(-1200) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronLeft, "Previous month") }
            TextButton(onClick = { monthOffset = 0 }, modifier = Modifier.weight(1f)) { Text("$month ${displayed.get(java.util.Calendar.YEAR)}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = { monthOffset = (monthOffset + 1).coerceAtMost(1200) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronRight, "Next month") }
        }
        val rows = (lead + days + 6) / 7
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth()) { names.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center) } }
            for (r in 0 until rows) Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                for (col in 0 until 7) {
                    val d = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (d in 1..days) {
                            val on = d == day && monthOffset == 0
                            val shape = if (look.corner < 8f) RoundedCornerShape(2.dp) else CircleShape
                            Box(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true).padding(1.dp).clip(shape)
                                .then(if (on) Modifier.background(c.primary) else Modifier), contentAlignment = Alignment.Center) {
                                Text(d.toString(), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = when { on -> c.onPrimary; monthOffset < 0 || (monthOffset == 0 && d < day) -> c.onSurfaceVariant.copy(alpha = 0.6f); else -> c.onSurface },
                                    fontWeight = if (on) FontWeight.Bold else null)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** The terminal widget that has the keyboard, and the key bar's sticky modifiers. Main-thread state. */
object TermInput {
    var focused by mutableStateOf<String?>(null)
    /** The terminal whose text is open for selecting (see SelectText). */
    var selecting by mutableStateOf<String?>(null)
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
}

/**
 * A real command line in Hatchlybox's own Linux. Every key goes straight to the shell (arrows, history and
 * completion work as in any terminal); the keys a phone keyboard lacks sit in a bar just above it.
 * With a command (a widget.json "terminal", or the built-in's style.json) it runs that program full screen.
 */
@Composable
private fun TerminalWidget(m: MakerModel, id: String, command: String = "", title: String = "", look: JSONObject? = m.widgetEngine.styles[id]) {
    val context = LocalContext.current
    val shell = remember(id) { Shells.get(context, id) }
    val st = WStyle(look)
    val program = command.isNotBlank()
    LaunchedEffect(m.basics, command) { if (m.basics && !program) shell.start(46) }
    // The input box is invisible: it holds a zero-width mark (so a backspace on "nothing" still reaches the
    // shell) and the letters typed since the last Enter, which are sent as they're typed.
    val mark = "\u200B"
    var field by remember { mutableStateOf(TextFieldValue(mark, TextRange(1))) }
    var sent by remember { mutableStateOf("") }
    val reset = { sent = ""; field = TextFieldValue(mark, TextRange(1)) }
    val focus = remember { FocusRequester() }
    val focused = TermInput.focused == id
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { if (!scroll.isScrollInProgress) runCatching { scroll.scrollTo(it) } } }
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(focused) { cursorOn = true; if (!focused) shell.toLatest(); while (focused) { delay(530); cursorOn = !cursorOn } }
    // An AI helper (claude, codex, opencode): Esc interrupts it (Ctrl+C would clear or quit), and it's working or ready.
    val agent = program && Agent.entries.any { it.bin == command.trim().substringBefore(' ') }
    val fontSize = st.num("fontSize", if (program) 10f else 11f).coerceIn(7f, 18f)
    val bg = st.back(Color(0xFF101216))
    val fg = color(st.str("text"), Color(0xFFD6DAE1)); val dim = fg.copy(alpha = 0.5f)
    val accent = color(st.str("prompt").ifBlank { st.str("accent") }, Color(0xFF8DB5FF))
    val mono = TextStyle(fontFamily = st.family(FontFamily.Monospace), fontSize = fontSize.sp, lineHeight = (fontSize * 1.36f).sp, color = fg)
    fun change(v: TextFieldValue) {
        val hadMark = v.text.startsWith(mark)
        val body = if (hadMark) v.text.substring(mark.length) else v.text
        if (!hadMark) shell.send("\u007f")
        val common = sent.commonPrefixWith(body).length
        if (sent.length > common) shell.send("\u007f".repeat(sent.length - common))
        var add = body.substring(common)
        if (add.isNotEmpty() && (TermInput.ctrl || TermInput.alt)) {
            // Ctrl turns a letter into its control code (Ctrl+C = interrupt); Alt sends Esc first. One key each.
            if (TermInput.ctrl) add = add.map { c -> if (c.uppercaseChar() in '@'..'_') (c.uppercaseChar().code and 0x1f).toChar() else c }.joinToString("")
            if (TermInput.alt) add = "\u001B" + add
            TermInput.ctrl = false; TermInput.alt = false
            shell.send(add); reset(); return
        }
        if (add.isNotEmpty()) shell.send(add.replace('\n', '\r'))
        sent = body
        field = TextFieldValue(mark + body, TextRange(mark.length + body.length), if (hadMark) v.composition else null)
    }
    val type = { runCatching { focus.requestFocus() }; Unit }
    if (TermInput.selecting == id) SelectText(shell) { TermInput.selecting = null }
    Box(Modifier.fillMaxSize().lookFrame(bg, corner = if (st.j?.has("corner") == true) st.num("corner", 22f).dp else null)) {
        Column(Modifier.fillMaxSize().padding(start = if (program) 10.dp else 14.dp, end = 6.dp, top = 4.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val busy = shell.alive && agent && shell.working
                // The dot breathes while an agent works.
                var dim2 by remember { mutableStateOf(false) }
                LaunchedEffect(busy) { while (busy) { dim2 = !dim2; delay(600) }; dim2 = false }
                val pulse by animateFloatAsState(if (dim2) 0.3f else 1f, tween(550), label = "busy")
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (shell.alive) MaterialTheme.colorScheme.primary.copy(alpha = pulse) else dim))
                Spacer(Modifier.width(8.dp))
                val status = when { !m.basics -> "setting up"; shell.alive && agent -> if (shell.working) "working" else "ready"; shell.alive -> "running"; shell.ended -> "stopped"; else -> "starting" }
                Text(title.ifBlank { if (program) command.substringBefore(' ') else "terminal" } + " · " + status, style = mono.copy(color = dim, fontSize = 10.sp), maxLines = 1, modifier = Modifier.weight(1f))
                if (program && !shell.alive && shell.ended) TermKey("restart", "Start it again", dim) { shell.ended = false }
                if (shell.alive) AttachKey(shell, dim)
                if (agent) TermKey("esc", "Interrupt the agent", dim) { shell.send("\u001B"); reset() }
                else TermKey("^C", "Interrupt", dim) { shell.send("\u0003"); reset() }
            }
            if (program) ProgramScreen(
                shell, command, mono, fg, bg, accent, focused && cursorOn, m.basics, focused, Modifier.weight(1f).fillMaxWidth().padding(end = 4.dp), type, m::external,
            ) else Box(
                Modifier.weight(1f).fillMaxWidth().padding(end = 8.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Type in the terminal") {
                        if (!shell.alive && m.basics) shell.start(46)
                        type()
                    },
            ) {
                val shown = shell.text.ifEmpty { if (m.basics) "Starting…" else "Linux is still installing. The terminal starts by itself when it's ready." }
                Text(
                    buildAnnotatedString { append(shown); if (focused) withStyle(SpanStyle(color = if (cursorOn) accent else Color.Transparent)) { append("▍") } },
                    // Swipes scroll it only while it's selected (has the keyboard); otherwise they're the home screen's.
                    style = mono, modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = focused),
                )
            }
            BasicTextField(
                field, ::change, singleLine = true, textStyle = mono.copy(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { shell.send("\r"); reset() }),
                modifier = Modifier.fillMaxWidth().height(1.dp).focusRequester(focus).onFocusChanged {
                    if (it.isFocused) TermInput.focused = id
                    else if (TermInput.focused == id) { TermInput.focused = null; TermInput.ctrl = false; TermInput.alt = false }
                },
            )
            Text(
                when { focused -> if (program) "swipe to scroll · tap a link to open it" else " "; shell.alive || !m.basics -> "tap to type"; else -> "tap to start again" },
                style = mono.copy(color = dim, fontSize = 10.sp), modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

/**
 * A program's full screen, cell by cell: backgrounds, then text in each run's colour. Non-ASCII glyphs
 * (box drawing, blocks, icons) are placed one per cell so they line up whatever the font's widths.
 * A tap types into it (and clicks there, when the program uses the mouse) or opens a link it shows. While it's
 * selected, a vertical swipe scrolls back through what the program printed (the mouse wheel, for programs on their
 * own full screen that take it); unselected, swipes are the home screen's.
 */
@Composable
private fun ProgramScreen(
    shell: Shell, command: String, mono: TextStyle, fg: Color, bg: Color, accent: Color, cursorOn: Boolean, ready: Boolean,
    selected: Boolean, modifier: Modifier, type: () -> Unit, open: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 512)
    val style = mono.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
    val cell = remember(style) { measurer.measure("MMMMMMMMMM", style).size.let { it.width / 10f to it.height.toFloat() } }
    BoxWithConstraints(modifier) {
        val cols = (constraints.maxWidth / cell.first).toInt().coerceAtLeast(10)
        val rows = (constraints.maxHeight / cell.second).toInt().coerceAtLeast(3)
        val size by rememberUpdatedState(cols to rows)
        // Started once the size is known; a later size change is a resize, not a restart.
        LaunchedEffect(ready, command, shell.ended, shell.restarts) {
            if (ready && !shell.ended) shell.start(cols, rows, command, fg.toArgbInt(), bg.toArgbInt(), cell.first.toInt() to cell.second.toInt())
        }
        LaunchedEffect(cols, rows) { delay(250); shell.resize(size.first, size.second) }
        val at = { o: Offset -> (o.x / cell.first).toInt().coerceIn(0, cols - 1) to (o.y / cell.second).toInt().coerceIn(0, rows - 1) }
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize()
                .pointerInput(cell, cols) {
                    detectTapGestures { o ->
                        fling?.cancel()
                        if (!shell.alive && shell.ended) { shell.ended = false; return@detectTapGestures }
                        val (c, r) = at(o)
                        linkAt(shell.lines, c, r, cols)?.let { open(it); return@detectTapGestures }
                        shell.mouse(0, c, r); type()
                    }
                }
                .pointerInput(cell, selected) {
                    if (!selected) return@pointerInput
                    val tracker = VelocityTracker()
                    var pulled = 0f
                    var where = Offset.Zero
                    // Finger down the screen: older lines. On its own full screen a program gets the wheel or arrows.
                    fun by(dy: Float) {
                        pulled += dy
                        val (c, r) = at(where)
                        while (pulled > cell.second) { pulled -= cell.second
                            if (!shell.fullScreen) shell.scrollBy(1) else if (!shell.mouse(64, c, r, release = false)) shell.send("\u001B[A") }
                        while (pulled < -cell.second) { pulled += cell.second
                            if (!shell.fullScreen) shell.scrollBy(-1) else if (!shell.mouse(65, c, r, release = false)) shell.send("\u001B[B") }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { fling?.cancel(); tracker.resetTracking(); pulled = 0f; where = it },
                        onDragEnd = {
                            var v = tracker.calculateVelocity().y
                            // A flick carries on and slows down (not for arrow keys: they'd overshoot).
                            if (!shell.fullScreen || shell.wantsMouse) fling = scope.launch {
                                while (abs(v) > 250f) { by(v * 0.016f); v *= 0.94f; delay(16) }
                            }
                        },
                    ) { ch, dy -> ch.consume(); tracker.addPosition(ch.uptimeMillis, ch.position); where = ch.position; by(dy) }
                },
        ) {
            val (cw, chh) = cell
            shell.lines.forEachIndexed { y, runs ->
                val top = y * chh
                // While shrinking, the program's old, taller screen lasts until the resize reaches it.
                if (top + chh > this.size.height + 0.5f) return@forEachIndexed
                runs.forEach { r -> if (r.bg != Grid.DEFAULT) drawRect(Color(r.bg), Offset(r.col * cw, top), androidx.compose.ui.geometry.Size(r.text.length * cw + 0.6f, chh + 0.6f)) }
                runs.forEach { r ->
                    if (r.text.isBlank()) return@forEach
                    val span = TextStyle(
                        color = Color(r.fg),
                        fontWeight = if (r.attr and Grid.BOLD != 0) FontWeight.Bold else null,
                        fontStyle = if (r.attr and Grid.ITALIC != 0) androidx.compose.ui.text.font.FontStyle.Italic else null,
                        textDecoration = when {
                            r.attr and Grid.UNDERLINE != 0 -> androidx.compose.ui.text.style.TextDecoration.Underline
                            r.attr and Grid.STRIKE != 0 -> androidx.compose.ui.text.style.TextDecoration.LineThrough
                            else -> null
                        },
                    )
                    // ASCII stretches in one go; anything else one glyph per cell.
                    var x = 0
                    val t = r.text
                    while (x < t.length) {
                        val c = t[x]
                        if (c.code < 128) {
                            var e = x
                            while (e < t.length && t[e].code < 128) e++
                            val part = t.substring(x, e)
                            if (part.isNotBlank()) drawText(measurer, part, Offset((r.col + x) * cw, top), style.merge(span), softWrap = false, maxLines = 1, size = androidx.compose.ui.geometry.Size((part.length + 1) * cw, chh + 1f))
                            x = e
                        } else {
                            val n = if (c.isHighSurrogate() && x + 1 < t.length) 2 else 1
                            drawText(measurer, t.substring(x, x + n), Offset((r.col + x) * cw, top), style.merge(span), softWrap = false, maxLines = 1, size = androidx.compose.ui.geometry.Size(cw * 2 + 1f, chh + 1f))
                            x += n
                        }
                    }
                }
            }
            val (cx, cy) = shell.cursor
            if (cursorOn && cx >= 0) drawRect(accent.copy(alpha = 0.75f), Offset(cx * cw, cy * chh), androidx.compose.ui.geometry.Size(cw, chh))
        }
        // Scrolled back: where in the history, and a way back to the live screen.
        if (shell.back > 0 && !shell.fullScreen) {
            val total = (shell.historySize + rows).coerceAtLeast(1)
            BoxWithConstraints(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(3.dp)) {
                val h = maxHeight
                Box(Modifier.padding(top = h * ((shell.historySize - shell.back).toFloat() / total)).height((h * (rows.toFloat() / total)).coerceAtLeast(12.dp)).fillMaxWidth().clip(CircleShape).background(fg.copy(alpha = 0.4f)))
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(14.dp)).background(accent)
                    .clickable(onClickLabel = "Back to the latest output") { fling?.cancel(); shell.toLatest() }.padding(horizontal = 10.dp, vertical = 5.dp),
            ) { Text("↓ latest", style = mono.copy(color = bg, fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
        }
        when {
            !ready -> Text("Linux is still installing. This starts by itself when it's ready.", style = mono.copy(color = fg.copy(alpha = 0.6f)))
            shell.ended -> Text(
                "$command has stopped · tap to start it again", style = mono.copy(color = accent, fontSize = 10.sp),
                modifier = Modifier.align(Alignment.BottomStart).background(bg.copy(alpha = 0.9f)).padding(4.dp),
            )
            shell.lines.isEmpty() -> Text("Starting $command…", style = mono.copy(color = fg.copy(alpha = 0.6f)))
        }
    }
}

/**
 * The terminal's text where Android's own selection works (long-press a word, drag the handles, Copy): on the home
 * screen a long-press moves the widget, so selecting happens here instead.
 */
@Composable
private fun SelectText(shell: Shell, close: () -> Unit) {
    val context = LocalContext.current
    val text = remember(shell) { shell.copyText().lines().takeLast(2000).joinToString("\n") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val v = rememberScrollState()
    // Opens at the end, where the latest output is.
    LaunchedEffect(Unit) { snapshotFlow { v.maxValue }.collect { if (it > 0 && it < Int.MAX_VALUE) v.scrollTo(it) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select text", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        android.widget.Toast.makeText(context, "Copied all the text", android.widget.Toast.LENGTH_SHORT).show(); close()
                    }) { Text("Copy all") }
                    TextButton(onClick = close) { Text("Done") }
                }
                Text("Long-press a word, drag the handles to choose, then Copy.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(v).padding(16.dp)) {
                    Text(text.ifBlank { "Nothing to select yet." }, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface))
                }
            }
        }
    }
}

/** A photo or a file for the program: copied into the Linux side (/root/attachments) and pasted in as its path. */
@Composable
private fun AttachKey(shell: Shell, color: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val take: (List<android.net.Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val paths = withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(context, it) } }
            // Pasted, so Claude and Codex see an image path and attach the picture.
            if (paths.isEmpty()) android.widget.Toast.makeText(context, "Couldn't read that file", android.widget.Toast.LENGTH_SHORT).show()
            else shell.paste(paths.joinToString(" ") + " ")
        }
    }
    val photos = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(10)) { take(it) }
    val files = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { take(it) }
    Box {
        Box(Modifier.heightIn(min = 48.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Attach a photo or a file") { menu = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AttachFile, "Attach", Modifier.size(16.dp), tint = color)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = {
                menu = false
                photos.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = {
                menu = false; files.launch(arrayOf("*/*"))
            })
        }
    }
}

/** Copies a picked file into /root/attachments under a safe name; its path inside Linux. */
private fun copyIn(context: Context, uri: android.net.Uri): String? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).trim('.').ifBlank { "file" }
    val dir = Linux(context).hostFile("/root/attachments").apply { mkdirs() }
    val stem = safe.substringBeforeLast('.'); val ext = if ('.' in safe) "." + safe.substringAfterLast('.') else ""
    var f = File(dir, safe); var n = 2
    while (f.exists()) f = File(dir, "$stem-${n++}$ext")
    (context.contentResolver.openInputStream(uri) ?: error("unreadable")).use { input -> f.outputStream().use { input.copyTo(it) } }
    "/root/attachments/" + f.name
}.getOrNull()

private val TermUrl = Regex("""https?://[^\s"'<>│┃|]+""")

/** A row of runs as the text in its cells. */
private fun rowText(runs: List<Grid.Run>, cols: Int): String {
    val out = CharArray(cols) { ' ' }
    runs.forEach { r -> r.text.forEachIndexed { i, ch -> val x = r.col + i; if (x in 0 until cols) out[x] = ch } }
    return String(out)
}

/** The link at a cell, if any; a long link wraps, so rows filled to the edge are read as one. */
private fun linkAt(lines: List<List<Grid.Run>>, col: Int, row: Int, cols: Int): String? {
    if (row !in lines.indices) return null
    val text = lines.map { rowText(it, cols) }
    fun full(y: Int) = text[y].trimEnd().length >= cols
    var start = row; while (start > 0 && full(start - 1)) start--
    var end = row; while (end + 1 < text.size && full(end)) end++
    val joined = (start..end).joinToString("") { text[it] }
    val at = (row - start) * cols + col
    return TermUrl.findAll(joined).firstOrNull { at in it.range }?.value?.trimEnd('.', ',', ')', ']', '>')
}

private fun Color.toArgbInt() = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

@Composable
private fun TermKey(label: String, description: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = description, onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)) }
}

/** Height of the terminal key bar above the keyboard: two rows of keys. */
private val KeyBarHeight = 92.dp

/** The keys a phone keyboard lacks, in two rows just above it, for the terminal that has the keyboard. */
@Composable
private fun KeyBar(modifier: Modifier) {
    val context = LocalContext.current
    val id = TermInput.focused ?: return
    val shell = remember(id) { Shells.get(context, id) }
    val key: (String) -> Unit = { seq -> shell.send(if (TermInput.alt) "\u001B$seq" else seq); TermInput.alt = false; TermInput.ctrl = false }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Page keys scroll the history of a program that keeps one (Claude, Codex); a full-screen one gets them.
    val page: (Int) -> Unit = { dir -> if (shell.scrollable) shell.scrollBy(dir * 12) else key(if (dir > 0) "\u001B[5~" else "\u001B[6~") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().height(KeyBarHeight)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BarKey("esc", "Escape") { key("\u001B") }
                BarKey("/", "Slash") { key("/") }
                BarKey("⇧tab", "Shift+Tab (Claude: switch mode)") { key("\u001B[Z") }
                BarKey("paste", "Paste from the clipboard") { clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { shell.paste(it) } }
                BarKey("↑", "Up") { key("\u001B[A") }
                BarKey("select", "Select and copy the terminal's text") { TermInput.selecting = id }
                BarKey("pgup", "Page up") { page(1) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BarKey("tab", "Tab") { key("\t") }
                BarKey("ctrl", "Control: the next key", on = TermInput.ctrl) { TermInput.ctrl = !TermInput.ctrl }
                BarKey("alt", "Alt: the next key", on = TermInput.alt) { TermInput.alt = !TermInput.alt }
                BarKey("←", "Left") { key("\u001B[D") }
                BarKey("↓", "Down") { key("\u001B[B") }
                BarKey("→", "Right") { key("\u001B[C") }
                BarKey("pgdn", "Page down") { page(-1) }
            }
        }
    }
}

@Composable
private fun RowScope.BarKey(label: String, description: String, on: Boolean = false, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(minOf(LocalLook.current.corner, 10f).dp))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClickLabel = description) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, maxLines = 1, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface))
    }
}

private sealed class PinItem {
    data class App(val item: LibItem) : PinItem()
    data class Folder(val label: String, val items: List<LibItem>) : PinItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsCarousel(m: MakerModel, id: String, shown: List<LibItem>, all: List<LibItem>, newApp: Boolean) {
    val st = WStyle(m.widgetEngine.styles[id])
    val scope = rememberCoroutineScope()

    val pinsArray = st.j?.optJSONArray("pins")
    val hasPins = pinsArray != null && pinsArray.length() > 0
    val displayItems: List<PinItem> = if (hasPins) {
        (0 until pinsArray!!.length()).mapNotNull { i ->
            when (val v = pinsArray.get(i)) {
                is String -> all.firstOrNull { it.key == v }?.let { PinItem.App(it) }
                is JSONObject -> {
                    val lbl = v.optString("label", "Folder")
                    val arr = v.optJSONArray("apps") ?: JSONArray()
                    val its = (0 until arr.length()).mapNotNull { j -> all.firstOrNull { it.key == arr.optString(j) } }
                    if (its.isNotEmpty()) PinItem.Folder(lbl, its) else null
                }
                else -> null
            }
        }
    } else shown.map { PinItem.App(it) }

    val indexed = displayItems.mapIndexed { i, item -> i to item }

    var pickerOpen by remember { mutableStateOf(false) }
    var folderMode by remember { mutableStateOf(false) }
    var folderLabel by remember { mutableStateOf("") }
    val folderSel = remember { mutableStateListOf<String>() }
    var expandedFolder by remember { mutableStateOf<PinItem.Folder?>(null) }

    fun rawPins(): MutableList<Any> = if (pinsArray != null) (0 until pinsArray.length()).mapTo(mutableListOf()) { pinsArray.get(it) } else mutableListOf()

    fun savePins(newPins: List<Any>) {
        scope.launch(Dispatchers.IO) {
            val j = JSONObject(st.j?.toString() ?: "{}").put("pins", JSONArray(newPins))
            val dir = m.linux.hostFile("$WIDGETS_DIR/builtin-$id")
            dir.mkdirs()
            File(dir, "style.json").writeText(j.toString())
            withContext(Dispatchers.Main) { m.widgetEngine.styles[id] = j }
        }
    }

    fun removePin(index: Int) = rawPins().let { if (index in it.indices) { it.removeAt(index); savePins(it) } }
    fun addApp(key: String) = rawPins().let { current -> if (!current.any { it is String && it == key }) { current += key; savePins(current) } }
    fun addFolder(label: String, keys: List<String>) = savePins(rawPins().also { it += JSONObject().put("label", label).put("apps", JSONArray(keys)) })
    fun pinnedKeys(): Set<String> = rawPins().flatMapTo(mutableSetOf()) { when (it) {
        is String -> listOf(it); is JSONObject -> it.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(); else -> emptyList()
    }}

    BoxWithConstraints(Modifier.fillMaxSize().then(if (st.bool("frame", false)) Modifier.lookFrame(MaterialTheme.colorScheme.surfaceContainer) else Modifier)) {
        val compact = maxHeight < 150.dp
        Box(Modifier.fillMaxSize()) {
            if (displayItems.isEmpty() && !newApp) {
                Box(Modifier.fillMaxSize().clickable { pickerOpen = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, "Add app", Modifier.size(28.dp), tint = Color.White.copy(alpha = 0.5f))
                }
            } else if (compact) {
                LazyRow(
                    Modifier.fillMaxSize().padding(start = 4.dp, end = 36.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (newApp) item(key = "building") { BuildingApp(m, Modifier.width(80.dp)) }
                    items(indexed, key = { it.first }) { (i, pin) ->
                        when (pin) {
                            is PinItem.App -> ShelfApp(m, pin.item, Modifier.width(80.dp), st, if (hasPins) { { removePin(i) } } else null)
                            is PinItem.Folder -> FolderTile(pin, Modifier.width(80.dp), st, { expandedFolder = pin }, { removePin(i) })
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(80.dp),
                    modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (newApp) item(key = "building") { BuildingApp(m, Modifier) }
                    gridItems(indexed, key = { it.first }) { (i, pin) ->
                        when (pin) {
                            is PinItem.App -> ShelfApp(m, pin.item, Modifier, st, if (hasPins) { { removePin(i) } } else null)
                            is PinItem.Folder -> FolderTile(pin, Modifier, st, { expandedFolder = pin }, { removePin(i) })
                        }
                    }
                }
            }
            // Always-visible + button pinned to top-right corner regardless of widget size.
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.28f))
                    .clickable(onClickLabel = "Add app") { pickerOpen = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Add, "Add app", Modifier.size(15.dp), tint = Color.White) }
        }
    }

    if (pickerOpen) ModalBottomSheet(onDismissRequest = { pickerOpen = false; folderMode = false; folderSel.clear(); folderLabel = "" }) {
        val already = pinnedKeys()
        val available = all.filter { it.key !in already }
        Column(Modifier.padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (folderMode) "Pick apps for folder" else "Add to apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!folderMode) TextButton(onClick = { folderMode = true }) { Text("Folder") }
                else TextButton(onClick = { folderMode = false; folderSel.clear() }) { Text("Cancel") }
            }
            if (folderMode) OutlinedTextField(value = folderLabel, onValueChange = { folderLabel = it }, label = { Text("Folder name") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), singleLine = true)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(80.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                gridItems(available, key = { it.key }) { item ->
                    PickerApp(item, st, selected = item.key in folderSel) {
                        if (folderMode) { if (item.key in folderSel) folderSel.remove(item.key) else folderSel.add(item.key) }
                        else { addApp(item.key); pickerOpen = false }
                    }
                }
            }
            if (folderMode) Button(
                onClick = { if (folderSel.isNotEmpty()) { addFolder(folderLabel.ifBlank { "Folder" }, folderSel.toList()); folderMode = false; folderSel.clear(); folderLabel = ""; pickerOpen = false } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                enabled = folderSel.isNotEmpty()
            ) { Text("Save folder (${folderSel.size} apps)") }
        }
    }

    expandedFolder?.let { folder ->
        ModalBottomSheet(onDismissRequest = { expandedFolder = null }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(folder.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(80.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    gridItems(folder.items, key = { it.key }) { item -> ShelfApp(m, item, Modifier, st) }
                }
            }
        }
    }
}

@Composable
private fun BuildingApp(m: MakerModel, modifier: Modifier) {
    Column(modifier.heightIn(min = 80.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "View build progress") { m.page = "job" }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        Text("Building…", style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ShelfApp(m: MakerModel, item: LibItem, modifier: Modifier, st: WStyle, onRemove: (() -> Unit)? = null) {
    Box(modifier) {
        Column(Modifier.heightIn(min = 80.dp).clip(RoundedCornerShape(12.dp)).goldTarget(item.key).clickable(onClickLabel = "Open ${item.label}") { m.used(item.key); item.open() }.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                item.icon(st.num("iconSize", 44f).coerceIn(28f, 48f).roundToInt())
                if (item.key in m.favourites) Icon(Icons.Outlined.Star, "Favourite", Modifier.align(Alignment.BottomEnd).size(16.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.dp), tint = MaterialTheme.colorScheme.primary)
                if (item.word != null) CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 2.dp, color = typeColor(item.type))
            }
            Text(item.label, style = MaterialTheme.typography.labelSmall.copy(shadow = wallShadow(), fontFamily = st.family(FontFamily.SansSerif)), color = color(st.str("text"), MaterialTheme.colorScheme.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (item.word != null) Text(item.word, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
            Icon(Icons.Outlined.Close, "Remove from apps", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FolderTile(folder: PinItem.Folder, modifier: Modifier, st: WStyle, onClick: () -> Unit, onRemove: () -> Unit) {
    Box(modifier) {
        Column(
            Modifier.heightIn(min = 80.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open ${folder.label}") { onClick() }.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (folder.items.isNotEmpty()) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { folder.items[0].icon(18) }
                        if (folder.items.size > 1) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { folder.items[1].icon(18) }
                        else Box(Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (folder.items.size > 2) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { folder.items[2].icon(18) }
                        else Box(Modifier.weight(1f))
                        if (folder.items.size > 3) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { folder.items[3].icon(18) }
                        else Box(Modifier.weight(1f))
                    }
                }
            }
            Text(folder.label, style = MaterialTheme.typography.labelSmall.copy(shadow = wallShadow(), fontFamily = st.family(FontFamily.SansSerif)), color = color(st.str("text"), MaterialTheme.colorScheme.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
            Icon(Icons.Outlined.Close, "Remove folder", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PickerApp(item: LibItem, st: WStyle, selected: Boolean, onClick: () -> Unit) {
    Box {
        Column(
            Modifier.heightIn(min = 80.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (selected) "Deselect ${item.label}" else "Select ${item.label}") { onClick() }.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                item.icon(st.num("iconSize", 44f).coerceIn(28f, 48f).roundToInt())
                if (item.word != null) CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 2.dp, color = typeColor(item.type))
            }
            Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun ArcSlot(state: LazyListState, key: Any, slot: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(
        Modifier.width(slot).fillMaxHeight().graphicsLayer {
            val info = state.layoutInfo
            val f = info.visibleItemsInfo.firstOrNull { it.key == key }
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val d = if (f == null || f.size <= 0) 2f else ((f.offset + f.size / 2f - mid) / f.size).coerceIn(-3f, 3f)
            val a = abs(d)
            val s = 1f - 0.17f * a.coerceAtMost(2f)
            scaleX = s; scaleY = s
            translationY = (a * a).coerceAtMost(5f) * 4.dp.toPx()
            rotationZ = d * 5f
            alpha = 1f - 0.3f * a.coerceAtMost(2.5f)
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A widget an agent made, or a note while it's being made. */
@Composable
private fun CustomWidget(m: MakerModel, ref: String, placedId: String) {
    val engine = m.widgetEngine
    val scope = rememberCoroutineScope()
    val w = engine.widgets.firstOrNull { it.id == ref }
    // A terminal widget: its program, full screen, in its own session.
    val term = w?.terminal
    if (term != null && w.error.isBlank()) {
        TerminalWidget(m, placedId, term.optString("command").trim(), term.optString("title").ifBlank { w.name }, look = term)
        return
    }
    if (w != null) {
        WidgetCard(w, m.linux.hostFile("$WIDGETS_DIR/$ref"), engine.loading[ref] == true, engine.errors[ref],
            act = { a -> scope.launch { engine.act(w, a, m::openTarget) } }, menu = {}, modifier = Modifier.fillMaxSize(), fill = true)
        return
    }
    val making = MakerRuntime.running && MakerRuntime.title == "Make a widget"
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            if (making) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                // Quiet for minutes (an agent's model can hang): said so, and it can always be stopped here.
                val quiet by produceState(0L, MakerRuntime.lastOutput) { while (true) { value = (System.currentTimeMillis() - MakerRuntime.lastOutput) / 60_000; delay(15_000) } }
                Muted(if (quiet >= 4) "${agentName(m.agent)} hasn't done anything for $quiet minutes. It may be stuck." else "${agentName(m.agent)} is making this widget")
                TextButton(onClick = { MakerRuntime.stop() }) { Text("Stop") }
            } else Muted("This widget isn't here any more. Long-press to remove it.")
        }
    }
}

// ---------- the create box ----------

/** What the create box can make, or just a chat: key, short name, icon, and the box's prompt. */
internal class CreateMode(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val prompt: String)

internal val CreateModes = listOf(
    CreateMode("auto", "Auto", Icons.Outlined.AutoAwesome, "Make anything, or ask"),
    CreateMode("app", "Android app", Icons.Outlined.PhoneAndroid, "Create an Android app"),
    CreateMode("game", "Game", Icons.Outlined.SportsEsports, "Make a game"),
    CreateMode("web", "Web app", Icons.Outlined.Language, "Create a web app"),
    CreateMode("linux", "Linux", Icons.Outlined.Computer, "Make a Linux app"),
    CreateMode("windows", "Windows", Icons.Outlined.DesktopWindows, "Make a Windows app"),
    CreateMode("omarchy", "Omarchy", Icons.Outlined.ViewQuilt, "Make an Omarchy plugin"),
    CreateMode("other", "Any system", Icons.Outlined.Devices, "Make it for any system: say which"),
    CreateMode("widget", "Widget", Icons.Outlined.Widgets, "Describe a widget for this page"),
    CreateMode("do", "Do on phone", Icons.Outlined.TouchApp, "Use the apps on this phone"),
    CreateMode("chat", "Chat", Icons.Outlined.ChatBubbleOutline, "Ask anything"),
)

/**
 * The box to make an app (or a game, a web app, an app for another system, a widget), or just to chat,
 * pinned to the bottom over everything, with the way to the library just above it. The small chip at its
 * start picks what it does. Faded while idle; a tap anywhere on the home screen brings it up with a nudge.
 */
@Composable
private fun CreateBar(
    m: MakerModel, web: AppModel, createWeb: (String) -> Boolean, items: List<LibItem>, bump: () -> Float, awake: () -> Float,
    pages: Int, page: Int, openLibrary: () -> Unit, modifier: Modifier,
) {
    var idea by rememberSaveable { mutableStateOf(m.draft("idea")) }
    LaunchedEffect(idea) { m.saveDraft("idea", idea) }
    var focused by remember { mutableStateOf(false) }
    val picking = remember { mutableStateOf(false) }
    val active = focused || idea.isNotBlank()
    val needsAgent = m.agentsInstalled && (!m.agentOk(m.agent) || m.justConnected == m.agent)
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer {
                val b = sin(bump() * PI).toFloat()
                alpha = if (active || needsAgent || picking.value) 1f else 0.58f + 0.42f * awake()
                translationY = -b * 5.dp.toPx()
                scaleX = 1f + 0.018f * b; scaleY = scaleX
            }
            .padding(horizontal = 16.dp).padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedVisibility(!active) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (pages > 1 || page >= pages) Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(pages) { i ->
                        val on = page == i
                        // A small dot to look at, in a bigger place to tap.
                        Box(Modifier.size(width = 22.dp, height = 18.dp).clip(RoundedCornerShape(9.dp))
                            .clickable(onClickLabel = "Page ${i + 1}") { m.homePage = i }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = if (on) 14.dp else 6.dp, height = 6.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    // The page that isn't there yet.
                    if (page >= pages) Icon(Icons.Outlined.Add, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // One small line, so the box below keeps the room.
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open the library", onClick = openLibrary).padding(horizontal = 10.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    Text(if (LocalLook.current.uppercase) "SWIPE UP FOR LIBRARY" else "Swipe up for library", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp, shadow = wallShadow()), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        }
        AnimatedVisibility(needsAgent) { AgentNotice(m, m.agent) }
        HomeStatus(m)
        AnimatedVisibility(active && m.boxOn("agent")) { AgentPicker(m) }
        // The chat, put away: one small line brings it back.
        AnimatedVisibility(!AgentChat.shown && AgentChat.messages.isNotEmpty() && !AgentChat.tucked) { ChatPill(m) }
        CreatorBox(m, web, createWeb, items, idea, { idea = it }, page, pages, picking = picking, onFocus = { focused = it })
    }
}

/** More than one thing to do ("open Spotify and play jazz"): the agent does those on the screen, not a single quick action. */
internal fun manySteps(text: String) = Regex("""(?i)\b(and then|then|after that|and)\b""").containsMatchIn(text.trim())

/**
 * The create box itself, on the home screen and at the top of the library: the chip that picks what it
 * makes, the text, and the button. What's typed also offers quick actions (open an app, call or text
 * someone, a timer...) as a slim row of suggestions, with the rest of the best one shown faded after the
 * text; they never take over: Enter runs one only in Auto, when the text starts with a command and matches.
 */
@Composable
internal fun CreatorBox(
    m: MakerModel, web: AppModel, createWeb: (String) -> Boolean, items: List<LibItem>, idea: String, setIdea: (String) -> Unit,
    page: Int, pages: Int, modifier: Modifier = Modifier, quickBelow: Boolean = false, appsInQuick: Boolean = true, placeholder: String? = null,
    picking: MutableState<Boolean> = remember { mutableStateOf(false) }, onFocus: (Boolean) -> Unit = {}, searchHit: Quick? = null,
    goldKey: String = "send",
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var queueDismissed by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var references by m::requestReferences
    fun clearAccepted() { setIdea(""); references = ""; m.saveDraft("references", "") }
    if (expanded) RequestEditor(m, idea, setIdea, references, { references = it }, { expanded = false })
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val ideaNow by rememberUpdatedState(idea)
    LaunchedEffect(m.restoredRequest) { m.restoredRequest?.let { setIdea(it); m.restoredRequest = null } }
    val mode = CreateModes.firstOrNull { it.key == m.createMode && it.key !in m.boxModesOff } ?: CreateModes[0]
    val idle = !m.busy && !MakerRuntime.running && !m.installing && !m.godotInstalling
    LaunchedEffect(focused, idle, idea.isBlank()) { if (!focused || idle || idea.isBlank()) { queueDismissed = false; queueOpen = false } }
    val canBuild = idea.isNotBlank() && m.basics && !m.deciding && when (mode.key) {
        "auto" -> true
        "web" -> web.projects.none { it.chat.running }
        "app", "game" -> m.pendingIdea.isBlank() && (!m.ready || idle)
        "chat", "do" -> !AgentChat.running && !m.agentsBusy
        else -> idle
    }
    // Starts what a mode makes; true when it started (the box then empties).
    val start: (String, String) -> Boolean = { key, original ->
            val text = withReferences(original, references)
            when (key) {
                "app" -> m.create(text)
                "game" -> m.create("A game: $text")
                // A web app is a creation like any other, made by the engine (it opens right here when it's done).
                "web" -> m.createFor("web", text)
                "widget" -> m.makeWidget(text)?.let { id ->
                    // On the page you're on (the "new page" past the last one makes it); it takes its own size once made.
                    val on = if (page >= pages) m.launcher.addPage() else page
                    val to = m.launcher.add(on, "custom", id, sizeLater = true)
                    if (to != page) m.homePage = to
                    if (to != on) m.notice = "This page was full, so the widget is going on a new page."
                    true
                } ?: false
                "chat" -> if (!m.agentsInstalled) { m.stillInstalling("Chat works"); false } else { AgentChat.send(context, runCatching { Agent.valueOf(m.agent.uppercase()) }.getOrDefault(Agent.CLAUDE), text) }
                // Using the phone's apps: the agent does it on the screen, step by step, and the chat follows it.
                "do" -> if (!m.agentsInstalled) { m.stillInstalling("Doing things on the phone works"); false }
                    else if (AgentChat.running) { m.notice = "${agentName(m.agent)} is still on the last request. Try again when it's done."; false }
                    else {
                        val accepted = AgentChat.send(context, runCatching { Agent.valueOf(m.agent.uppercase()) }.getOrDefault(Agent.CLAUDE),
                            "Do this on the phone for me, using its apps and the screen (nothing new to build): $text", display = text)
                        m.notice = "${agentName(m.agent)} is doing it on the phone. The chat line shows how it goes."
                        accepted
                    }
                // Could go either way: the box keeps the text and offers the choices.
                "ask" -> { m.unsure = original; m.notice = "Do you want this done on the phone, or something made? Pick one above the box."; false }
                else -> m.createFor(key, text)
            }
    }
    val build = {
        if (canBuild && m.agentsInstalled && !m.agentOk(m.agent)) m.connect(m.agent)
        else if (canBuild) {
            val text = idea.trim()
            if (mode.key == "auto") {
                focusManager.clearFocus()
                m.unsure = null
                m.decide(text) { key ->
                    val what = when (key) {
                        "app" -> "an Android app"; "game" -> "a game"; "web" -> "a web app"; "linux" -> "a Linux app"; "windows" -> "a Windows app"
                        "omarchy" -> "an Omarchy plugin"; "widget" -> "a widget"; "other" -> "an app for the system you named"; else -> ""
                    }
                    if (ideaNow.trim() == text && start(key, text)) { if (ideaNow.trim() == text) clearAccepted(); if (what.isNotBlank()) m.notice = "Auto: making $what. Pick a type on the left of the box to choose yourself." }
                }
            } else if (start(mode.key, text)) { clearAccepted(); focusManager.clearFocus() }
        }
    }

    // Quick actions: contacts are looked up off the main thread, once Android's permission is given.
    val cmd = remember(idea) { commandOf(idea) }
    val needPeople = cmd?.contacts == true
    var contactsOk by remember { mutableStateOf(hasContacts(context)) }
    val contactOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(contactOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) contactsOk = hasContacts(context) }
        contactOwner.lifecycle.addObserver(observer)
        onDispose { contactOwner.lifecycle.removeObserver(observer) }
    }
    val askContacts = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        contactsOk = ok
        if (!ok) m.notice = "Without contacts, calling takes a number. You can allow contacts in Android's settings for Hatchlybox."
    }
    val contactQuery = "${cmd?.kind}:${cmd?.rest}"
    val peopleResult by produceState("" to emptyList<Person>(), contactQuery, contactsOk) {
        value = "" to emptyList()
        delay(200)
        val result = if (needPeople && contactsOk) withContext(Dispatchers.IO) { findPeople(context, cmd!!.rest, if (cmd.kind == "email") "email" else "phone") } else emptyList()
        value = contactQuery to result
    }
    val people = peopleResult.second.takeIf { peopleResult.first == contactQuery }.orEmpty()
    val quick = if (references.isNotBlank() && references != "[]" || idea.isBlank() || !m.boxOn("quick")) emptyList() else quickActions(context, m, idea, items, people, appsInQuick) +
        (if (needPeople && !contactsOk) listOf(Quick("contacts", "Allow contacts", "To find who to ${cmd!!.verb.lowercase()}", "", { s -> Icon(Icons.Outlined.Contacts, null, Modifier.size((s * 0.62f).dp), tint = MaterialTheme.colorScheme.primary) }) {
            askContacts.launch(android.Manifest.permission.READ_CONTACTS)
        }) else emptyList())
    // The local model, for what the rules didn't catch: a suggestion to tap (or Enter in Auto), never run by itself.
    var proposal by remember { mutableStateOf<Pair<String, Choice>?>(null) }
    LaunchedEffect(focused) { if (focused) { Gold.active = goldKey; if (Assist.state == "ready") Assist.warm(context) } }
    val rulesFound = references.isNotBlank() && references != "[]" || quick.any { it.key != "contacts" }
    LaunchedEffect(idea, Assist.state, mode.key, rulesFound) {
        if (focused) Assist.invalidate(idea.trim())
        proposal = proposal?.takeIf { it.first == idea }
        val t = idea.trim()
        if (!m.boxOn("actions") || Assist.state != "ready" || mode.key != "auto" || t.split(Regex("\\s+")).size < 2 || manySteps(t) || rulesFound || m.namesCreation(t) || Hands.candidates(t).isEmpty()) { proposal = null; return@LaunchedEffect }
        delay(550)
        proposal = Assist.choose(context, t)?.let { idea to it }
    }
    val handIcon: (androidx.compose.ui.graphics.vector.ImageVector) -> @Composable (Int) -> Unit = { v -> { s -> Icon(v, null, Modifier.size((s * 0.62f).dp), tint = Color(0xFFE2B33C)) } }
    val smart = proposal?.takeIf { it.first == idea }?.second?.let { c ->
        Quick("hand:" + c.hand.name, Act.describe(c.hand, c.args), if (c.hand.level == Level.SECURE) "Tap to do it · checks it's you" else "Tap to do it", "",
            handIcon(c.hand.icon), gold = false) { Act.perform(context, m, c, items) }
    }
    // Something to do on the phone, and no local model yet: offered once, quietly.
    val offer = if (m.boxOn("actions") && Assist.state != "ready" && mode.key == "auto" && !rulesFound && idea.trim().split(' ').size >= 2 && !m.namesCreation(idea) && Hands.candidates(idea).isNotEmpty())
        Quick("assist:get", if (Assist.state == "downloading") "Getting the local model… ${(Assist.progress * 100).toInt()}%" else "Do this on the phone",
            if (Assist.state == "downloading") "" else "Get the local model (${Assist.MODEL_MB} MB, works offline)", "", handIcon(Icons.Outlined.AutoAwesome), gold = false) {
            if (Assist.state != "downloading" && Legal.getModel(context)) m.notice = "Getting the local model (${Assist.MODEL_MB} MB). Actions work as soon as it's in."
        } else null
    // Auto couldn't tell: the choices, for as long as the text stays the same.
    val choices = m.unsure?.takeIf { it == idea.trim() }?.let { text ->
        fun choice(key: String, label: String, hint: String, icon: androidx.compose.ui.graphics.vector.ImageVector) =
            Quick("unsure:$key", label, hint, "", { s -> Icon(icon, null, Modifier.size((s * 0.62f).dp), tint = MaterialTheme.colorScheme.primary) }, gold = false) {
                m.unsure = null
                if (start(key, text)) { clearAccepted(); focusManager.clearFocus() }
            }
        listOf(
            choice("do", "Do it on the phone", "Use the apps you have", Icons.Outlined.TouchApp),
            choice("app", "Make an app", "Build something new", Icons.Outlined.Build),
            choice("widget", "Make a widget", "For the home screen", Icons.Outlined.Widgets),
            choice("chat", "Just answer", "Talk it over", Icons.Outlined.ChatBubbleOutline),
        )
    }.orEmpty()
    val shown = choices + listOfNotNull(smart) + quick + listOfNotNull(offer)
    val scope = rememberCoroutineScope()
    val pick: (Quick) -> Unit = { q ->
        q.go()
        if (q.gold) scope.launch { Gold.fly(goldKey, q.target) }
        if (q.key != "contacts" && q.key != "wa:none" && q.key != "assist:get") { clearAccepted(); focusManager.clearFocus() }
    }
    // Enter runs the best match only for a command typed in Auto (in a chosen mode it always makes).
    // For people and the rest, only once the whole match is typed ("text Ana", "text Ana see you"); "open" takes the best app.
    val direct = quick.firstOrNull()?.takeIf { cmd != null && mode.key == "auto" && it.key != "contacts" && it.key != "wa:none" &&
        !manySteps(cmd.rest) && (if (cmd.kind == "open") true else idea.trim().startsWith(it.complete, ignoreCase = true)) }
        ?: searchHit?.takeIf { cmd == null && mode.key == "auto" }
        ?: smart?.takeIf { mode.key == "auto" }
    // The rest of the best match, faded after the text ("open ma" → "ps").
    val ghost = quick.firstOrNull()?.complete?.takeIf { m.boxOn("ghost") && focused && idea.isNotBlank() && !idea.contains('\n') && it.length > idea.length && it.startsWith(idea, ignoreCase = true) }?.drop(idea.length)
    // Enter before the local model has answered ("lock my phone"): wait for it, so a phone action isn't handed to an agent.
    var choosing by remember { mutableStateOf(false) }
    val go: () -> Unit = {
        val text = idea.trim()
        if (choosing) Unit
        else if (direct != null) pick(direct)
        else if ((references.isBlank() || references == "[]") && m.boxOn("actions") && mode.key == "auto" && cmd == null && Assist.state == "ready" && text.split(Regex("\\s+")).size >= 2 && !manySteps(text) && !m.namesCreation(text) && Hands.candidates(text).isNotEmpty()) {
            choosing = true
            scope.launch {
                val c = try { Gold.around(goldKey) { Assist.choose(context, text) } } finally { choosing = false }
                if (ideaNow.trim() != text) Unit
                else if (c == null) { m.unsure = text; m.notice = "Choose how to handle this request." }
                else { clearAccepted(); focusManager.clearFocus(); Act.perform(context, m, c, items, goldKey) }
            }
        }
        else build()
    }
    Column(modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!quickBelow) AnimatedVisibility(shown.isNotEmpty()) { QuickRow(shown, pick) }
        val look = LocalLook.current
        Box(Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(minOf(look.corner, 24f).dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = maxOf(0.75f, look.cardOpacity)),
            border = BorderStroke(maxOf(1f, look.border).dp, if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else lookBorder(look).let { if (look.border > 0f) it else it.copy(alpha = 0.7f) }),
            modifier = Modifier.fillMaxWidth().goldTarget("box:$goldKey", minOf(look.corner, 24f).dp),
        ) {
            Row(Modifier.heightIn(min = 46.dp).padding(start = 6.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // What it makes: one small chip, a tap away from the others.
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "Choose what to make") { picking.value = true }
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(mode.icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(mode.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                    DropdownMenu(expanded = picking.value, onDismissRequest = { picking.value = false }) {
                        CreateModes.filter { it.key !in m.boxModesOff }.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.label) }, leadingIcon = { Icon(c.icon, null) },
                                trailingIcon = { if (c.key == mode.key) Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp)) },
                                onClick = { m.chooseCreateMode(c.key); picking.value = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (idea.isEmpty()) {
                        if (m.deciding) Text("Working out what to make…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        else if (focused && m.boxOn("examples") && (mode.key == "app" || mode.key == "web" || mode.key == "auto")) ExamplePrompt(mode.key == "web", MaterialTheme.typography.bodyMedium)
                        else Text(placeholder ?: if (mode.key == "chat") "Ask ${agentName(m.agent)} anything" else mode.prompt, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (ghost != null) Text(
                        buildAnnotatedString { withStyle(SpanStyle(color = Color.Transparent)) { append(idea) }; withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append(ghost) } },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    )
                    BasicTextField(
                        idea, setIdea, maxLines = if (focused) 5 else 1,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (mode.key == "chat") ImeAction.Send else ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (!idle && idea.isNotBlank() && direct == null) { queueOpen = true; queueDismissed = false } else go() }, onSend = { if (!idle && idea.isNotBlank() && direct == null) { queueOpen = true; queueDismissed = false } else go() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Request: ${mode.label}" }.onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
                    )
                }
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
                    Icon(if (references.isNotBlank() && references != "[]") Icons.Outlined.AttachFile else Icons.Default.OpenInFull,
                        if (references.isNotBlank() && references != "[]") "Expand request · files attached" else "Expand request and attach files", Modifier.size(18.dp))
                }
                FilledIconButton(onClick = { if (!idle && idea.isNotBlank() && direct == null) { queueOpen = true; queueDismissed = false } else go() }, enabled = (!idle && idea.isNotBlank()) || canBuild || direct != null, modifier = Modifier.size(48.dp).goldTarget(goldKey)) {
                    if (choosing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else if (direct != null) Icon(Icons.Outlined.NorthEast, "${direct.hint} ${direct.label}", Modifier.size(18.dp))
                    else if (!idle && idea.isNotBlank()) Icon(Icons.Outlined.PlaylistAdd, "Choose type to queue", Modifier.size(18.dp))
                    else Icon(Icons.Default.ArrowUpward, if (mode.key == "chat") "Send to ${agentName(m.agent)}" else "${mode.prompt}: start", Modifier.size(18.dp))
                }
            }
        }
        DropdownMenu(
            expanded = idea.isNotBlank() && !idle && (queueOpen || (focused && !queueDismissed)),
            onDismissRequest = { queueDismissed = true; queueOpen = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 280.dp),
        ) {
            Text("Choose type to queue", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            CreateModes.filter { it.key != "auto" && it.key != "ask" && it.key !in m.boxModesOff }.forEach { c ->
                DropdownMenuItem(text = { Text("Queue ${c.label.lowercase()}") }, leadingIcon = { Icon(c.icon, null, Modifier.size(18.dp)) }, onClick = {
                    when (val r = RequestQueue.add(context, c.key, m.agent, withReferences(idea.trim(), references), page)) {
                        is RequestQueue.AddResult.Added -> { clearAccepted(); queueDismissed = true; queueOpen = false; focusManager.clearFocus(); m.notice = "Queued. Manage tasks in Settings → Tasks and storage." }
                        is RequestQueue.AddResult.Blocked -> m.notice = "Could not queue: ${r.reason} Your draft is kept."
                    }
                })
            }
        }
        }
        if (quickBelow) AnimatedVisibility(shown.isNotEmpty()) { QuickRow(shown, pick) }
    }
}

// ---------- sheets ----------

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = if (trailing != null) 8.dp else 24.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** The widgets that can be added: the built-in ones, the ones agents made, and a line to describe a new one. */
@Composable
private fun AddWidget(m: MakerModel, add: (kind: String, ref: String, size: String) -> Unit) {
    var ask by rememberSaveable { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Add a widget", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Box(Modifier.padding(horizontal = 24.dp)) { Muted("Each one takes the theme's colours, title style, corners and background.") }
        val section: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)) }
        val example = { ex: String, name: String, size: String -> add("custom", m.widgetEngine.newFromExample(ex, name), size) }
        section("Agents")
        // An agent in a terminal of its own, full screen, ready to type to.
        Agent.entries.forEach { ag ->
            SheetItem(Icons.Outlined.SmartToy, ag.label, "${ag.label} running full screen, ready to type to") {
                add("agent", ag.bin, "")
            }
        }
        section("Time")
        SheetItem(Icons.Outlined.Schedule, "Clock", "Time, date, timezone and your next alarm") { add("clock", "", "") }
        SheetItem(Icons.Outlined.AccessTime, "Analog clock", "A clock face in the theme's style") { add("analog", "", "") }
        SheetItem(Icons.Outlined.CalendarMonth, "Calendar", "Browse months, see today, tap the title to return") { add("calendar", "", "") }
        SheetItem(Icons.Outlined.Today, "Today", "The date large, and how much of the year is left") { example("date", "Today", "small") }
        section("Phone and Linux")
        SheetItem(Icons.Outlined.BatteryFull, "Battery", "The phone's charge as a ring, and if it's charging") { example("battery", "Battery", "small") }
        SheetItem(Icons.Outlined.Memory, "System", "Processor, memory and storage as rings, with a history") { example("system", "System", "tall") }
        SheetItem(Icons.Outlined.Info, "Fetch", "This Linux in a fetch: logo, system, kernel, uptime, packages, memory, colours") { example("fetch", "Fetch", "wide") }
        SheetItem(Icons.Outlined.Terminal, "Terminal", "A command line in Hatchlybox's Linux") { add("terminal", "", "") }
        SheetItem(Icons.Outlined.Apps, "Your apps", "Named shortcuts, live build states and a grid that fits the tile") { add("apps", "", "") }
        section("Live")
        SheetItem(Icons.Outlined.WbSunny, "Weather", "Now, feels like, wind and rain, and the next three days") { example("forecast", "Weather", "tall") }
        SheetItem(Icons.Outlined.WbSunny, "Weather, small", "Where you are, with today's hours as a line") { example("weather", "Weather", "small") }
        SheetItem(Icons.Outlined.TrendingUp, "Hacker News", "The top stories, each opens when tapped") { example("headlines", "Hacker News", "large") }
        SheetItem(Icons.Outlined.WaterDrop, "Water", "Glasses of water today, with a goal") { example("water", "Water", "small") }
        section("Play")
        SheetItem(Icons.Outlined.Pets, "Wifoz", "A tabby cat who lives in the card: climbs, scratches, plays and chases what you tap") { example("wifoz", "Wifoz", "wide") }
        val yours = m.widgetEngine.widgets.filter { it.builtin.isBlank() && agentOf(it) == null }
        var deleting by remember { mutableStateOf<HomeWidget?>(null) }
        if (yours.isNotEmpty()) {
            section("Your widgets")
            yours.forEach { w ->
                SheetItem(Icons.Outlined.Widgets, w.name, w.spec?.optString("description").orEmpty().ifBlank { "Made by you or an agent" },
                    trailing = { IconButton(onClick = { deleting = w }) { Icon(Icons.Outlined.DeleteOutline, "Delete ${w.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                ) { add("custom", w.id, w.size) }
            }
        }
        deleting?.let { w ->
            val scope = rememberCoroutineScope()
            AlertDialog(
                onDismissRequest = { deleting = null }, title = { Text("Delete ${w.name}?") },
                text = { Text("It goes from your widgets and from the home screen. A copy stays in /root/widgets/.trash.") },
                confirmButton = { TextButton(onClick = { deleting = null; m.launcher.removeRef("custom", w.id); scope.launch { m.widgetEngine.remove(w.id) } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
        Text("Or describe a new one", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp))
        val make = { m.makeWidget(ask)?.let { id -> ask = ""; add("custom", id, "") }; Unit }
        OutlinedTextField(
            value = ask, onValueChange = { ask = it }, maxLines = 3, shape = RoundedCornerShape(24.dp),
            placeholder = { Text("The weather here, a to-do list…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { make() }),
            trailingIcon = {
                FilledIconButton(onClick = make, enabled = ask.isNotBlank() && !MakerRuntime.running, modifier = Modifier.padding(end = 6.dp).size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Make the widget with ${agentName(m.agent)}")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { Muted("${agentName(m.agent)} makes it; it shows on this page when it's ready.") }
    }
}

/** The agent a widget folder only runs (a Claude, Codex or OpenCode terminal), or null. */
private fun agentOf(w: HomeWidget): Agent? {
    val command = w.terminal?.optString("command")?.trim() ?: return null
    return Agent.entries.firstOrNull { it.bin == command }
}
