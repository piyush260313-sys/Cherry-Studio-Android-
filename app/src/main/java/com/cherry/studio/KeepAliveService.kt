package com.cherry.studio

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

class DlJob(val id: String, val url: String, initialTitle: String, val thumb: String?, val opts: Opts) {
    var title by mutableStateOf(initialTitle)
    var progress by mutableStateOf(0f)
    var status by mutableStateOf("Queued")
    var eta by mutableStateOf(0L)
    var note by mutableStateOf("")
    @Volatile var cancelled = false
    val active get() = status == "Queued" || status == "Downloading" || status == "Processing"
}

/** Natural-language command bar parser. */
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

/** OkHttp-backed Downloader for NewPipeExtractor. */
private class OkHttpNewPipeDownloader(
    private val client: OkHttpClient,
    private val cookieProvider: () -> String?
) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val reqBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )

        for ((k, values) in request.headers()) {
            reqBuilder.removeHeader(k)
            for (v in values) reqBuilder.addHeader(k, v)
        }

        val cookies = cookieProvider()
        if (!cookies.isNullOrBlank() && request.url().contains("youtube.com")) {
            reqBuilder.header("Cookie", cookies)
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val respBody = resp.body?.string() ?: ""
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                respBody,
                resp.request.url.toString()
            )
        }
    }
}

