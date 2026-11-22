package com.cherry.studio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// ───────────────────────── Themes ─────────────────────────
class Pal(
    val name: String,
    val accent: Long, val accent2: Long, val fg: Long, val bg: Long,
    val bg2: Long, val surface: Long, val text: Long = 0xFFF9F8FF, val dim: Long = 0xFFA39EB8,
    val light: Boolean = false,
) {
    fun scheme(): ColorScheme {
        val base = if (light) lightColorScheme() else darkColorScheme()
        return base.copy(
            primary = Color(accent), onPrimary = Color(fg), secondary = Color(accent2),
            background = Color(bg), onBackground = Color(text),
            surface = Color(surface), onSurface = Color(text),
            surfaceVariant = Color(bg2), onSurfaceVariant = Color(dim),
            secondaryContainer = Color(accent).copy(alpha = 0.22f), onSecondaryContainer = Color(text),
            outline = Color(if (light) 0x20000000 else 0x1AFFFFFF),
        )
    }
}

val PALS = linkedMapOf(
    "cherry" to Pal("Cherry Noir", 0xFFFF2A5F, 0xFFFF8A5B, 0xFFFFFFFF, 0xFF06060A, 0xFF0E0D16, 0xD913111E),
    "acid" to Pal("Acid Matcha", 0xFFCCFF00, 0xFF10B981, 0xFF050806, 0xFF050806, 0xFF0B100D, 0xD9101713),
    "y2k" to Pal("Ultraviolet", 0xFFC084FC, 0xFF38BDF8, 0xFF080510, 0xFF06050C, 0xFF0D0A1A, 0xD9141024),
    "sunset" to Pal("Solar Flare", 0xFFFF5E36, 0xFFFBBF24, 0xFFFFFFFF, 0xFF090605, 0xFF120D0B, 0xD91A120F),
    "cyber" to Pal("Tokyo Cyan", 0xFF00F5D4, 0xFF38BDF8, 0xFF03100E, 0xFF04080C, 0xFF091118, 0xD90E1821),
    "light" to Pal("Paper Studio", 0xFFFF2A5F, 0xFF7C3AED, 0xFFFFFFFF, 0xFFF5F4F0, 0xFFEAE8E1, 0xEBFFFFFF,
        text = 0xFF121211, dim = 0xFF5E5D58, light = true),
)

private const val DISCORD_URL = "https://discord.gg/56Mcqv5jMd"
private const val UPI_B64 = "ODU5NTY0NTUxMkBmYW0="
private val URL_RE = Regex("https?://\\S+")

// ───────────────────────── Activity ─────────────────────────
class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf<String?>(null)
    private var crash by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        handle(intent)
        crash = getSharedPreferences("cherry", MODE_PRIVATE).getString("crash", null)
        setContent {
            val c = crash
            if (c != null) {
                CrashScreen(c) {
                    getSharedPreferences("cherry", MODE_PRIVATE).edit().remove("crash").apply()
                    crash = null
                }
            } else {
                Root(sharedUrl) { sharedUrl = null }
            }
        }
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

// ───────────────────────── Background & Confetti FX ─────────────────────────
private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float)
private class ConfettiPiece(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var rot: Float, val vr: Float, val size: Float, val color: Color, var life: Int
)

