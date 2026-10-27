package com.hatchlybox.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Web apps added from the browser: its share menu (Share → Hatchlybox) or, while Hatchlybox is the home app, its
// "Add to Home screen". The name and icon are confirmed first (the page's favicon by default), then the app goes
// on the home screen in a free place (a new page when the page is full) and into the library.

/** A web page on the home screen. browser: the app it opens in; shortcut*: the browser's own pinned shortcut, when it gave one. */
class Site(val id: String, val name: String, val url: String, val browser: String, val shortcutPkg: String, val shortcutId: String, val created: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("browser", browser)
        .put("shortcutPkg", shortcutPkg).put("shortcutId", shortcutId).put("created", created)
    companion object {
        fun of(o: JSONObject) = Site(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("browser"),
            o.optString("shortcutPkg"), o.optString("shortcutId"), o.optLong("created"))
    }
}

/** An icon to choose from: where it came from, and the picture. */
class SiteIcon(val label: String, val bitmap: Bitmap)

/** A web app waiting for the user's yes: its name and icon can still change. pin: the browser's request, accepted on Add. */
class SiteDraft(val url: String, val browser: String, val pin: LauncherApps.PinItemRequest?, name: String) {
    var name by mutableStateOf(name)
    val icons = mutableStateListOf<SiteIcon>()
    var chosen by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
}

