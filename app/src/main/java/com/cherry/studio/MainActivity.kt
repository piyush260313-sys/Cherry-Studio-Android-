package com.cherry.studio

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ───────────────────────── Themes (same palettes as Cherry desktop) ─────────────────────────
class Pal(
    val accent: Long, val accent2: Long, val fg: Long, val bg: Long,
    val bg2: Long, val surface: Long, val text: Long = 0xFFF5F5F7, val dim: Long = 0xFF9A9AA8,
    val light: Boolean = false,
) {
    fun scheme(): ColorScheme {
        val base = if (light) lightColorScheme() else darkColorScheme()
        return base.copy(
            primary = Color(accent), onPrimary = Color(fg), secondary = Color(accent2),
            background = Color(bg), onBackground = Color(text),
            surface = Color(surface), onSurface = Color(text),
            surfaceVariant = Color(bg2), onSurfaceVariant = Color(dim),
            secondaryContainer = Color(accent).copy(alpha = 0.25f), onSecondaryContainer = Color(text),
            outline = Color(dim).copy(alpha = 0.5f),
        )
    }
}

val PALS = linkedMapOf(
    "cherry" to Pal(0xFFFF2A5F, 0xFFFF8A5B, 0xFFFFFFFF, 0xFF050508, 0xFF0B0A12, 0xFF100E1A),
    "acid" to Pal(0xFFCCFF00, 0xFF10B981, 0xFF050806, 0xFF050706, 0xFF0A0E0C, 0xFF0E1310),
    "y2k" to Pal(0xFFC084FC, 0xFF38BDF8, 0xFF080510, 0xFF05040A, 0xFF0A0814, 0xFF110E1E),
    "sunset" to Pal(0xFFFF5E36, 0xFFFBBF24, 0xFFFFFFFF, 0xFF080605, 0xFF100C0A, 0xFF16100E),
    "cyber" to Pal(0xFF00F5D4, 0xFF38BDF8, 0xFF03100E, 0xFF03070A, 0xFF080F15, 0xFF0C151D),
    "light" to Pal(0xFFFF2A5F, 0xFF7C3AED, 0xFFFFFFFF, 0xFFF4F3EE, 0xFFEAE8E0, 0xFFFFFFFF,
        text = 0xFF14131A, dim = 0xFF6B6A75, light = true),
)

private const val DISCORD_URL = "https://discord.gg/56Mcqv5jMd"
private const val UPI_B64 = "ODU5NTY0NTUxMkBmYW0=" // same masked ID as the desktop app
private val URL_RE = Regex("https?://\\S+")

// ───────────────────────── Activity ─────────────────────────
class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handle(intent)
        setContent { Root(sharedUrl) { sharedUrl = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            sharedUrl = URL_RE.find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
        }
    }
}

// ───────────────────────── Root ─────────────────────────
@Composable
fun Root(shared: String?, consume: () -> Unit) {
    val pal = PALS[Engine.theme] ?: PALS.getValue("cherry")
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(shared) { if (shared != null) tab = 0 }
    val running = Engine.jobs.count { it.active }

    MaterialTheme(colorScheme = pal.scheme()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    listOf("Get" to "⬇️", "Queue" to "⚡", "Vault" to "🗄️", "Settings" to "⚙️")
                        .forEachIndexed { i, (label, emoji) ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = {
                                    if (i == 1 && running > 0) BadgedBox(badge = { Badge { Text("$running") } }) { Text(emoji) }
                                    else Text(emoji)
                                },
                                label = { Text(label) },
                            )
                        }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> GetScreen(shared, consume) { tab = 1 }
                    1 -> QueueScreen()
                    2 -> VaultScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}

// ───────────────────────── Shared bits ─────────────────────────
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        content()
    }
}

@Composable
fun Chip(label: String, on: Boolean, click: () -> Unit) =
    FilterChip(selected = on, onClick = click, label = { Text(label) })

@Composable
fun GradientButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(c.primary, c.secondary)), alpha = if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
fun Thumb(url: String?, size: Int = 64) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

