package com.cherry.studio

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class Opts(
    val audio: Boolean, val res: Int, val bitrate: Int,
    val subs: Boolean, val lang: String, val sb: Boolean,
    val start: String, val end: String, val limit: String,
)

data class Meta(
    val url: String, val title: String, val thumb: String?,
    val uploader: String, val duration: Int, val heights: List<Int>,
)

data class Entry(
    val title: String, val uri: String, val audio: Boolean,
    val size: Long, val time: Long, val thumb: String?,
)

class DlJob(val id: String, val url: String, val title: String, val thumb: String?, val opts: Opts) {
    var progress by mutableStateOf(0f)
    var status by mutableStateOf("Queued") // Queued, Downloading, Processing, Done, Failed, Cancelled
    var eta by mutableStateOf(0L)
    var note by mutableStateOf("")
    @Volatile var cancelled = false
    val active get() = status == "Queued" || status == "Downloading" || status == "Processing"
}

/** Natural-language command bar parser matching main.py. */
object NL {
    data class Parsed(
        var audio: Boolean? = null, var res: Int? = null, var bitrate: Int? = null,
        var sb: Boolean? = null, var subs: Boolean? = null, var lang: String? = null,
        var start: String? = null, var end: String? = null,
    )

    private val langMap = mapOf(
        "english" to "en", "hindi" to "hi", "spanish" to "es", "japanese" to "ja",
        "korean" to "ko", "french" to "fr", "german" to "de", "arabic" to "ar"
    )

    fun parse(text: String): Parsed {
        val t = text.lowercase()
        val p = Parsed()
        if (Regex("mp3|audio|music|song").containsMatchIn(t)) p.audio = true
        if (Regex("mp4|video").containsMatchIn(t)) p.audio = false
        Regex("(8k|4k|2160|1440|1080|720|480|360)").find(t)?.value?.let {
            p.res = when (it) { "8k" -> 4320; "4k" -> 2160; else -> it.toInt() }
        }
        Regex("(128|192|256|320)\\s*k").find(t)?.groupValues?.get(1)?.let { p.bitrate = it.toInt() }
        if ("sponsor" in t) p.sb = true
        if (Regex("\\bsubs?\\b|subtitle|caption|\\bcc\\b").containsMatchIn(t)) {
            p.subs = true
            langMap.entries.firstOrNull { it.key in t }?.let { p.lang = it.value }
        }
        Regex("(\\d{1,2}(?::\\d{2}){1,2})\\s*(?:to|-|until)\\s*(\\d{1,2}(?::\\d{2}){1,2})").find(t)?.let {
            p.start = it.groupValues[1]; p.end = it.groupValues[2]
        }
        return p
    }
}