@Composable
fun StudioCanvasBackground(pal: Pal) {
    val accent = Color(pal.accent)
    val accent2 = Color(pal.accent2)
    val bg = Color(pal.bg)
    val gridColor = Color(if (pal.light) 0x0C000000 else 0x0EFFFFFF)

    val inf = rememberInfiniteTransition(label = "bg")
    val drift by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(15000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift"
    )
    val particles = remember {
        List(24) {
            Particle(
                Random.nextFloat(), Random.nextFloat(),
                (Random.nextFloat() - 0.5f) * 0.0014f,
                (Random.nextFloat() - 0.5f) * 0.0014f,
                2.5f + Random.nextFloat() * 3f
            )
        }
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { frame = it } } }

    Canvas(Modifier.fillMaxSize().background(bg)) {
        val w = size.width; val h = size.height
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = if (pal.light) 0.12f else 0.18f), Color.Transparent),
                Offset(w * (0.2f + 0.2f * drift), h * 0.12f), w * 0.8f
            ),
            w * 0.8f, Offset(w * (0.2f + 0.2f * drift), h * 0.12f)
        )
        drawCircle(
            Brush.radialGradient(
                listOf(accent2.copy(alpha = if (pal.light) 0.09f else 0.14f), Color.Transparent),
                Offset(w * (0.8f - 0.2f * drift), h * 0.85f), w * 0.75f
            ),
            w * 0.75f, Offset(w * (0.8f - 0.2f * drift), h * 0.85f)
        )
        val step = 44.dp.toPx()
        var gx = 0f; while (gx < w) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), 1f); gx += step }
        var gy = 0f; while (gy < h) { drawLine(gridColor, Offset(0f, gy), Offset(w, gy), 1f); gy += step }

        @Suppress("UNUSED_EXPRESSION") frame
        for (p in particles) {
            p.x += p.vx; p.y += p.vy
            if (p.x < 0f || p.x > 1f) p.vx *= -1f
            if (p.y < 0f || p.y > 1f) p.vy *= -1f
            drawCircle(accent.copy(alpha = 0.45f), p.r, Offset(p.x * w, p.y * h))
        }
        val maxDist = 105.dp.toPx()
        for (i in particles.indices) {
            val p1 = particles[i]; val x1 = p1.x * w; val y1 = p1.y * h
            for (j in i + 1 until particles.size) {
                val p2 = particles[j]
                val d = hypot(x1 - p2.x * w, y1 - p2.y * h)
                if (d < maxDist) {
                    drawLine(accent.copy(alpha = (1f - d / maxDist) * 0.18f), Offset(x1, y1), Offset(p2.x * w, p2.y * h), 1f)
                }
            }
        }
    }
}