object Sites {
    val list = mutableStateListOf<Site>()
    /** The web app being confirmed, shown as a dialog over the home screen. */
    var draft by mutableStateOf<SiteDraft?>(null)
    private val icons = HashMap<String, ImageBitmap?>()
    private var loaded = false
    private val scope = MainScope()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = runCatching { JSONArray(context.getSharedPreferences("sites", 0).getString("list", "[]")) }.getOrDefault(JSONArray())
        list += (0 until saved.length()).mapNotNull { saved.optJSONObject(it)?.let(Site::of) }.filter { it.id.isNotBlank() }
    }

    private fun save(context: Context) {
        context.getSharedPreferences("sites", 0).edit().putString("list", JSONArray(list.map { it.json() }).toString()).apply()
    }

    private fun iconFile(context: Context, id: String) = File(context.filesDir, "sites/$id.png")

    fun icon(context: Context, id: String): ImageBitmap? = icons.getOrPut(id) {
        runCatching { BitmapFactory.decodeFile(iconFile(context, id).path)?.asImageBitmap() }.getOrNull()
    }

    fun add(context: Context, d: SiteDraft): Site {
        val info = d.pin?.shortcutInfo
        val site = Site("s" + System.currentTimeMillis().toString(36), d.name.trim().ifBlank { host(d.url) }, d.url, d.browser,
            info?.`package`.orEmpty(), info?.id.orEmpty(), System.currentTimeMillis() / 1000)
        d.icons.getOrNull(d.chosen)?.bitmap?.let { b ->
            runCatching {
                val f = iconFile(context, site.id); f.parentFile?.mkdirs()
                f.outputStream().use { Bitmap.createScaledBitmap(b, 192, 192, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        list += site; save(context)
        return site
    }

    fun remove(context: Context, id: String) {
        list.removeAll { it.id == id }; save(context)
        icons.remove(id); iconFile(context, id).delete()
    }

    /** Opens it: the browser's own shortcut when there is one (it may open as an installed web app), else the page in its browser. */
    fun open(context: Context, s: Site): Boolean {
        if (s.shortcutId.isNotBlank()) {
            val ok = runCatching {
                context.getSystemService(LauncherApps::class.java).startShortcut(s.shortcutPkg, s.shortcutId, null, null, Process.myUserHandle()); true
            }.getOrDefault(false)
            if (ok) return true
        }
        if (s.url.isBlank()) return false
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (s.browser.isNotBlank() && runCatching { context.startActivity(Intent(view).setPackage(s.browser)); true }.getOrDefault(false)) return true
        return runCatching { context.startActivity(view); true }.getOrDefault(false)
    }

    /** A share from a browser (a page's address) or a browser's "Add to Home screen": starts the confirmation. True when it was one. */
    fun handle(activity: Activity, intent: Intent?): Boolean {
        intent ?: return false
        val context = activity.applicationContext
        if (intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) {
            val apps = context.getSystemService(LauncherApps::class.java)
            val req = runCatching { apps.getPinItemRequest(intent) }.getOrNull() ?: return false
            val info = req.shortcutInfo
            if (req.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || info == null || !req.isValid) return false
            val d = SiteDraft("", info.`package`, req, (info.shortLabel ?: info.longLabel ?: "").toString())
            val browserIcon = runCatching {
                apps.getShortcutIconDrawable(info, activity.resources.displayMetrics.densityDpi)?.toBitmap(192, 192)
            }.getOrNull()
            browserIcon?.let { d.icons += SiteIcon("From ${appLabel(context, info.`package`)}", it) }
            d.icons += SiteIcon("Letter", letterIcon(d.name))
            d.loading = false
            draft = d
            return true
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = Regex("https?://[^\\s\"'<>]+").find(text)?.value ?: return false
            val subject = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)).orEmpty()
                .takeUnless { it.startsWith("http") }.orEmpty()
            val d = SiteDraft(url, browserOf(context, activity.referrer), null, subject)
            draft = d
            scope.launch { findIcons(d) }
            return true
        }
        return false
    }

    /** The app that shared it, when it's a browser (the page then opens there); blank: the phone's default browser. */
    private fun browserOf(context: Context, referrer: Uri?): String {
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return ""
        val browsers = runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), android.content.pm.PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return pkg.takeIf { it in browsers }.orEmpty()
    }

    private fun appLabel(context: Context, pkg: String) = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("the browser")

    fun host(url: String) = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault(url)

    /** The page's title (when the share gave none) and its icons: the favicon (.ico) first, then larger ones, then a letter. */
    private suspend fun findIcons(d: SiteDraft) {
        val (title, found) = withContext(Dispatchers.IO) {
            val page = runCatching { get(d.url, 400_000) }.getOrNull()
            val base = page?.first ?: d.url
            val html = page?.second?.decodeToString().orEmpty()
            val title = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            // Every <link rel="…icon…" href="…">, the favicon kinds before the touch icons.
            val links = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { m ->
                val tag = m.value
                val rel = attr(tag, "rel")?.lowercase() ?: return@mapNotNull null
                val href = attr(tag, "href")?.let(::unescape)?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return@mapNotNull null
                if (!rel.contains("icon")) return@mapNotNull null
                val abs = runCatching { URL(URL(base), href).toString() }.getOrNull() ?: return@mapNotNull null
                val kind = if (rel.contains("apple-touch")) 2 else 1
                Triple(kind, abs, attr(tag, "sizes").orEmpty())
            }.toList()
            val origin = runCatching { URL(URL(base), "/favicon.ico").toString() }.getOrNull()
            val tries = buildList {
                links.filter { it.first == 1 && it.second.substringBefore('?').endsWith(".ico", true) }.forEach { add("Page icon (.ico)" to it.second) }
                origin?.let { add("Page icon (.ico)" to it) }
                links.filter { it.first == 1 }.forEach { add((if (it.third.isNotBlank()) "Icon ${it.third}" else "Page icon") to it.second) }
                links.filter { it.first == 2 }.forEach { add("Touch icon" + (if (it.third.isNotBlank()) " ${it.third}" else "") to it.second) }
            }.distinctBy { it.second }.take(6)
            val icons = tries.mapNotNull { (label, u) ->
                runCatching { get(u, 600_000).second }.getOrNull()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    ?.takeIf { it.width >= 8 && it.height >= 8 }?.let { SiteIcon(label, it) }
            }
            // The same picture twice (the link and /favicon.ico) shows once.
            title to icons.distinctBy { it.bitmap.width.toString() + "x" + it.bitmap.height + ":" + it.bitmap.getPixel(it.bitmap.width / 2, it.bitmap.height / 2) }
        }
        if (d.name.isBlank()) d.name = title.ifBlank { host(d.url) }
        d.icons += found
        d.icons += SiteIcon("Letter", letterIcon(d.name.ifBlank { host(d.url) }))
        d.loading = false
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.let { g -> g[2].ifEmpty { g[3].ifEmpty { g[4] } } }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    /** Fetches a URL (following redirects, also http → https) up to limit bytes: the final address and the bytes. */
    private fun get(url: String, limit: Int): Pair<String, ByteArray> {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: error("no Location")).toString(); return@repeat }
                if (code !in 200..299) error("HTTP $code")
                val bytes = c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
                    while (out.size() < limit) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray()
                }
                return u to bytes
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }

    /** A plain icon: the name's first letter on a colour picked from the name. */
    private fun letterIcon(name: String): Bitmap {
        val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val hues = intArrayOf(0xFF4858AB.toInt(), 0xFF14B8A6.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF8B5CF6.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt())
        c.drawColor(hues[Math.floorMod(name.hashCode(), hues.size)])
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 104f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        c.drawText(letter, 96f, 96f - (p.descent() + p.ascent()) / 2, p)
        return b
    }
}

