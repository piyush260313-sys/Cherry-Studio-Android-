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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Opts(
    val audio: Boolean,
    val res: Int,
    val bitrate: Int,
    val subs: Boolean,
    val lang: String,
    val start: String,
    val end: String,
    val limit: String,
    val audioTrack: String = "",
)

data class Meta(
    val url: String,
    val title: String,
    val thumb: String?,
    val uploader: String,
    val duration: Int,
    val heights: List<Int>,
    val videoBitrates: Map<Int, Int> = emptyMap(),
    val maxAudioKbps: Int = 128,
    val audioTracks: List<String> = emptyList(),
    val subtitleLangs: List<String> = emptyList(),
    val playlistUrl: String? = null,
) {
    fun estimateBytes(audioOnly: Boolean, targetRes: Int, targetKbps: Int): Long {
        if (duration <= 0) return 0L
        val aKbps = if (audioOnly) minOf(targetKbps, maxAudioKbps).coerceAtLeast(64) else maxAudioKbps.coerceAtLeast(128)
        val audioBytes = (aKbps * 1000L / 8L) * duration
        if (audioOnly) return audioBytes
        val matchedHeight = heights.filter { it <= targetRes }.maxOrNull() ?: heights.minOrNull() ?: return 0L
        val vBps = videoBitrates[matchedHeight]?.toLong() ?: return 0L
        val normBps = if (vBps < 20_000L) vBps * 1000L else vBps
        return (normBps / 8L) * duration + audioBytes
    }
}

data class PlaylistEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val duration: Int,
    val thumb: String?,
)

data class PlaylistMeta(
    val url: String,
    val title: String,
    val uploader: String,
    val items: List<PlaylistEntry>,
)

data class Entry(
    val title: String,
    val uri: String,
    val audio: Boolean,
    val size: Long,
    val time: Long,
    val thumb: String?,
    val sourceUrl: String = "",
)

class DlJob(
    val id: String,
    val url: String,
    initialTitle: String,
    val thumb: String?,
    val opts: Opts
) {
    var title by mutableStateOf(initialTitle)
    var progress by mutableStateOf(0f)
    var status by mutableStateOf("Queued")
    var eta by mutableStateOf(0L)
    var speedBps by mutableStateOf(0L)
    var downloadedBytes by mutableStateOf(0L)
    var totalBytes by mutableStateOf(0L)
    var note by mutableStateOf("")
    @Volatile var cancelled = false
    val active get() = status == "Queued" || status == "Downloading" || status == "Processing"
}

/** Natural-language quick command parser. */
object NL {
    data class Parsed(
        var audio: Boolean? = null,
        var res: Int? = null,
        var bitrate: Int? = null,
        var subs: Boolean? = null,
        var lang: String? = null,
        var start: String? = null,
        var end: String? = null,
        var limit: String? = null,
    )

    private val langMap = mapOf(
        "english" to "en", "hindi" to "hi", "spanish" to "es", "japanese" to "ja",
        "korean" to "ko", "french" to "fr", "german" to "de", "arabic" to "ar"
    )

    fun parse(text: String): Parsed {
        val t = text.lowercase()
        val p = Parsed()
        if (Regex("\\b(mp3|m4a|audio|music|song)\\b").containsMatchIn(t)) p.audio = true
        if (Regex("\\b(mp4|video)\\b").containsMatchIn(t)) p.audio = false
        Regex("(8k|4k|2160|1440|1080|720|480|360)").find(t)?.value?.let {
            p.res = when (it) { "8k" -> 4320; "4k" -> 2160; else -> it.toInt() }
        }
        Regex("(128|192|256|320)\\s*k").find(t)?.groupValues?.get(1)?.let { p.bitrate = it.toInt() }
        if (Regex("\\bsubs?\\b|subtitle|caption|\\bcc\\b").containsMatchIn(t)) {
            p.subs = true
            langMap.entries.firstOrNull { it.key in t }?.let { p.lang = it.value }
        }
        Regex("(\\d{1,2}(?::\\d{2}){1,2})\\s*(?:to|-|until)\\s*(\\d{1,2}(?::\\d{2}){1,2})").find(t)?.let {
            p.start = it.groupValues[1]; p.end = it.groupValues[2]
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(mb|kb)(?:/s)?").find(t)?.let { m ->
            val v = m.groupValues[1]
            val unit = if (m.groupValues[2] == "mb") "M" else "K"
            p.limit = "$v$unit"
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

/** 8-Thread Parallel Chunk Downloader (with real speed throttling) + Native MediaMuxer (with trim). */
private object FastEngine {
    private const val UA =
        "Mozilla/5.