@Composable
fun ConfettiOverlay(trigger: Int) {
    val pieces = remember { mutableStateListOf<ConfettiPiece>() }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        val cols = listOf(Color(0xFFFF2A5F), Color(0xFFFFB800), Color(0xFF00F5D4), Color(0xFFC084FC), Color(0xFFCCFF00))
        repeat(70) { i ->
            val ang = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val spd = 6f + Random.nextFloat() * 14f
            pieces.add(
                ConfettiPiece(
                    0.5f, 0.45f, cos(ang) * spd, sin(ang) * spd - 8f,
                    Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 14f,
                    11f + Random.nextFloat() * 9f, cols[i % cols.size], 90 + Random.nextInt(35)
                )
            )
        }
        while (pieces.isNotEmpty()) { withFrameNanos { tick = it } }
    }
    if (pieces.isNotEmpty()) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            val iter = pieces.listIterator()
            while (iter.hasNext()) {
                val k = iter.next()
                k.x += k.vx / size.width; k.y += k.vy / size.height; k.vy += 0.45f; k.rot += k.vr; k.life -= 1
                if (k.life <= 0 || k.y > 1.1f) iter.remove()
                else {
                    val cx = k.x * size.width; val cy = k.y * size.height
                    rotate(k.rot, Offset(cx, cy)) {
                        drawRect(k.color, Offset(cx - k.size / 2, cy - k.size / 3), Size(k.size, k.size * 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun LiquidProgressBar(progress: Float, accent: Color, accent2: Color) {
    val inf = rememberInfiniteTransition(label = "liquid")
    val shift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shift")
    Box(
        Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    ) {
        Canvas(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().clip(CircleShape)) {
            val w = size.width
            drawRect(Brush.horizontalGradient(listOf(accent, accent2, accent), -w + (w * 2f * shift), w + (w * 2f * shift)))
        }
    }
}

// ───────────────────────── Crash Screen ─────────────────────────
@Composable
fun CrashScreen(text: String, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFF06060A)).padding(16.dp)) {
        Text("Cherry crashed last time", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row {
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", text))
                toast(ctx, "Copied")
            }) { Text("Copy") }
            TextButton(onClick = dismiss) { Text("Dismiss") }
        }
        Text(text.take(2500), color = Color(0xFFFFB4C0), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

// ───────────────────────── Root Shell ─────────────────────────
@Composable
fun Root(shared: String?, consume: () -> Unit) {
    val pal = PALS[Engine.theme] ?: PALS.getValue("cherry")
    var tab by remember { mutableIntStateOf(0) }
    var showSupport by remember { mutableStateOf(false) }
    var showYoutubeLogin by remember { mutableStateOf(false) }
    LaunchedEffect(shared) { if (shared != null) tab = 0 }
    val running = Engine.jobs.count { it.active }

    MaterialTheme(colorScheme = pal.scheme()) {
        Box(Modifier.fillMaxSize()) {
            StudioCanvasBackground(pal)

            Scaffold(
                containerColor = Color.Transparent,
                topBar = { CompactTopBar(pal) { showSupport = true } },
                bottomBar = {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("Studio" to "⚡", "Queue" to "🌊", "Vault" to "📼", "Vibe" to "🎨")
                                .forEachIndexed { i, (label, emoji) ->
                                    val selected = tab == i
                                    val c = MaterialTheme.colorScheme
                                    Row(
                                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                            .background(
                                                if (selected) Brush.horizontalGradient(listOf(c.primary, c.secondary))
                                                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                            )
                                            .clickable { tab = i }
                                            .padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(emoji, fontSize = 15.sp)
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            if (i == 1 && running > 0) "$label ($running)" else label,
                                            color = if (selected) c.onPrimary else c.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
                                        )
                                    }
                                }
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220)))
                                .togetherWith(fadeOut(tween(150)))
                        },
                        label = "tab"
                    ) { current ->
                        when (current) {
                            0 -> StudioScreen(shared, consume) { tab = 1 }
                            1 -> QueueScreen()
                            2 -> VaultScreen()
                            else -> VibeScreen({ showSupport = true }, { showYoutubeLogin = true })
                        }
                    }
                }
            }

            if (showSupport) SupportModal(pal) { showSupport = false }
            if (showYoutubeLogin) YoutubeLoginDialog { showYoutubeLogin = false }
            ConfettiOverlay(Engine.confettiTick)
        }
    }
}

@Composable
fun CompactTopBar(pal: Pal, onSupport: () -> Unit) {
    val accent = Color(pal.accent)
    val accent2 = Color(pal.accent2)
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(accent, accent2))),
                contentAlignment = Alignment.Center
            ) { Text("🍒", fontSize = 18.sp) }
            Column {
                Text("cherry studio.", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (Engine.hasCookies) "● COOKIES ACTIVE" else "● READY • UNLOCKED",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        }
        Box(
            Modifier.clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF2A5F), Color(0xFFFF8A5B))))
                .clickable(onClick = onSupport)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) { Text("💖 Support", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp) }
    }
}

// ───────────────────────── Reusable Bento Components ─────────────────────────
@Composable
fun BentoCard(title: String, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            if (tag != null) {
                Text(
                    tag, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        content()
    }
}

@Composable
fun SelectTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) Brush.horizontalGradient(listOf(c.primary, c.secondary))
                else Brush.horizontalGradient(listOf(c.surfaceVariant, c.surfaceVariant))
            )
            .border(1.dp, if (selected) c.primary else c.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) c.onPrimary else c.onSurfaceVariant,
            fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1
        )
    }
}

@Composable
fun ToggleBentoTile(
    emoji: String, title: String, subtitle: String,
    checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(if (checked) c.primary.copy(alpha = 0.16f) else c.surfaceVariant)
            .border(1.5.dp, if (checked) c.primary else c.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Text(
                if (checked) "ON" else "OFF",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold,
                color = if (checked) c.onPrimary else c.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape)
                    .background(if (checked) c.primary else c.surface)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.onSurface)
        Text(subtitle, fontSize = 11.sp, color = c.onSurfaceVariant, lineHeight = 14.sp)
    }
}

@Composable
fun PrimaryCTA(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(c.primary, c.secondary)), alpha = if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = c.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

