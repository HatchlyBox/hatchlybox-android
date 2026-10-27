package __PKG__

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * A web app as an Android app (made by hatchlybox wrap): its files are in assets/web and are served from a private
 * https address, so localStorage, modules and fetch work as on a website, offline. Links elsewhere open outside.
 * window.HatchlyboxNative gives the page notifications, vibration and sharing.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = FrameLayout(this)
        // Android draws apps edge to edge: keep the page clear of the status bar, navigation and keyboard.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
            }
        } else root.fitsSystemWindows = true
        web = WebView(this)
        root.addView(web)
        setContentView(root)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.host == HOST) asset(request.url.path ?: "/") else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "HatchlyboxNative")
        if (saved == null || web.restoreState(saved) == null) web.loadUrl("https://$HOST/index.html")
        // Back goes back in the page first (Android 13+; older ones use onBackPressed below).
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (web.canGoBack()) web.goBack() else finish()
        }
    }

    /** A file of the web app, by its path. */
    private fun asset(path: String): WebResourceResponse {
        var p = path.trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) p += "index.html"
        if (p.split('/').any { it == ".." }) return missing()
        return try {
            val type = mime(p)
            WebResourceResponse(type, if (type.startsWith("text/") || type.endsWith("javascript") || type.endsWith("json")) "utf-8" else null, assets.open("web/$p"))
        } catch (e: IOException) { missing() }
    }

    private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mime(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "ico" -> "image/x-icon"
        "wasm" -> "application/wasm"; "pck" -> "application/octet-stream"; "txt", "md" -> "text/plain"; "xml" -> "text/xml"
        "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "mp4" -> "video/mp4"; "webm" -> "video/webm"
        "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "otf" -> "font/otf"
        else -> "application/octet-stream"
    }

    /** What the page may ask of the phone: window.HatchlyboxNative.notify / vibrate / share. */
    inner class Bridge {
        @JavascriptInterface fun available(): Boolean = true

        /** A notification; the first time, Android asks the person (it shows once they allow it). */
        @JavascriptInterface fun notify(title: String, text: String): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
                return false
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("app", getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify((System.currentTimeMillis() % 100000).toInt(), Notification.Builder(this@MainActivity, "app")
                .setSmallIcon(R.drawable.ic_fg).setContentTitle(title.take(120)).setContentText(text.take(400)).setAutoCancel(true).build())
            return true
        }

        @JavascriptInterface fun vibrate(ms: Int) {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 5000).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }

        @JavascriptInterface fun share(text: String) {
            runOnUiThread { runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) } }
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    @Deprecated("Back goes back in the page first")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    companion object { const val HOST = "app.hatchlybox.local" }
}
