package com.moeez.cardash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Album covers looked up online, because this head unit's Bluetooth
 * (Android 8.1) never receives cover art from the phone.
 *
 * Uses Deezer's and Apple's free search APIs (no account or key), Deezer
 * first. Only the song title and artist are sent. A result is used only when BOTH the title and
 * an artist match what's playing; otherwise no cover is shown, which is
 * better than a wrong one.
 *
 * Light on the unit: at most a few small requests per new song (searches ≈ 2–10 KB,
 * image ≈ 40–70 KB), on a background thread. Covers are kept on disk (capped),
 * so a song heard before needs no network at all.
 */
object CoverLookup {
    private const val TAG = "CarDash"
    private const val MAX_DISK_FILES = 300
    private val memory = LruCache<String, Bitmap>(24)
    private val misses = HashSet<String>() // no match this session: don't ask again

    /** A cover already on the unit (memory or disk) — no network. Null if not saved. */
    suspend fun cached(context: Context, title: String, artist: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${norm(artist)}|${norm(title)}"
        memory.get(key) ?: File(File(context.cacheDir, "covers"), sha1(key) + ".jpg").takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.path) }?.also { memory.put(key, it) }
    }

    suspend fun find(context: Context, title: String, artist: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${norm(artist)}|${norm(title)}"
        memory.get(key)?.let { return@withContext it }
        if (key in misses) return@withContext null
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File(dir, sha1(key) + ".jpg")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); memory.put(key, it); return@withContext it }
        }
        try {
            val urls = artworkUrls(title, artist)
            if (urls.isEmpty()) {
                misses += key
                Log.i(TAG, "Online cover for \"$title\": no confident match")
                return@withContext null
            }
            // An image server can be unreachable on one network (seen on the
            // phone hotspot: one CDN node timed out) — try every candidate.
            var bytes: ByteArray? = null
            var lastError: Exception? = null
            for (u in urls) {
                bytes = try { download(u) } catch (e: Exception) { lastError = e; null }
                if (bytes != null) break
            }
            if (bytes == null) throw lastError ?: java.io.IOException("no image")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            file.writeBytes(bytes)
            trim(dir)
            memory.put(key, bmp)
            Log.i(TAG, "Online cover for \"$title\": found (${bmp.width}x${bmp.height})")
            bmp
        } catch (e: Exception) {
            // Offline or slow: no cover this time; a later play of the song tries again.
            Log.i(TAG, "Online cover for \"$title\": lookup failed ($e)")
            null
        }
    }

    /** True when the services answered but have no matching song (not a network failure). */
    fun isKnownMiss(title: String, artist: String) = "${norm(artist)}|${norm(title)}" in misses

    /**
     * Image URLs for a confident match, best first: Deezer, Deezer via its
     * second image host, then Apple. Apple is asked even when Deezer matched,
     * so there is a fallback image if Deezer's image server is unreachable.
     */
    private fun artworkUrls(title: String, artist: String): List<String> {
        val primaryArtist = artistNames(artist).firstOrNull() ?: ""
        val term = URLEncoder.encode("$primaryArtist ${cleanTitle(title)}".trim(), "UTF-8")
        val out = ArrayList<String>(3)
        fromDeezer(term, title, artist)?.let { d ->
            out += d
            if ("://cdn-images.dzcdn.net/" in d) out += d.replace("://cdn-images.dzcdn.net/", "://e-cdns-images.dzcdn.net/")
        }
        try { fromItunes(term, title, artist)?.let { out += it } } catch (e: Exception) { if (out.isEmpty()) throw e }
        return out
    }

    private fun fromDeezer(term: String, title: String, artist: String): String? = try {
        val data = JSONObject(String(download("https://api.deezer.com/search?q=$term&limit=10")))
            .optJSONArray("data")
        var url: String? = null
        if (data != null) for (i in 0 until data.length()) {
            val r = data.getJSONObject(i)
            val a = r.optJSONObject("artist")?.optString("name").orEmpty()
            if (matches(title, artist, r.optString("title"), a)) {
                url = r.optJSONObject("album")?.optString("cover_big")?.takeIf { it.startsWith("https://") }
                if (url != null) break
            }
        }
        url
    } catch (e: Exception) {
        null // try the next source
    }

    private fun fromItunes(term: String, title: String, artist: String): String? {
        val json = JSONObject(String(download("https://itunes.apple.com/search?term=$term&entity=song&limit=10")))
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (matches(title, artist, r.optString("trackName"), r.optString("artistName"))) {
                val small = r.optString("artworkUrl100").ifBlank { return null }
                return small.replace("100x100bb", "500x500bb") // the cover is ~300 px on screen
            }
        }
        return null
    }

    // ---- Matching (pure, unit-tested) --------------------------------------

    /** True when the result is the song playing: same title and at least one shared artist. */
    fun matches(title: String, artist: String, resultTitle: String, resultArtist: String): Boolean {
        val t = norm(cleanTitle(title))
        val rt = norm(cleanTitle(resultTitle))
        if (t.isEmpty() || rt.isEmpty()) return false
        val titleOk = t == rt || (t.length >= 4 && rt.length >= 4 && (rt.startsWith(t) || t.startsWith(rt)))
        if (!titleOk) return false
        val mine = artistNames(artist).map(::norm).filter { it.isNotEmpty() }
        val theirs = artistNames(resultArtist).map(::norm).filter { it.isNotEmpty() }
        if (mine.isEmpty()) return false
        return mine.any { a -> theirs.any { b -> a == b || (a.length >= 4 && (b.contains(a) || a.contains(b))) } }
    }

    /** "A, B & C feat. D" → [A, B, C, D]. */
    fun artistNames(artist: String): List<String> =
        artist.split(Regex("\\s*(,|&|\\band\\b|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b|/)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** Drops "(feat. …)", "[Remastered]", " - Live" style suffixes. */
    fun cleanTitle(title: String): String =
        title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
            .replace(Regex("\\s+-\\s+.*$"), "")
            .trim()

    /** Lower case, accents removed, letters and digits only. */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    // ---- Plumbing ------------------------------------------------------------

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4_000
        c.readTimeout = 6_000
        c.setRequestProperty("User-Agent", "CarDash/1.0 (personal car head unit)")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