@Composable
fun Thumb(url: String?, size: Int = 64) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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

// ───────────────────────── Studio Screen (New Bento UI) ─────────────────────────
@Composable
fun StudioScreen(shared: String?, consume: () -> Unit, goQueue: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var ai by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<Meta?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    var audio by remember { mutableStateOf(false) }
    var res by remember { mutableIntStateOf(1080) }
    var bit by remember { mutableIntStateOf(320) }
    var subs by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("en") }
    var sb by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    fun fetch() {
        if (url.isBlank()) return
        busy = true; err = ""; meta = null
        scope.launch {
            try { meta = Engine.fetch(url.trim()) } catch (e: Throwable) { err = Engine.friendly(e) }
            busy = false
        }
    }
    LaunchedEffect(shared) { if (shared != null) { url = shared; consume(); fetch() } }

    fun applyAi(input: String = ai) {
        URL_RE.find(input)?.value?.let { url = it; fetch() }
        val r = NL.parse(input)
        r.audio?.let { audio = it }; r.res?.let { res = it }; r.bitrate?.let { bit = it }
        r.sb?.let { sb = it }; r.subs?.let { subs = it }; r.lang?.let { lang = it }
        r.start?.let { start = it }; r.end?.let { end = it }
        ai = ""
        toast(ctx, "🤖 Preset applied")
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Source URL Card
            BentoCard("Media Link", if (audio) "MP3 ${bit}K" else "${if (res == 2160) "4K" else if (res == 4320) "8K" else "${res}P"} MP4") {
                OutlinedTextField(
                    url, { url = it; if (it.isBlank()) meta = null },
                    Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Paste YouTube, Instagram, TikTok, or X link…", fontSize = 13.sp) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectTile("📋 Paste", false, {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        URL_RE.find(t)?.value?.let { url = it } ?: toast(ctx, "No link on clipboard")
                    }, Modifier.weight(1f))
                    SelectTile(if (busy) "Inspecting…" else "🔍 Inspect", false, { if (!busy && url.isNotBlank()) fetch() }, Modifier.weight(1f))
                }
                if (busy) LiquidProgressBar(0.65f, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

                meta?.let { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumb(m.thumb, 60)
                        Column(Modifier.weight(1f)) {
                            Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${m.uploader} • ${fmtDur(m.duration)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 2. Format, Resolution & Audio Bitrate Deck
            BentoCard("Quality & Format", if (audio) "AUDIO BITRATE" else "VIDEO RESOLUTION") {
                // Mode Segmented Switcher
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SelectTile("🎬 Video (MP4)", !audio, { audio = false }, Modifier.weight(1f))
                    SelectTile("🎧 Audio (MP3)", audio, { audio = true }, Modifier.weight(1f))
                }

                if (!audio) {
                    // Resolution Grid (2 clean rows)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(360 to "360p", 480 to "480p", 720 to "720p", 1080 to "1080p").forEach { (v, lbl) ->
                            SelectTile(lbl, res == v, { res = v }, Modifier.weight(1f))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1440 to "2K", 2160 to "4K UHD", 4320 to "8K MAX").forEach { (v, lbl) ->
                            SelectTile(lbl, res == v, { res = v }, Modifier.weight(1f))
                        }
                    }
                } else {
                    // Audio Bitrate Grid
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(128 to "128k", 192 to "192k", 256 to "256k", 320 to "320k").forEach { (v, lbl) ->
                            SelectTile(lbl, bit == v, { bit = v }, Modifier.weight(1f))
                        }
                    }
                }
            }

            // 3. SponsorBlock & Embedded Subtitles Bento Tiles
            BentoCard("Smart Post-Processors", "FFMPEG") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleBentoTile(
                        emoji = "🛡️",
                        title = "SponsorBlock",
                        subtitle = "Auto-cut sponsors & intros",
                        checked = sb,
                        onToggle = { sb = !sb },
                        modifier = Modifier.weight(1f)
                    )
                    ToggleBentoTile(
                        emoji = "💬",
                        title = "Subtitles",
                        subtitle = if (audio) "Video mode only" else "Embed ${lang.uppercase()} track",
                        checked = subs && !audio,
                        onToggle = {
                            if (audio) toast(ctx, "Switch to Video (MP4) for subtitles")
                            else subs = !subs
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Expandable Subtitle Language Picker
                AnimatedVisibility(visible = subs && !audio, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SUBTITLE LANGUAGE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("en", "hi", "es", "ja", "ko", "fr", "de", "ar").forEach { code ->
                                SelectTile(code.uppercase(), lang == code, { lang = code }, Modifier.width(56.dp))
                            }
                        }
                    }
                }
            }

            // 4. Collapsible AI Bar + Trim + Speed Limit
            BentoCard("AI Bar & Clip Trimmer", if (showAdvanced) "HIDE" else "EXPAND") {
                Row(
                    Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI commands, trim timestamps & speed cap", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (showAdvanced) "▲" else "▼", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            ai, { ai = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("e.g. '4k with hindi subs and sponsorblock'", fontSize = 12.sp) },
                            trailingIcon = { TextButton(onClick = { applyAi() }, enabled = ai.isNotBlank()) { Text("Apply") } }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Trim from") }, placeholder = { Text("0:00") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("to") }, placeholder = { Text("1:30") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("" to "Uncapped", "500K" to "500 KB/s", "2M" to "2 MB/s", "5M" to "5 MB/s").forEach { (v, l) ->
                                SelectTile(l, limit == v, { limit = v })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }

        // Sticky Bottom Download Action
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            PrimaryCTA("🚀 Download Now", enabled = url.isNotBlank()) {
                val clean = url.trim()
                if (clean.isBlank()) toast(ctx, "Paste a link first")
                else {
                    Engine.enqueueUrl(
                        clean,
                        Opts(audio, res, bit, subs && !audio, lang, sb, start.trim(), end.trim(), limit),
                        meta
                    )
                    goQueue()
                }
            }
        }
    }
}