object Engine {
    lateinit var app: Application
    private val prefs get() = app.getSharedPreferences("cherry", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Boolean>()
    private val active = AtomicInteger(0)
    private var gate = Semaphore(2)

    // Client fallback order to bypass YouTube "Sign in to confirm you're not a bot"
    private val YT_CLIENTS = listOf("android_vr", "tv,android", "mweb")

    val jobs = mutableStateListOf<DlJob>()
    val history = mutableStateListOf<Entry>()
    var coreStatus by mutableStateOf("Starting engine…")
    var theme by mutableStateOf("cherry")
        private set
    var parallel by mutableStateOf(2)
        private set
    var confettiTick by mutableStateOf(0)
        private set

    private fun ui(block: () -> Unit) { main.post { block() } }

    fun triggerConfetti() { ui { confettiTick++ } }

    fun init(a: Application) {
        app = a
        theme = prefs.getString("theme", "cherry") ?: "cherry"
        parallel = prefs.getInt("parallel", 2)
        gate = Semaphore(parallel)
        loadHistory()
        scope.launch {
            try {
                YoutubeDL.getInstance().init(a)
                FFmpeg.getInstance().init(a)
                ui { coreStatus = "Engine ready (FFmpeg + yt-dlp)" }
                ready.complete(true)
            } catch (e: Throwable) {
                ui { coreStatus = "Engine failed: ${e.javaClass.simpleName}: ${e.message}" }
                ready.complete(false)
            }
        }
    }

    @JvmName("applyTheme")
    fun setTheme(t: String) { theme = t; prefs.edit().putString("theme", t).apply() }

    @JvmName("applyParallel")
    fun setParallel(n: Int) { parallel = n; prefs.edit().putInt("parallel", n).apply() }

    private fun isBotOrSignInError(e: Throwable): Boolean {
        val m = (e.message ?: e.toString()).lowercase()
        return "sign in" in m || "confirm you" in m || "bot" in m || "403" in m || "po_token" in m
    }

    private fun applyBypassOptions(r: YoutubeDLRequest, clientProfile: String) {
        r.addOption("--extractor-args", "youtube:player_client=$clientProfile")
        r.addOption("--no-check-certificates")
        r.addOption("--no-playlist")
        r.addOption("--retries", "5")
        r.addOption("--fragment-retries", "5")
        val cookies = File(app.filesDir, "cookies.txt")
        if (cookies.exists() && cookies.length() > 0) {
            r.addOption("--cookies", cookies.absolutePath)
        }
    }

    suspend fun fetch(url: String): Meta = withContext(Dispatchers.IO) {
        check(ready.await()) { "Engine not ready" }
        var lastError: Throwable? = null
        for (client in YT_CLIENTS) {
            try {
                val req = YoutubeDLRequest(url).apply {
                    applyBypassOptions(this, client)
                }
                val i = YoutubeDL.getInstance().getInfo(req)
                return@withContext Meta(
                    url, i.title ?: "Untitled", i.thumbnail, i.uploader ?: "", i.duration,
                    i.formats?.map { it.height }?.filter { it > 0 }?.distinct()?.sortedDescending() ?: emptyList(),
                )
            } catch (e: Throwable) {
                lastError = e
                if (!isBotOrSignInError(e)) throw e
            }
        }
        throw lastError ?: RuntimeException("Could not fetch video info.")
    }

    fun enqueue(m: Meta, o: Opts) {
        val j = DlJob(UUID.randomUUID().toString(), m.url, m.title, m.thumb, o)
        jobs.add(0, j)
        if (active.getAndIncrement() == 0) {
            app.startForegroundService(Intent(app, KeepAliveService::class.java))
        }
        scope.launch { try { gate.withPermit { work(j) } } finally { finished() } }
    }

    fun retry(j: DlJob) {
        jobs.remove(j)
        enqueue(Meta(j.url, j.title, j.thumb, "", 0, emptyList()), j.opts)
    }

    fun cancel(j: DlJob) {
        j.cancelled = true
        j.status = "Cancelled"
        scope.launch { runCatching { YoutubeDL.getInstance().destroyProcessById(j.id) } }
    }

    fun clearFinished() { jobs.removeAll { !it.active } }

    private fun finished() {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            app.stopService(Intent(app, KeepAliveService::class.java))
        }
    }

    private fun options(r: YoutubeDLRequest, o: Opts, tmp: File, clientProfile: String) {
        applyBypassOptions(r, clientProfile)
        r.addOption("--no-mtime")
        r.addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")
        if (o.audio) {
            r.addOption("-f", "bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", "mp3")
            r.addOption("--audio-quality", "${o.bitrate}K")
        } else {
            val h = o.res
            r.addOption("-f", "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b")
            r.addOption("--merge-output-format", "mp4")
            if (o.subs) {
                r.addOption("--write-subs")
                r.addOption("--write-auto-subs")
                r.addOption("--sub-langs", o.lang.ifBlank { "en" })
                r.addOption("--embed-subs")
            }
        }
        if (o.sb) r.addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction,intro,outro")
        if (o.start.isNotBlank() || o.end.isNotBlank()) {
            r.addOption("--download-sections", "*${o.start.ifBlank { "0" }}-${o.end.ifBlank { "inf" }}")
            r.addOption("--force-keyframes-at-cuts")
        }
        if (o.limit.isNotBlank()) r.addOption("--limit-rate", o.limit)
    }

