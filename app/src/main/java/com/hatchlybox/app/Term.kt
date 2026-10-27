package com.hatchlybox.nightly

object Ansi {
    private val escapes = Regex("""\u001B(\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(\u0007|\u001B\\)|[()][0-9A-Za-z]|[@-Z\\-_])""")

    // Drops colours and cursor codes, and keeps only the last overwrite of a line redrawn with \r.
    fun strip(s: String): String = escapes.replace(s, "").split('\n').joinToString("\n") { it.substringAfterLast('\r') }
}

// A tiny terminal: enough of VT100 for login prompts and menus (cursor moves, erase, OSC 8 links).
class Screen(private val cols: Int = Linux.PTY_COLS, private val rows: Int = 40) {
    private class Line(val text: StringBuilder = StringBuilder(), var wrapped: Boolean = false)

    private val lines = arrayListOf(Line())
    private var row = 0
    private var col = 0
    private var saved = 0 to 0
    private var state = 0 // 0 text, 1 ESC, 2 CSI, 3 OSC, 4 ESC inside OSC, 5 charset
    private val seq = StringBuilder()
    val links = linkedSetOf<String>()

    private val top get() = maxOf(0, lines.size - rows)

    fun feed(s: String) {
        for (c in s) when (state) {
            0 -> when (c) {
                '\u001B' -> state = 1
                '\r' -> col = 0
                '\n' -> { lines[row].wrapped = false; down() }
                '\b' -> if (col > 0) col--
                '\t' -> col = minOf(cols - 1, (col / 8 + 1) * 8)
                '\u0007', '\u0000' -> {}
                else -> put(c)
            }
            1 -> when (c) {
                '[' -> { state = 2; seq.clear() }
                ']' -> { state = 3; seq.clear() }
                '(', ')' -> state = 5
                '7' -> { saved = row to col; state = 0 }
                '8' -> { row = saved.first.coerceIn(0, lines.size - 1); col = saved.second; state = 0 }
                'M' -> { if (row > 0) row--; state = 0 }
                else -> state = 0
            }
            2 -> if (c in '@'..'~') { csi(c); state = 0 } else if (seq.length < 8192) seq.append(c) else { seq.clear(); state = 0 }
            3 -> when (c) {
                '\u0007' -> { osc(); state = 0 }
                '\u001B' -> state = 4
                else -> seq.append(c)
            }
            4 -> { osc(); state = 0 }
            5 -> state = 0
        }
    }

    private fun down() {
        row++
        if (lines.size >= 2000 && row >= lines.size) { lines.removeAt(0); row--; saved = (saved.first - 1).coerceAtLeast(0) to saved.second }
        while (lines.size <= row) lines += Line()
    }

    private fun put(c: Char) {
        if (col >= cols) { lines[row].wrapped = true; down(); col = 0 }
        val t = lines[row].text
        while (t.length < col) t.append(' ')
        if (col < t.length) t.setCharAt(col, c) else t.append(c)
        col++
    }

    private fun osc() {
        // OSC 8 ;params; URI — a hyperlink
        if (seq.startsWith("8;")) seq.substring(2).substringAfter(';').takeIf { it.startsWith("http") }?.let { links += it; if (links.size > 100) links.remove(links.first()) }
    }