/** The confirmation: the name (editable) and the icon (the page's favicon chosen at first), then Add puts it on the home screen. */
@Composable
fun SiteDialog(m: MakerModel) {
    val d = Sites.draft ?: return
    val context = LocalContext.current
    val dismiss = { Sites.draft = null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add to the home screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (d.url.isNotBlank()) Text(d.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(d.name, { d.name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Getting the page's icon…", style = MaterialTheme.typography.bodySmall)
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.icons.size) { i ->
                        val ic = d.icons[i]
                        val on = d.chosen == i
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Use ${ic.label}") { d.chosen = i }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(if (on) 2.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Image(ic.bitmap.asImageBitmap(), ic.label, Modifier.size(40.dp), filterQuality = FilterQuality.High) }
                            Text(ic.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.size(width = 72.dp, height = 16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !d.loading && d.name.isNotBlank(), onClick = {
                // The browser's own request is accepted too: its shortcut only opens once it's pinned.
                val pin = d.pin
                if (pin != null && !runCatching { pin.isValid && pin.accept() }.getOrDefault(false)) {
                    m.notice = "The browser withdrew the shortcut. Try Add to Home screen again."
                    Sites.draft = null; return@TextButton
                }
                val site = Sites.add(context, d)
                Sites.draft = null
                m.showConsole = false; m.library = false; m.page = "apps"
                val pages = m.launcher.pages.size
                val to = m.launcher.add(m.homePage.coerceIn(0, (pages - 1).coerceAtLeast(0)), "site", site.id)
                m.homePage = to
                m.notice = "${site.name} is on your home screen" + if (m.launcher.pages.size > pages) ", on a new page: the other was full." else "."
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

/** A web app on the home screen: its icon and name; tap opens it. */
@Composable
fun SiteTile(m: MakerModel, ref: String, shadow: androidx.compose.ui.graphics.Shadow?) {
    val context = LocalContext.current
    val site = Sites.list.firstOrNull { it.id == ref }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (site == null) return@BoxWithConstraints
        val icon = Sites.icon(context, site.id)
        val side = minOf(maxWidth * 0.62f, maxHeight - 22.dp, 64.dp).coerceAtLeast(24.dp)
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open ${site.name}") {
                m.used("site:" + site.id)
                if (!Sites.open(context, site)) m.notice = "Couldn't open ${site.name}. Is its browser still installed?"
            }.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SiteIcon(icon, site.name, side)
            Text(site.name, style = MaterialTheme.typography.labelSmall.copy(shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
fun SiteIcon(icon: ImageBitmap?, name: String, side: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(side).clip(RoundedCornerShape(side * 0.26f)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.size(side), filterQuality = FilterQuality.High)
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
    }
}