// ───────────────────────── Queue Screen ─────────────────────────
@Composable
fun QueueScreen() {
    val jobs = Engine.jobs
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live Queue", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { Engine.clearFinished() }) { Text("Clear finished") }
        }
        if (jobs.isEmpty()) {
            BentoCard("Idle") {
                Text("No active downloads yet. Paste a link in ⚡ Studio and tap Download Now.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { j ->
                BentoCard(j.status, "${(j.progress * 100).toInt()}%") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(j.thumb, 58)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(j.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val eta = if (j.status == "Downloading" && j.eta > 0) " • ETA ${j.eta}s" else ""
                            Text("${j.status}$eta", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (j.active) LiquidProgressBar(j.progress, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                    if (j.note.isNotBlank()) {
                        Text(j.note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (j.active) SelectTile("⏹ Cancel", false, { Engine.cancel(j) }, Modifier.weight(1f))
                        if (j.status == "Failed" || j.status == "Cancelled") SelectTile("🔄 Retry", true, { Engine.retry(j) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ───────────────────────── Vault Screen ─────────────────────────
@Composable
fun VaultScreen() {
    val ctx = LocalContext.current
    val h = Engine.history
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Vault Archive", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("${h.size} files • ${humanSize(h.sumOf { it.size })}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(h, key = { it.uri }) { e ->
                BentoCard(if (e.audio) "Audio" else "Video", humanSize(e.size)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(e.thumb, 58)
                        Column(Modifier.weight(1f)) {
                            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(DateFormat.getDateInstance().format(Date(e.time)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectTile("▶ Open", true, {
                            val u = Uri.parse(e.uri)
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, ctx.contentResolver.getType(u))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) { toast(ctx, "No app to open this") }
                        }, Modifier.weight(1f))
                        SelectTile("↗ Share", false, {
                            val u = Uri.parse(e.uri)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(u) ?: "*/*")
                                .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        }, Modifier.weight(1f))
                        SelectTile("✕", false, { Engine.deleteEntry(e) }, Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────── Vibe (Settings) Screen ─────────────────────────
@Composable
fun VibeScreen(onSupport: () -> Unit, onYoutubeLogin: () -> Unit) {
    val ctx = LocalContext.current
    var par by remember { mutableIntStateOf(Engine.parallel) }
    var msg by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BentoCard("YouTube Sign-In (Bot-Check Fix)", if (Engine.hasCookies) "CONNECTED" else "OPTIONAL") {
            Text(
                if (Engine.hasCookies) "YouTube cookies are active and attached to downloads."
                else "If YouTube blocks your IP with a sign-in check, log in once here to save cookies.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectTile("🔑 Sign in to YouTube", true, onYoutubeLogin, Modifier.weight(1f))
                if (Engine.hasCookies) SelectTile("Clear", false, { Engine.clearYoutubeCookies(); toast(ctx, "Cleared") }, Modifier.width(74.dp))
            }
        }

        BentoCard("Engine Core", "YT-DLP NIGHTLY") {
            Text(Engine.coreStatus, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            PrimaryCTA("🔄 Update yt-dlp (Nightly)") {
                msg = "Updating to latest Nightly…"
                Engine.updateCore { msg = it }
            }
            if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        BentoCard("Studio Themes") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PALS.forEach { (id, p) ->
                    val selected = Engine.theme == id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.5.dp, if (selected) Color(p.accent) else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            .clickable { Engine.setTheme(id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(p.accent), Color(p.accent2)))),
                            contentAlignment = Alignment.Center
                        ) { if (selected) Text("✓", color = Color(p.fg), fontWeight = FontWeight.ExtraBold) }
                        Spacer(Modifier.height(6.dp))
                        Text(p.name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        BentoCard("Parallel Downloads") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Max concurrent", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                SelectTile("−", false, { if (par > 1) { par--; Engine.setParallel(par) } }, Modifier.width(44.dp))
                Text("$par", fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                SelectTile("+", false, { if (par < 4) { par++; Engine.setParallel(par) } }, Modifier.width(44.dp))
            }
        }

        BentoCard("Community") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectTile("💖 Support", true, onSupport, Modifier.weight(1f))
                SelectTile("💬 Discord", false, { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL))) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ───────────────────────── In-App YouTube Cookie Login ─────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YoutubeLoginDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Sign in, then tap Save", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectTile("Save Cookies", true, {
                        val cm = CookieManager.getInstance()
                        cm.flush()
                        val raw = cm.getCookie("https://www.youtube.com") ?: cm.getCookie("https://m.youtube.com") ?: ""
                        if (Engine.saveYoutubeCookies(raw)) {
                            toast(ctx, "✅ Cookies saved!")
                            onDismiss()
                        } else {
                            toast(ctx, "Finish signing in first")
                        }
                    })
                    SelectTile("✕", false, onDismiss, Modifier.width(44.dp))
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl("https://m.youtube.com")
                    }
                }
            )
        }
    }
}

// ───────────────────────── Support Modal ─────────────────────────
@Composable
fun SupportModal(pal: Pal, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val upi = remember { String(Base64.decode(UPI_B64, Base64.DEFAULT)).trim() }
    var selectedAmt by remember { mutableStateOf("99") }

    fun pay(amount: String) {
        val b = Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", upi)
            .appendQueryParameter("pn", "Cherry Studio")
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", "Supporting Cherry Studio")
        if (amount.isNotBlank()) b.appendQueryParameter("am", amount)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, b.build()))
            Engine.triggerConfetti()
        } catch (_: Exception) { toast(ctx, "No UPI app found") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(pal.bg2),
        title = { Text("💖 Support Cherry Studio!", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("100% free & unlocked (4K/8K, Subtitles, SponsorBlock & 320k MP3). Pick a tier to fuel future updates!", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("☕ ₹49" to "49", "🍕 ₹99" to "99", "🚀 ₹249" to "249", "💝 Any" to "").forEach { (l, a) ->
                        SelectTile(l, selectedAmt == a, { selectedAmt = a }, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { PrimaryCTA("🎉 Open UPI App") { pay(selectedAmt); onDismiss() } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