/** 8-Thread Parallel Chunk Downloader (throttled UI updates) + Native MediaMuxer. */
private object FastEngine {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val BUFFER_SIZE = 64 * 1024

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun downloadParallel(
        url: String,
        dest: File,
        threads: Int = 8,
        isCancelled: () -> Boolean,
        onProgress: (Float, Long) -> Unit
    ) = coroutineScope {
        val headReq = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .head()
            .build()

        val totalBytes = runCatching {
            client.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) resp.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
            }
        }.getOrDefault(-1L)

        val startTime = System.currentTimeMillis()
        val lastEmit = AtomicLong(0L)

        fun emitProgress(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            val prev = lastEmit.get()
            if (now - prev >= 120L && lastEmit.compareAndSet(prev, now)) {
                val pct = (done.toFloat() / total).coerceIn(0f, 1f)
                val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.5)
                val speed = done / elapsedSec
                val eta = if (speed > 0) ((total - done) / speed).toLong() else 0L
                onProgress(pct, eta)
            }
        }

        if (totalBytes <= 0L) {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} while downloading stream")
                val len = resp.body?.contentLength() ?: -1L
                val input = resp.body!!.byteStream()
                val buf = ByteArray(BUFFER_SIZE)
                var done = 0L
                dest.outputStream().use { out ->
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Cancelled")
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (len > 0) emitProgress(done, len)
                    }
                }
            }
            onProgress(1f, 0L)
            return@coroutineScope
        }

        RandomAccessFile(dest, "rw").use { it.setLength(totalBytes) }
        val chunkSize = totalBytes / threads
        val downloaded = AtomicLong(0L)

        (0 until threads).map { i ->
            async(Dispatchers.IO) {
                val start = i * chunkSize
                val end = if (i == threads - 1) totalBytes - 1 else (start + chunkSize - 1)
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Range", "bytes=$start-$end")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} on chunk $i")
                    val input = resp.body!!.byteStream()
                    val buf = ByteArray(BUFFER_SIZE)
                    RandomAccessFile(dest, "rw").use { raf ->
                        raf.seek(start)
                        while (true) {
                            if (isCancelled()) throw InterruptedException("Cancelled")
                            val read = input.read(buf)
                            if (read == -1) break
                            raf.write(buf, 0, read)
                            val done = downloaded.addAndGet(read.toLong())
                            emitProgress(done, totalBytes)
                        }
                    }
                }
            }
        }.awaitAll()
        onProgress(1f, 0L)
    }

    /** Hardware-native MP4 video + M4A audio merger (zero FFmpeg). */
    fun muxMp4(videoFile: File, audioFile: File, outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val aExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

        try {
            val vTrackIn = (0 until vExtractor.trackCount).first {
                vExtractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true
            }
            val aTrackIn = (0 until aExtractor.trackCount).first {
                aExtractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true
            }

            vExtractor.selectTrack(vTrackIn)
            aExtractor.selectTrack(aTrackIn)

            val vTrackOut = muxer.addTrack(vExtractor.getTrackFormat(vTrackIn))
            val aTrackOut = muxer.addTrack(aExtractor.getTrackFormat(aTrackIn))
            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            fun copyTrack(ext: MediaExtractor, outIdx: Int) {
                while (true) {
                    val sampleSize = ext.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = ext.sampleTime
                    info.flags = ext.sampleFlags
                    muxer.writeSampleData(outIdx, buffer, info)
                    ext.advance()
                }
            }

            copyTrack(vExtractor, vTrackOut)
            copyTrack(aExtractor, aTrackOut)
        } finally {
            runCatching { vExtractor.release() }
            runCatching { aExtractor.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}

object Engine {
    lateinit var app: Application
    private val prefs get() = app.getSharedPreferences("cherry", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicInteger(0)
    private var gate = Semaphore(2)

    val jobs = mutableStateListOf<DlJob>()
    val history = mutableStateListOf<Entry>()
    var isInitialized by mutableStateOf(false)
        private set
    var coreStatus by mutableStateOf("Initializing Native Cherry Engine…")
    var theme by mutableStateOf("cherry")
        private set
    var parallel by mutableStateOf(2)
        private set
    var confettiTick by mutableStateOf(0)
        private set
    var hasCookies by mutableStateOf(false)
        private set

    private fun ui(block: () -> Unit) { main.post { block() } }

    fun triggerConfetti() { ui { confettiTick++ } }

    fun init(a: Application) {
        app = a
        theme = prefs.getString("theme", "cherry") ?: "cherry"
        parallel = prefs.getInt("parallel", 2)
        gate = Semaphore(parallel)
        hasCookies = !prefs.getString("yt_cookies", null).isNullOrBlank()

        scope.launch {
            loadHistory()
            NewPipe.init(
                OkHttpNewPipeDownloader(FastEngine.client) {
                    prefs.getString("yt_cookies", null)
                }
            )
            ui {
                coreStatus = "Native Cherry Engine Ready (8-Thread + MediaMuxer)"
                isInitialized = true
            }
        }
    }

    fun saveYoutubeCookies(rawCookieHeader: String): Boolean {
        if (rawCookieHeader.isBlank()) return false
        prefs.edit().putString("yt_cookies", rawCookieHeader.trim()).apply()
        ui { hasCookies = true }
        return true
    }

    fun clearYoutubeCookies() {
        prefs.edit().remove("yt_cookies").apply()
        ui { hasCookies = false }
    }

    @JvmName("applyTheme")
    fun setTheme(t: String) { theme = t; prefs.edit().putString("theme", t).apply() }

    @JvmName("applyParallel")
    fun setParallel(n: Int) { parallel = n; prefs.edit().putInt("parallel", n).apply() }

    suspend fun fetch(url: String): Meta = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(url)
        val heights = (info.videoStreams + info.videoOnlyStreams)
            .map { it.height }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        Meta(
            url = url,
            title = info.name ?: "Untitled",
            thumb = info.thumbnails.lastOrNull()?.url,
            uploader = info.uploaderName ?: "",
            duration = info.duration.toInt(),
            heights = heights
        )
    }

    fun enqueueUrl(url: String, o: Opts, meta: Meta? = null) {
        val title = meta?.title ?: url.take(60)
        val j = DlJob(UUID.randomUUID().toString(), url, title, meta?.thumb, o)
        jobs.add(0, j)
        if (active.getAndIncrement() == 0) {
            KeepAliveService.start(app)
        }
        scope.launch { try { gate.withPermit { work(j) } } finally { finished() } }
    }

    fun retry(j: DlJob) {
        jobs.remove(j)
        enqueueUrl(j.url, j.opts, Meta(j.url, j.title, j.thumb, "", 0, emptyList()))
    }

    fun cancel(j: DlJob) {
        j.cancelled = true
        j.status = "Cancelled"
    }

    fun clearFinished() { jobs.removeAll { !it.active } }

    private fun finished() {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            KeepAliveService.stop(app)
        }
    }

    private fun safeFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "cherry_media" }

    private suspend fun work(j: DlJob) {
        if (j.cancelled) return
        val tmp = File(app.getExternalFilesDir(null), "tmp/${j.id}").apply { mkdirs() }
        try {
            ui { j.status = "Downloading"; j.note = "Extracting stream URLs…" }
            val info = StreamInfo.getInfo(j.url)
            val cleanTitle = safeFilename(info.name ?: j.title)
            ui { j.title = info.name ?: j.title }

            val outFile: File
            if (j.opts.audio) {
                val bestAudio = info.audioStreams
                    .filter { it.format == MediaFormat.M4A }
                    .maxByOrNull { it.averageBitrate }
                    ?: info.audioStreams.maxByOrNull { it.averageBitrate }
                    ?: error("No audio stream found.")

                val ext = bestAudio.format?.suffix ?: "m4a"
                outFile = File(tmp, "$cleanTitle.$ext")
                ui { j.note = "Downloading audio (8 threads)…" }
                FastEngine.downloadParallel(bestAudio.content, outFile, 8, { j.cancelled }) { p, eta ->
                    ui { if (!j.cancelled) { j.progress = p; j.eta = eta } }
                }
            } else {
                val dashVideo = info.videoOnlyStreams
                    .filter { it.format == MediaFormat.MPEG_4 && it.height <= j.opts.res }
                    .maxByOrNull { it.height }
                val dashAudio = info.audioStreams
                    .filter { it.format == MediaFormat.M4A }
                    .maxByOrNull { it.averageBitrate }

                if (dashVideo != null && dashAudio != null) {
                    val vFile = File(tmp, "video.mp4")
                    val aFile = File(tmp, "audio.m4a")
                    outFile = File(tmp, "$cleanTitle.mp4")

                    ui { j.note = "Downloading ${dashVideo.height}p video (8 threads)…" }
                    FastEngine.downloadParallel(dashVideo.content, vFile, 8, { j.cancelled }) { p, eta ->
                        ui { if (!j.cancelled) { j.progress = p * 0.78f; j.eta = eta } }
                    }

                    ui { j.note = "Downloading M4A audio (8 threads)…" }
                    FastEngine.downloadParallel(dashAudio.content, aFile, 8, { j.cancelled }) { p, eta ->
                        ui { if (!j.cancelled) { j.progress = 0.78f + p * 0.14f; j.eta = eta } }
                    }

                    ui { j.status = "Processing"; j.progress = 0.94f; j.note = "Muxing MP4 natively…" }
                    FastEngine.muxMp4(vFile, aFile, outFile)
                } else {
                    val prog = info.videoStreams
                        .filter { it.format == MediaFormat.MPEG_4 && it.height <= j.opts.res }
                        .maxByOrNull { it.height }
                        ?: info.videoStreams.maxByOrNull { it.height }
                        ?: error("No compatible MP4 stream found.")

                    outFile = File(tmp, "$cleanTitle.mp4")
                    ui { j.note = "Downloading ${prog.height}p MP4 (8 threads)…" }
                    FastEngine.downloadParallel(prog.content, outFile, 8, { j.cancelled }) { p, eta ->
                        ui { if (!j.cancelled) { j.progress = p; j.eta = eta } }
                    }
                }
            }

            if (j.cancelled) return
            val uri = save(outFile, j.opts.audio)
            val entry = Entry(
                title = info.name ?: cleanTitle,
                uri = uri,
                audio = j.opts.audio,
                size = outFile.length(),
                time = System.currentTimeMillis(),
                thumb = info.thumbnails.lastOrNull()?.url ?: j.thumb
            )
            ui {
                j.progress = 1f; j.status = "Done"; j.note = "Saved to Downloads/Cherry"
                history.removeAll { it.uri == entry.uri }
                history.add(0, entry)
                persist()
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
            if (!dir.exists() && !dir.mkdirs()) error("Storage permission needed. Allow it in app settings.")
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
        val loaded = mutableListOf<Entry>()
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val uri = o.getString("u")
            if (seen.add(uri)) {
                loaded.add(
                    Entry(
                        o.getString("t"), uri, o.getBoolean("a"),
                        o.getLong("s"), o.getLong("d"), o.optString("th").ifBlank { null }
                    )
                )
            }
        }
        ui {
            history.clear()
            history.addAll(loaded)
        }
    }

    fun updateCore(done: (String) -> Unit) {
        ui { done("Native Cherry Engine is built-in (no Python/yt-dlp needed ⚡)") }
    }

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.toString()
        return when {
            "Sign in" in m || "reCaptcha" in m || "bot" in m.lowercase() ->
                "YouTube bot check triggered. Go to Vibe → Sign in to YouTube and retry."
            "Private" in m -> "This video is private."
            "Unable to resolve host" in m || "Network is unreachable" in m -> "No internet connection."
            else -> m.lines().lastOrNull { it.isNotBlank() }?.take(140) ?: "Something went wrong."
        }
    }
}