fun humanSize(b: Long): String {
    var v = b.toDouble(); val u = arrayOf("B", "KB", "MB", "GB", "TB"); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

fun fmtDur(s: Int): String =
    if (s <= 0) "" else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

// ───────────────────────── Get ─────────────────────────
@Composable
fun GetScreen(shared: String?, consume: () -> Unit, goQueue: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var ai by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<Meta?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf(false) }
    var res by remember { mutableIntStateOf(1080) }
    var bit by remember { mutableIntStateOf(192) }
    var subs by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("en") }
    var sb by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }

    fun fetch() {
        if (url.isBlank()) return
        busy = true; err = ""; meta = null
        scope.launch {
            try { meta = Engine.fetch(url.trim()) } catch (e: Exception) { err = Engine.friendly(e) }
            busy = false
        }
    }
    LaunchedEffect(shared) { if (shared != null) { url = shared; consume(); fetch() } }

    fun applyAi() {
        URL_RE.find(ai)?.value?.let { url = it; fetch() }
        val r = NL.parse(ai)
        r.audio?.let { audio = it }; r.res?.let { res = it }; r.bitrate?.let { bit = it }
        r.sb?.let { sb = it }; r.subs?.let { subs = it }; r.start?.let { start = it }; r.end?.let { end = it }
        ai = ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cherry Studio", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)

        Section("AI bar") {
            OutlinedTextField(
                ai, { ai = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("“mp3 320k from 1:20 to 2:30, skip sponsors”") },
                trailingIcon = { TextButton(onClick = { applyAi() }, enabled = ai.isNotBlank()) { Text("Go") } },
            )
        }

        Section("Link") {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Paste a video link") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    URL_RE.find(t)?.value?.let { url = it; fetch() } ?: toast(ctx, "No link on clipboard")
                }) { Text("📋 Paste") }
                Button(onClick = { fetch() }, enabled = url.isNotBlank() && !busy) { Text(if (busy) "Fetching…" else "Fetch") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error)
        }

        meta?.let { m ->
            Section("Preview") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Thumb(m.thumb, 84)
                    Column(Modifier.weight(1f)) {
                        Text(m.title, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${m.uploader}  ${fmtDur(m.duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            Section("Format") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("🎬 MP4 video", !audio) { audio = false }
                    Chip("🎵 MP3 audio", audio) { audio = true }
                }
                if (!audio) {
                    val all = listOf(4320, 2160, 1440, 1080, 720, 480, 360)
                    val opts = if (m.heights.isEmpty()) all else all.filter { it <= (m.heights.maxOrNull() ?: 4320) }.ifEmpty { listOf(360) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        opts.forEach { h -> Chip(when (h) { 4320 -> "8K"; 2160 -> "4K"; else -> "${h}p" }, res == h) { res = h } }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(128, 192, 256, 320).forEach { b -> Chip("${b}k", bit == b) { bit = b } }
                    }
                }
            }

            Section("Extras") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SponsorBlock (auto-cut sponsors)", Modifier.weight(1f)); Switch(sb, { sb = it })
                }
                if (!audio) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Embed subtitles", Modifier.weight(1f)); Switch(subs, { subs = it })
                }
                if (subs && !audio) OutlinedTextField(lang, { lang = it }, label = { Text("Language code (en, hi, es…)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Trim from") }, placeholder = { Text("1:20") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("to") }, placeholder = { Text("2:30") }, singleLine = true)
                }
                Text("Speed limit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "∞", "500K" to "500 KB/s", "2M" to "2 MB/s", "5M" to "5 MB/s").forEach { (v, l) -> Chip(l, limit == v) { limit = v } }
                }
            }

            GradientButton("⬇  Download") {
                Engine.enqueue(m, Opts(audio, res, bit, subs && !audio, lang, sb, start.trim(), end.trim(), limit))
                toast(ctx, "Added to queue")
                goQueue()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ───────────────────────── Queue ─────────────────────────
@Composable
fun QueueScreen() {
    val jobs = Engine.jobs
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { Engine.clearFinished() }) { Text("Clear finished") }
        }
        if (jobs.isEmpty()) Text("Nothing here yet. Paste a link on the Get tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { j ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Thumb(j.thumb)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(j.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        if (j.active) LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth())
                        val eta = if (j.status == "Downloading" && j.eta > 0) " • ${j.eta}s left" else ""
                        Text("${j.status} ${(j.progress * 100).toInt()}%$eta", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        if (j.note.isNotBlank()) Text(j.note, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            if (j.active) TextButton(onClick = { Engine.cancel(j) }) { Text("Cancel") }
                            if (j.status == "Failed" || j.status == "Cancelled") TextButton(onClick = { Engine.retry(j) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Vault ─────────────────────────
@Composable
fun VaultScreen() {
    val ctx = LocalContext.current
    val h = Engine.history
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Vault", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("${h.size} files • ${humanSize(h.sumOf { it.size })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(h, key = { it.uri }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Thumb(e.thumb)
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${if (e.audio) "🎵" else "🎬"} ${humanSize(e.size)} • ${DateFormat.getDateInstance().format(Date(e.time))}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = {
                                val u = Uri.parse(e.uri)
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, ctx.contentResolver.getType(u))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } catch (x: Exception) { toast(ctx, "No app to open this") }
                            }) { Text("Open") }
                            TextButton(onClick = {
                                val u = Uri.parse(e.uri)
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(u) ?: "*/*")
                                    .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                            }) { Text("Share") }
                            TextButton(onClick = { Engine.deleteEntry(e) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Settings ─────────────────────────
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var par by remember { mutableIntStateOf(Engine.parallel) }
    var msg by remember { mutableStateOf("") }
    val upi = remember { String(Base64.decode(UPI_B64, Base64.DEFAULT)).trim() }

    fun pay(amount: String) {
        val b = Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", upi).appendQueryParameter("pn", "Cherry Studio").appendQueryParameter("cu", "INR")
        if (amount.isNotBlank()) b.appendQueryParameter("am", amount)
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, b.build())) } catch (e: Exception) { toast(ctx, "No UPI app found") }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)

        Section("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PALS.forEach { (name, p) ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(p.accent), Color(p.accent2))))
                            .clickable { Engine.setTheme(name) },
                        contentAlignment = Alignment.Center,
                    ) { if (Engine.theme == name) Text("✓", color = Color(p.fg), fontWeight = FontWeight.Bold) }
                }
            }
        }

        Section("Downloads") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Parallel downloads", Modifier.weight(1f))
                OutlinedButton(onClick = { if (par > 1) { par--; Engine.setParallel(par) } }) { Text("−") }
                Text("$par", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { if (par < 4) { par++; Engine.setParallel(par) } }) { Text("+") }
            }
            Text("Applies after restarting the app. Files save to Downloads/Cherry.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Engine") {
            Text(Engine.coreStatus)
            Button(onClick = { msg = "Updating…"; Engine.updateCore { msg = it } }) { Text("Update yt-dlp") }
            if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("If a site stops working, updating yt-dlp usually fixes it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Support the creator") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("☕ ₹49" to "49", "🍕 ₹99" to "99", "🚀 ₹249" to "249", "💝 Any" to "").forEach { (l, a) ->
                    OutlinedButton(onClick = { pay(a) }) { Text(l) }
                }
            }
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL))) }) { Text("Join the Discord") }
        }
        Text("Cherry Studio for Android v1.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
