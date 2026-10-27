package com.hatchlybox.app

import android.os.FileObserver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reuse complete snapshots until their source changes. Partial writes retain the last valid value. */
object EngineState {
    private data class Snapshot(val stamp: String, val value: Any)
    private val values = mutableMapOf<String, Snapshot>()
    private fun stamp(f: File) = "${f.lastModified()}:${f.length()}"
    @Synchronized fun json(f: File, previous: JSONObject): JSONObject {
        val key = "json:${f.path}"; val old = values[key]; val stamp = stamp(f)
        if (old?.stamp == stamp) return old.value as JSONObject
        return runCatching { JSONObject(f.readText()) }.getOrElse { old?.value as? JSONObject ?: previous }.also { values[key] = Snapshot(stamp, it) }
    }
    @Synchronized fun array(f: File, previous: List<JSONObject>): List<JSONObject> {
        val key = "array:${f.path}"; val old = values[key]; val stamp = stamp(f)
        @Suppress("UNCHECKED_CAST")
        if (old?.stamp == stamp) return old.value as List<JSONObject>
        @Suppress("UNCHECKED_CAST")
        val result = runCatching { JSONArray(f.readText()).objects() }.getOrElse { old?.value as? List<JSONObject> ?: previous }
        values[key] = Snapshot(stamp, result); return result
    }
    @Synchronized fun tail(f: File, limit: Int): String {
        val key = "tail:${f.path}"; val old = values[key]; val stamp = stamp(f)
        if (old?.stamp == stamp) return old.value as String
        val text = runCatching {
            java.io.RandomAccessFile(f, "r").use { r ->
                r.seek((r.length() - limit).coerceAtLeast(0))
                // Advance past any partial UTF-8 multibyte sequence at the seek point.
                while (r.filePointer < r.length()) { val b = r.read(); if (b and 0xC0 != 0x80) { r.seek(r.filePointer - 1); break } }
                val bytes = ByteArray((r.length() - r.filePointer).toInt())
                r.readFully(bytes); String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
        values[key] = Snapshot(stamp, text); return text
    }
}

@Suppress("DEPRECATION")
class EngineObserver(dir: File, private val changed: () -> Unit) : FileObserver(dir.path, CLOSE_WRITE or MOVED_TO or DELETE or CREATE) {
    override fun onEvent(event: Int, path: String?) { changed() }
}