    private fun csi(final: Char) {
        val private = seq.startsWith("?")
        val ps = seq.toString().trimStart('?', '>', '=').split(';').map { it.toIntOrNull()?.coerceIn(0, 500) }
        fun p(i: Int, def: Int = 1) = ps.getOrNull(i)?.takeIf { it > 0 } ?: def
        if (private) return
        when (final) {
            'A' -> row = maxOf(top, row - p(0))
            'B' -> repeat(p(0)) { down() }
            'C' -> col = minOf(cols - 1, col + p(0))
            'D' -> col = maxOf(0, col - p(0))
            'E' -> { repeat(p(0)) { down() }; col = 0 }
            'F' -> { row = maxOf(top, row - p(0)); col = 0 }
            'G' -> col = (p(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val target = top + (p(0) - 1).coerceIn(0, rows - 1)
                while (lines.size <= target) lines += Line()
                row = target; col = (p(1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> when (ps.getOrNull(0) ?: 0) {
                0 -> { eraseRight(); while (lines.size > row + 1) lines.removeAt(lines.size - 1) }
                1 -> for (r in top until row) lines[r].text.setLength(0)
                else -> { for (r in top until lines.size) lines[r].text.setLength(0) }
            }
            'K' -> when (ps.getOrNull(0) ?: 0) {
                0 -> eraseRight()
                1 -> { val t = lines[row].text; for (i in 0 until minOf(col + 1, t.length)) t.setCharAt(i, ' ') }
                else -> lines[row].text.setLength(0)
            }
        }
    }

    private fun eraseRight() {
        val t = lines[row].text
        if (col < t.length) t.setLength(col)
    }

    // Logical lines (soft-wrapped lines joined), trailing blanks dropped.
    fun text(): String {
        val sb = StringBuilder()
        lines.forEach { l -> sb.append(l.text.trimEnd()); if (!l.wrapped) sb.append('\n') }
        return sb.toString().trimEnd()
    }

    fun urls(): List<String> =
        (links + Regex("""https?://[^\s"'<>]+""").findAll(text()).map { it.value.trimEnd('.', ',', ')') }).distinct()
}

/**
 * A full-screen terminal: a grid of cells with colours, for programs that draw the whole screen
 * (OpenCode, Claude, Codex, vim, htop). xterm's common sequences, an alternate screen, scroll regions,
 * mouse modes, and replies to the questions such programs ask (cursor position, colours, size).
 * Not thread-safe: the caller locks it.
 */
class Grid(cols: Int, rows: Int, private val reply: (String) -> Unit = {}) {
    var cols = cols.coerceAtLeast(2); private set
    var rows = rows.coerceAtLeast(2); private set
    /** One cell: its text (a char, or a surrogate pair), colours (ARGB, or DEFAULT) and attributes. */
    class Cell(var ch: String = " ", var fg: Int = DEFAULT, var bg: Int = DEFAULT, var attr: Int = 0) {
        fun set(o: Cell) { ch = o.ch; fg = o.fg; bg = o.bg; attr = o.attr }
    }
    /** A stretch of one line in one style, for drawing. */
    class Run(val text: String, val col: Int, val fg: Int, val bg: Int, val attr: Int)

    private fun blank() = Array(rows) { Array(cols) { Cell() } }
    private var main = blank()
    private var alt = blank()
    private var cells = main
    var altScreen = false; private set
    /** Lines that scrolled off the top of the main screen, oldest first (at most [HISTORY]). */
    private val history = ArrayDeque<Array<Cell>>()
    val historySize get() = history.size
    /** How many lines have ever gone into the history, so a view scrolled back can stay where it is. */
    var historyAdded = 0L; private set
    var cx = 0; private set
    var cy = 0; private set
    var cursorVisible = true; private set
    /** The program wants taps (any xterm mouse mode), in SGR encoding when sgrMouse. */
    var mouse = false; private set
    var sgrMouse = false; private set
    /** Arrow keys as ESC O x (DECCKM). */
    var appCursor = false; private set
    var bracketedPaste = false; private set
    /** Colours the program asked about (OSC 10/11): the widget's own text and background. */
    var defaultFg = 0xFFD6DAE1.toInt()
    var defaultBg = 0xFF101216.toInt()
    /** Cell size in pixels, for programs that ask (CSI 14t / 16t). */
    var cellW = 10; var cellH = 20
    var title = ""; private set

    private val pen = Cell()
    private var top = 0
    private var bottom = this.rows - 1
    private var wrapNext = false
    private var autowrap = true
    private var originMode = false
    private var saved = intArrayOf(0, 0, DEFAULT, DEFAULT, 0)
    private var state = 0 // 0 text, 1 ESC, 2 CSI, 3 OSC, 4 ESC inside a string, 5 charset, 6 DCS/APC/PM/SOS
    private val seq = StringBuilder()
    private var high: Char? = null
    private var stringKind = ' '

    fun resize(c: Int, r: Int) {
        val nc = c.coerceAtLeast(2); val nr = r.coerceAtLeast(2)
        if (nc == cols && nr == rows) return
        // Keep the cursor's line on screen when it shrinks (where the prompt usually is).
        val shift = maxOf(0, cy - (nr - 1))
        for (y in 0 until shift) keep(main[y])
        fun copy(a: Array<Array<Cell>>) = Array(nr) { y -> Array(nc) { x -> Cell().also { n -> a.getOrNull(y + shift)?.getOrNull(x)?.let(n::set) } } }
        main = copy(main); alt = copy(alt); cells = if (altScreen) alt else main
        cols = nc; rows = nr
        cy = (cy - shift).coerceIn(0, rows - 1); cx = cx.coerceIn(0, cols - 1)
        top = 0; bottom = rows - 1; wrapNext = false
    }

    fun feed(s: String) { for (c in s) step(c) }

    private fun step(c: Char) {
        when (state) {
            0 -> when (c) {
                '\u001B' -> state = 1
                '\r' -> { cx = 0; wrapNext = false }
                '\n', '\u000B', '\u000C' -> lineFeed()
                '\b' -> { if (cx > 0) cx--; wrapNext = false }
                '\t' -> cx = minOf(cols - 1, (cx / 8 + 1) * 8)
                '\u0007', '\u0000', '\u000E', '\u000F' -> {}
                else -> if (c.code >= 32 && c != '\u007F') {
                    if (c.isHighSurrogate()) high = c
                    else { val h = high; high = null; put(if (h != null && c.isLowSurrogate()) "$h$c" else c.toString()) }
                }
            }
            1 -> { state = 0; when (c) {
                '[' -> { state = 2; seq.clear() }
                ']' -> { state = 3; seq.clear() }
                'P', '_', '^', 'X' -> { state = 6; stringKind = c; seq.clear() }
                '(', ')', '*', '+' -> state = 5
                '7' -> save(); '8' -> restore()
                'D' -> lineFeed()
                'E' -> { cx = 0; lineFeed() }
                'M' -> reverseIndex()
                'c' -> reset()
                else -> {}
            } }
            2 -> if (c in '@'..'~') { state = 0; csi(c) } else if (seq.length < 256) seq.append(c) else state = 0
            3 -> when (c) {
                '\u0007' -> { state = 0; osc() }
                '\u001B' -> { state = 4; stringKind = ']' }
                else -> if (seq.length < 4096) seq.append(c)
            }
            4 -> { state = 0; if (stringKind == ']') osc() else dcs() }
            5 -> state = 0
            6 -> if (c == '\u001B') state = 4 else if (c == '\u0007') { state = 0 } else if (seq.length < 4096) seq.append(c)
        }
    }

    private fun put(ch: String) {
        if (wrapNext && autowrap) { cx = 0; lineFeed() }
        wrapNext = false
        cells[cy][cx].apply { this.ch = ch; fg = pen.fg; bg = pen.bg; attr = pen.attr }
        if (cx == cols - 1) wrapNext = true else cx++
    }

    private fun lineFeed() {
        wrapNext = false
        if (cy == bottom) scrollUp(1) else if (cy < rows - 1) cy++
    }

    private fun reverseIndex() { if (cy == top) scrollDown(1) else if (cy > 0) cy-- }

    private fun clear(cell: Cell) { cell.ch = " "; cell.fg = pen.fg; cell.bg = pen.bg; cell.attr = 0 }
    private fun blankLine() = Array(cols) { Cell().also(::clear) }

    private fun keep(line: Array<Cell>) {
        history.addLast(line); historyAdded++
        while (history.size > HISTORY) history.removeFirst()
    }

    private fun scrollUp(n: Int, from: Int = top) {
        repeat(n.coerceAtMost(bottom - from + 1)) {
            // A line leaving the top of the main screen goes into the history (it can be scrolled back to).
            if (!altScreen && from == 0) keep(cells[0])
            for (y in from until bottom) cells[y] = cells[y + 1]
            cells[bottom] = blankLine()
        }
    }

    private fun scrollDown(n: Int, from: Int = top) {
        repeat(n.coerceAtMost(bottom - from + 1)) {
            for (y in bottom downTo from + 1) cells[y] = cells[y - 1]
            cells[from] = blankLine()
        }
    }

    private fun save() { saved = intArrayOf(cx, cy, pen.fg, pen.bg, pen.attr) }
    private fun restore() {
        cx = saved[0].coerceIn(0, cols - 1); cy = saved[1].coerceIn(0, rows - 1)
        pen.fg = saved[2]; pen.bg = saved[3]; pen.attr = saved[4]; wrapNext = false
    }

    private fun reset() {
        main = blank(); alt = blank(); cells = main; altScreen = false; history.clear()
        cx = 0; cy = 0; top = 0; bottom = rows - 1; pen.fg = DEFAULT; pen.bg = DEFAULT; pen.attr = 0
        cursorVisible = true; mouse = false; sgrMouse = false; appCursor = false; autowrap = true; originMode = false
    }

    private fun osc() {
        val s = seq.toString()
        val code = s.substringBefore(';')
        val arg = s.substringAfter(';', "")
        when (code) {
            "0", "2" -> title = arg.take(80)
            "10" -> if (arg == "?") reply("\u001B]10;${rgb(defaultFg)}\u001B\\")
            "11" -> if (arg == "?") reply("\u001B]11;${rgb(defaultBg)}\u001B\\")
            "4" -> arg.split(';').chunked(2).forEach { p -> if (p.size == 2 && p[1] == "?") p[0].toIntOrNull()?.let { reply("\u001B]4;$it;${rgb(color256(it))}\u001B\\") } }
        }
    }

    private fun dcs() {
        // XTGETTCAP (DCS + q ...): we don't answer capabilities, so say "not known".
        if (stringKind == 'P' && seq.startsWith("+q")) reply("\u001BP0+r\u001B\\")
    }

    private fun rgb(c: Int): String {
        fun h(v: Int) = "%02x%02x".format(v, v)
        return "rgb:${h(c shr 16 and 255)}/${h(c shr 8 and 255)}/${h(c and 255)}"
    }

    private fun csi(final: Char) {
        val raw = seq.toString()
        val prefix = raw.firstOrNull()?.takeIf { it in "?>=<" }
        val body = if (prefix != null) raw.drop(1) else raw
        val inter = body.takeLastWhile { it in ' '..'/' }
        val ps = body.dropLast(inter.length).split(';').map { it.substringBefore(':').toIntOrNull() }
        fun p(i: Int, def: Int = 1) = ps.getOrNull(i)?.takeIf { it > 0 } ?: def
        if (inter.isNotEmpty()) {
            // DECRQM (CSI ? Ps $ p): which modes we support; 2 = known and off, 0 = unknown.
            if (final == 'p' && inter == "$") {
                val m = ps.getOrNull(0) ?: 0
                val known = prefix == '?' && m in setOf(1, 7, 25, 1000, 1002, 1003, 1006, 1049, 2004)
                reply("\u001B[${if (prefix == '?') "?" else ""}$m;${if (known) 2 else 0}\$y")
            }
            return // cursor shape (SP q) and the like: nothing to draw
        }
        when (prefix) {
            '?' -> when (final) {
                'h', 'l' -> ps.forEach { m -> mode(m ?: 0, final == 'h') }
                'u' -> {} // kitty keyboard query: no answer means "not supported"
                'n' -> if (p(0) == 6) reply("\u001B[?${cy + 1};${cx + 1}R")
            }
            '>' -> when (final) {
                'c' -> reply("\u001B[>1;10;0c")
                'q' -> reply("\u001BP>|Hatchlybox\u001B\\")
            }
            null -> when (final) {
                'A' -> { cy = maxOf(if (cy >= top) top else 0, cy - p(0)); wrapNext = false }
                'B' -> { cy = minOf(if (cy <= bottom) bottom else rows - 1, cy + p(0)); wrapNext = false }
                'C', 'a' -> { cx = minOf(cols - 1, cx + p(0)); wrapNext = false }
                'D' -> { cx = maxOf(0, cx - p(0)); wrapNext = false }
                'E' -> { cy = minOf(rows - 1, cy + p(0)); cx = 0; wrapNext = false }
                'F' -> { cy = maxOf(0, cy - p(0)); cx = 0; wrapNext = false }
                'G', '`' -> { cx = (p(0) - 1).coerceIn(0, cols - 1); wrapNext = false }
                'd' -> { cy = (p(0) - 1 + if (originMode) top else 0).coerceIn(0, rows - 1); wrapNext = false }
                'H', 'f' -> { cy = (p(0) - 1 + if (originMode) top else 0).coerceIn(0, rows - 1); cx = (p(1) - 1).coerceIn(0, cols - 1); wrapNext = false }
                'J' -> when (ps.getOrNull(0) ?: 0) {
                    0 -> { eraseLine(cy, cx, cols); for (y in cy + 1 until rows) eraseLine(y, 0, cols) }
                    1 -> { for (y in 0 until cy) eraseLine(y, 0, cols); eraseLine(cy, 0, cx + 1) }
                    3 -> history.clear()
                    else -> for (y in 0 until rows) eraseLine(y, 0, cols)
                }
                'K' -> when (ps.getOrNull(0) ?: 0) {
                    0 -> eraseLine(cy, cx, cols); 1 -> eraseLine(cy, 0, cx + 1); else -> eraseLine(cy, 0, cols)
                }
                'X' -> eraseLine(cy, cx, minOf(cols, cx + p(0)))
                '@' -> { val line = cells[cy]; val n = p(0).coerceAtMost(cols - cx)
                    for (x in cols - 1 downTo cx + n) line[x].set(line[x - n]); for (x in cx until cx + n) clear(line[x]) }
                'P' -> { val line = cells[cy]; val n = p(0).coerceAtMost(cols - cx)
                    for (x in cx until cols - n) line[x].set(line[x + n]); for (x in cols - n until cols) clear(line[x]) }
                'L' -> if (cy in top..bottom) scrollDown(p(0), cy)
                'M' -> if (cy in top..bottom) scrollUp(p(0), cy)
                'S' -> scrollUp(p(0))
                'T' -> scrollDown(p(0))
                'b' -> { val last = cells[cy][maxOf(0, cx - 1)].ch; repeat(p(0).coerceAtMost(cols * rows)) { put(last) } }
                'm' -> sgr(body)
                'r' -> {
                    val t = p(0) - 1; val b = p(1, rows) - 1
                    if (t < b && b < rows) { top = t; bottom = b } else { top = 0; bottom = rows - 1 }
                    cx = 0; cy = if (originMode) top else 0; wrapNext = false
                }
                's' -> save(); 'u' -> restore()
                'n' -> when (p(0)) { 5 -> reply("\u001B[0n"); 6 -> reply("\u001B[${cy + 1};${cx + 1}R") }
                'c' -> reply("\u001B[?62;22c")
                't' -> when (p(0)) {
                    14 -> reply("\u001B[4;${rows * cellH};${cols * cellW}t")
                    16 -> reply("\u001B[6;$cellH;${cellW}t")
                    18 -> reply("\u001B[8;$rows;${cols}t")
                }
            }
        }
    }

    private fun mode(m: Int, on: Boolean) {
        when (m) {
            1 -> appCursor = on
            6 -> { originMode = on; cx = 0; cy = if (on) top else 0 }
            7 -> autowrap = on
            25 -> cursorVisible = on
            1000, 1002, 1003 -> mouse = on
            1006 -> sgrMouse = on
            2004 -> bracketedPaste = on
            47, 1047, 1049 -> if (on != altScreen) {
                if (on) { if (m == 1049) save(); alt = blank(); cells = alt } else { cells = main; if (m == 1049) restore() }
                altScreen = on; wrapNext = false
            }
        }
    }

    private fun eraseLine(y: Int, from: Int, to: Int) {
        val line = cells.getOrNull(y) ?: return
        for (x in from.coerceAtLeast(0) until to.coerceAtMost(cols)) clear(line[x])
        if (y == cy) wrapNext = false
    }

    private fun sgr(body: String) {
        val parts = body.split(';')
        var i = 0
        fun num(s: String?) = s?.toIntOrNull() ?: 0
        while (i < parts.size) {
            val part = parts[i]
            // Colours may also come with colons: 38:2::r:g:b or 38:5:n.
            if (part.contains(':')) {
                val sub = part.split(':').map { it.toIntOrNull() }
                val target = sub[0]
                if (target == 38 || target == 48 || target == 58) {
                    val c = when (sub.getOrNull(1)) {
                        5 -> sub.getOrNull(2)?.let(::color256)
                        2 -> { val v = sub.drop(2).filterNotNull().takeLast(3); if (v.size == 3) argb(v[0], v[1], v[2]) else null }
                        else -> null
                    }
                    if (c != null) { if (target == 38) pen.fg = c else if (target == 48) pen.bg = c }
                } else if (target == 4) { if ((sub.getOrNull(1) ?: 1) == 0) pen.attr = pen.attr and UNDERLINE.inv() else pen.attr = pen.attr or UNDERLINE }
                i++; continue
            }
            when (val n = if (part.isEmpty()) 0 else num(part)) {
                0 -> { pen.fg = DEFAULT; pen.bg = DEFAULT; pen.attr = 0 }
                1 -> pen.attr = pen.attr or BOLD
                2 -> pen.attr = pen.attr or DIM
                3 -> pen.attr = pen.attr or ITALIC
                4 -> pen.attr = pen.attr or UNDERLINE
                7 -> pen.attr = pen.attr or INVERSE
                8 -> pen.attr = pen.attr or HIDDEN
                9 -> pen.attr = pen.attr or STRIKE
                21, 22 -> pen.attr = pen.attr and (BOLD or DIM).inv()
                23 -> pen.attr = pen.attr and ITALIC.inv()
                24 -> pen.attr = pen.attr and UNDERLINE.inv()
                27 -> pen.attr = pen.attr and INVERSE.inv()
                28 -> pen.attr = pen.attr and HIDDEN.inv()
                29 -> pen.attr = pen.attr and STRIKE.inv()
                in 30..37 -> pen.fg = color256(n - 30)
                39 -> pen.fg = DEFAULT
                in 40..47 -> pen.bg = color256(n - 40)
                49 -> pen.bg = DEFAULT
                in 90..97 -> pen.fg = color256(n - 90 + 8)
                in 100..107 -> pen.bg = color256(n - 100 + 8)
                38, 48, 58 -> {
                    val c = when (num(parts.getOrNull(i + 1))) {
                        5 -> { val v = color256(num(parts.getOrNull(i + 2))); i += 2; v }
                        2 -> { val v = argb(num(parts.getOrNull(i + 2)), num(parts.getOrNull(i + 3)), num(parts.getOrNull(i + 4))); i += 4; v }
                        else -> null
                    }
                    if (c != null) { if (n == 38) pen.fg = c else if (n == 48) pen.bg = c }
                }
            }
            i++
        }
    }

    /**
     * The screen as runs of one style per line, colours resolved (inverse, dim, hidden) against the defaults.
     * [back] lines up into the history (main screen only): the view a scroll back shows.
     */
    fun snapshot(back: Int = 0): List<List<Run>> {
        val b = if (altScreen) 0 else back.coerceIn(0, history.size)
        if (b == 0) return cells.map(::runsOf)
        val from = history.size - b
        return List(rows) { y -> runsOf(if (from + y < history.size) history[from + y] else cells[from + y - history.size]) }
    }

    private fun runsOf(line: Array<Cell>): List<Run> {
        val runs = ArrayList<Run>()
        var start = 0
        val sb = StringBuilder()
        var fg = 0; var bg = 0; var attr = 0
        for (x in 0 until minOf(cols, line.size)) {
            val c = line[x]
            var f = if (c.fg == DEFAULT) defaultFg else c.fg
            var b = if (c.bg == DEFAULT) DEFAULT else c.bg
            if (c.attr and INVERSE != 0) { val t = f; f = if (b == DEFAULT) defaultBg else b; b = t }
            if (c.attr and DIM != 0) f = (f and 0x00FFFFFF) or (0x99 shl 24)
            val text = if (c.attr and HIDDEN != 0) " " else c.ch
            val a = c.attr and (BOLD or ITALIC or UNDERLINE or STRIKE)
            if (x > 0 && (f != fg || b != bg || a != attr)) { runs += Run(sb.toString(), start, fg, bg, attr); sb.clear(); start = x }
            fg = f; bg = b; attr = a; sb.append(text)
        }
        runs += Run(sb.toString(), start, fg, bg, attr)
        return runs
    }

    /** The screen's text, trailing blanks dropped (for tests and copying). */
    fun text(): String = cells.joinToString("\n") { l -> l.joinToString("") { it.ch }.trimEnd() }.trimEnd()

    /** The history and the screen as text, for copying (the main screen's history even while a program has the other). */
    fun allText(): String = (history.map { l -> l.joinToString("") { it.ch }.trimEnd() } + text()).joinToString("\n").trim('\n')

    companion object {
        const val DEFAULT = 1 // never a real colour: real ones are opaque ARGB
        const val HISTORY = 3000
        const val BOLD = 1; const val DIM = 2; const val ITALIC = 4; const val UNDERLINE = 8
        const val INVERSE = 16; const val HIDDEN = 32; const val STRIKE = 64

        private val base16 = intArrayOf(
            0x1D1F21, 0xCC6666, 0xB5BD68, 0xF0C674, 0x81A2BE, 0xB294BB, 0x8ABEB7, 0xC5C8C6,
            0x666666, 0xD54E53, 0xB9CA4A, 0xE7C547, 0x7AA6DA, 0xC397D8, 0x70C0B1, 0xEAEAEA,
        )
        fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
        private fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        fun color256(n: Int): Int = when (n) {
            in 0..15 -> (0xFF shl 24) or base16[n]
            in 16..231 -> { val i = n - 16; argb(level(i / 36), level(i / 6 % 6), level(i % 6)) }
            in 232..255 -> { val v = 8 + (n - 232) * 10; argb(v, v, v) }
            else -> argb(200, 200, 200)
        }
    }
}