    private val mediaExt = setOf("mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "ogg", "flac", "wav")

    private suspend fun work(j: DlJob) {
        if (j.cancelled) return
        if (!ready.await()) { fail(j, "Engine failed to start"); return }
        val tmp = File(app.getExternalFilesDir(null), "tmp/${j.id}").apply { mkdirs() }
        try {
            ui { if (!j.cancelled) j.status = "Downloading" }

            var lastError: Throwable? = null
            var succeeded = false
            for (client in YT_CLIENTS) {
                if (j.cancelled) return
                try {
                    val r = YoutubeDLRequest(j.url)
                    options(r, j.opts, tmp, client)
                    YoutubeDL.getInstance().execute(request = r, processId = j.id, callback = { p, eta, line ->
                        ui {
                            if (!j.cancelled) {
                                if (p >= 0) j.progress = p / 100f
                                j.eta = eta
                                j.note = line.trim().take(90)
                                if (line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                                    line.contains("[EmbedSubtitle]") || line.contains("[SponsorBlock]")
                                ) j.status = "Processing"
                            }
                        }
                    })
                    succeeded = true
                    break
                } catch (e: Throwable) {
                    lastError = e
                    if (j.cancelled) return
                    if (!isBotOrSignInError(e)) throw e
                    ui { j.note = "Switching YouTube client ($client blocked)…" }
                }
            }
            if (!succeeded) throw (lastError ?: RuntimeException("Download failed."))

            if (j.cancelled) return
            val out = tmp.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in mediaExt }
                ?.maxByOrNull { it.length() }
            if (out == null) { fail(j, "No output file was produced"); return }
            val uri = save(out, j.opts.audio)
            val entry = Entry(out.nameWithoutExtension, uri, j.opts.audio, out.length(), System.currentTimeMillis(), j.thumb)
            ui {
                j.progress = 1f; j.status = "Done"; j.note = "Saved to Downloads/Cherry"
                history.add(0, entry); persist()
                confettiTick++
            }
        } catch (e: Throwable) {
            if (!j.cancelled) fail(j, friendly(e))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun fail(j: DlJob, msg: String) = ui { j.status = "Failed"; j.note = msg }

    private suspend fun save(f: File, audio: Boolean): String {
        if (Build.VERSION.SDK_INT < 29) {
            val sub = if (audio) "Music" else "Videos"
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cherry/$sub")
            if (!dir.exists() && !dir.mkdirs()) error("Storage permission needed. Allow it in the app's settings.")
            val dest = File(dir, f.name)
            f.copyTo(dest, overwrite = true)
            val result = CompletableDeferred<String>()
            MediaScannerConnection.scanFile(app, arrayOf(dest.absolutePath), null) { _, uri ->
                result.complete(uri?.toString() ?: Uri.fromFile(dest).toString())
            }
            return result.await()
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Cherry/${if (audio) "Music" else "Videos"}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = app.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Could not create file")
        cr.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(uri, v, null, null)
        return uri.toString()
    }

    fun deleteEntry(e: Entry) {
        history.remove(e); persist()
        runCatching { app.contentResolver.delete(Uri.parse(e.uri), null, null) }
    }

    private fun persist() {
        val a = JSONArray()
        history.forEach {
            a.put(JSONObject().put("t", it.title).put("u", it.uri).put("a", it.audio)
                .put("s", it.size).put("d", it.time).put("th", it.thumb ?: ""))
        }
        prefs.edit().putString("history", a.toString()).apply()
    }

    private fun loadHistory() {
        val a = JSONArray(prefs.getString("history", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            history.add(Entry(o.getString("t"), o.getString("u"), o.getBoolean("a"), o.getLong("s"),
                o.getLong("d"), o.optString("th").ifBlank { null }))
        }
    }

    fun updateCore(done: (String) -> Unit) {
        scope.launch {
            ready.await()
            val msg = try {
                when (YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._NIGHTLY)) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated to latest Nightly"
                    else -> "Already on latest Nightly"
                }
            } catch (e: Throwable) { "Update failed: ${friendly(e)}" }
            ui { done(msg) }
        }
    }

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.toString()
        return when {
            "Unsupported URL" in m -> "That link isn't supported."
            "Sign in" in m || "confirm you" in m -> "YouTube bot check triggered. Tap Update yt-dlp in Settings and retry."
            "Private video" in m -> "This video is private."
            "HTTP Error 403" in m -> "Blocked by site (403). Tap Update yt-dlp in Settings and retry."
            "Unable to resolve host" in m || "Network is unreachable" in m -> "No internet connection."
            else -> m.lines().lastOrNull { it.isNotBlank() }?.take(140) ?: "Something went wrong."
        }
    }
}
