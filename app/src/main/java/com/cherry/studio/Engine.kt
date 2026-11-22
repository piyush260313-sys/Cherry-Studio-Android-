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
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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

// ───────────────────────── Themes (matching main.py v16.0) ─────────────────────────
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
            outline = Color(if (light) 0x22000000 else 0x1AFFFFFF),
        )
    }
}

val PALS = linkedMapOf(
    "cherry" to Pal("Cherry Noir", 0xFFFF2A5F, 0xFFFF8A5B, 0xFFFFFFFF, 0xFF050508, 0xFF0B0A12, 0xCC100E1A),
    "acid" to Pal("Acid Matcha", 0xFFCCFF00, 0xFF10B981, 0xFF050806, 0xFF050706, 0xFF0A0E0C, 0xCC0E1310),
    "y2k" to Pal("Ultraviolet", 0xFFC084FC, 0xFF38BDF8, 0xFF080510, 0xFF05040A, 0xFF0A0814, 0xCC110E1E),
    "sunset" to Pal("Solar Flare", 0xFFFF5E36, 0xFFFBBF24, 0xFFFFFFFF, 0xFF080605, 0xFF100C0A, 0xCC16100E),
    "cyber" to Pal("Tokyo Cyan", 0xFF00F5D4, 0xFF38BDF8, 0xFF03100E, 0xFF03070A, 0xFF080F15, 0xCC0C151D),
    "light" to Pal("Paper Studio", 0xFFFF2A5F, 0xFF7C3AED, 0xFFFFFFFF, 0xFFF4F3EE, 0xFFEAE8E0, 0xE6FFFFFF,
        text = 0xFF121211, dim = 0xFF5C5B57, light = true),
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

// ───────────────────────── 60FPS Particle & Aurora FX Engine ─────────────────────────
private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float)
private class ConfettiPiece(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var rot: Float, val vr: Float, val size: Float, val color: Color, var life: Int
)

@Composable
fun HyperKineticBackground(pal: Pal) {
    val accent = Color(pal.accent)
    val accent2 = Color(pal.accent2)
    val bg = Color(pal.bg)
    val gridColor = Color(if (pal.light) 0x10000000 else 0x12FFFFFF)

    val inf = rememberInfiniteTransition(label = "aurora")
    val drift by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift"
    )

    val particles = remember {
        List(28) {
            Particle(
                x = Random.nextFloat(), y = Random.nextFloat(),
                vx = (Random.nextFloat() - 0.5f) * 0.0018f,
                vy = (Random.nextFloat() - 0.5f) * 0.0018f,
                r = 2.5f + Random.nextFloat() * 3.5f
            )
        }
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { withFrameNanos { frame = it } }
    }

    Canvas(Modifier.fillMaxSize().background(bg)) {
        val w = size.width
        val h = size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = if (pal.light) 0.14f else 0.22f), Color.Transparent),
                center = Offset(w * (0.15f + 0.25f * drift), h * (0.12f + 0.12f * drift)),
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = Offset(w * (0.15f + 0.25f * drift), h * (0.12f + 0.12f * drift))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent2.copy(alpha = if (pal.light) 0.10f else 0.16f), Color.Transparent),
                center = Offset(w * (0.85f - 0.25f * drift), h * (0.82f - 0.14f * drift)),
                radius = w * 0.8f
            ),
            radius = w * 0.8f,
            center = Offset(w * (0.85f - 0.25f * drift), h * (0.82f - 0.14f * drift))
        )

        val step = 46.dp.toPx()
        var gx = 0f
        while (gx < w) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f); gx += step }
        var gy = 0f
        while (gy < h) { drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f); gy += step }

        @Suppress("UNUSED_EXPRESSION") frame
        for (p in particles) {
            p.x += p.vx; p.y += p.vy
            if (p.x < 0f || p.x > 1f) p.vx *= -1f
            if (p.y < 0f || p.y > 1f) p.vy *= -1f
            drawCircle(accent.copy(alpha = 0.5f), p.r, Offset(p.x * w, p.y * h))
        }
        val maxDist = 115.dp.toPx()
        for (i in particles.indices) {
            val p1 = particles[i]
            val x1 = p1.x * w; val y1 = p1.y * h
            for (j in i + 1 until particles.size) {
                val p2 = particles[j]
                val d = hypot(x1 - p2.x * w, y1 - p2.y * h)
                if (d < maxDist) {
                    drawLine(
                        color = accent.copy(alpha = (1f - d / maxDist) * 0.22f),
                        start = Offset(x1, y1), end = Offset(p2.x * w, p2.y * h), strokeWidth = 1.2f
                    )
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
        val cols = listOf(
            Color(0xFFFF2A5F), Color(0xFFFFB800), Color(0xFF00F5D4),
            Color(0xFFC084FC), Color(0xFFCCFF00), Color(0xFF38BDF8)
        )
        repeat(75) { i ->
            val ang = Random.nextFloat() * (Math.PI.toFloat() * 2f)
            val spd = 6f + Random.nextFloat() * 14f
            pieces.add(
                ConfettiPiece(
                    x = 0.5f, y = 0.45f,
                    vx = cos(ang) * spd, vy = sin(ang) * spd - 8f,
                    rot = Random.nextFloat() * 360f, vr = (Random.nextFloat() - 0.5f) * 14f,
                    size = 12f + Random.nextFloat() * 10f,
                    color = cols[i % cols.size], life = 95 + Random.nextInt(40)
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
                    rotate(k.rot, pivot = Offset(cx, cy)) {
                        drawRect(k.color, Offset(cx - k.size / 2, cy - k.size / 3), Size(k.size, k.size * 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun LaserSweepBar(accent: Color, accent2: Color) {
    val inf = rememberInfiniteTransition(label = "laser")
    val pos by inf.animateFloat(
        initialValue = -0.4f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "laserPos"
    )
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
        val startX = size.width * pos
        val segW = size.width * 0.45f
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, accent, accent2, Color.Transparent),
                startX = startX, endX = startX + segW
            ),
            topLeft = Offset(startX, 0f), size = Size(segW, size.height)
        )
    }
}

@Composable
fun LiquidProgressBar(progress: Float, accent: Color, accent2: Color) {
    val inf = rememberInfiniteTransition(label = "liquid")
    val shift by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "shift"
    )
    Box(
        Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    ) {
        Canvas(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().clip(CircleShape)) {
            val w = size.width
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(accent, accent2, accent),
                    startX = -w + (w * 2f * shift), endX = w + (w * 2f * shift)
                )
            )
        }
    }
}

@Composable
fun EqualizerBars(color: Color = Color(0xFF10B981)) {
    val inf = rememberInfiniteTransition(label = "eq")
    val heights = listOf(0, 180, 360, 120).map { delayMs ->
        inf.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, delayMillis = delayMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bar$delayMs"
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(12.dp)) {
        heights.forEach { h ->
            Box(Modifier.width(3.dp).fillMaxHeight(h.value).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

// ───────────────────────── Crash report ─────────────────────────
@Composable
fun CrashScreen(text: String, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFF050508)).padding(16.dp)) {
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

// ───────────────────────── Root ─────────────────────────
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
            HyperKineticBackground(pal)

            Scaffold(
                containerColor = Color.Transparent,
                topBar = { TopCommandDeck(pal) { showSupport = true } },
                bottomBar = {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        tonalElevation = 0.dp
                    ) {
                        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                            listOf("Studio" to "⚡", "Queue" to "🌊", "Vault" to "📼", "Vibe" to "🎨")
                                .forEachIndexed { i, (label, emoji) ->
                                    NavigationBarItem(
                                        selected = tab == i, onClick = { tab = i },
                                        icon = {
                                            if (i == 1 && running > 0) {
                                                BadgedBox(badge = { Badge { Text("$running") } }) { Text(emoji, fontSize = 18.sp) }
                                            } else Text(emoji, fontSize = 18.sp)
                                        },
                                        label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                    )
                                }
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (fadeIn(tween(240)) + scaleIn(initialScale = 0.96f, animationSpec = tween(240)))
                                .togetherWith(fadeOut(tween(160)))
                        },
                        label = "page"
                    ) { currentTab ->
                        when (currentTab) {
                            0 -> GetScreen(shared, consume) { tab = 1 }
                            1 -> QueueScreen()
                            2 -> VaultScreen()
                            else -> SettingsScreen(
                                onSupport = { showSupport = true },
                                onYoutubeLogin = { showYoutubeLogin = true }
                            )
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
fun TopCommandDeck(pal: Pal, onSupport: () -> Unit) {
    val accent = Color(pal.accent)
    val accent2 = Color(pal.accent2)
    val inf = rememberInfiniteTransition(label = "orb")
    val orbY by inf.animateFloat(
        initialValue = -2.5f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbY"
    )

    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    ) {
        LaserSweepBar(accent, accent2)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.offset(y = orbY.dp).size(38.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(accent, accent2))),
                    contentAlignment = Alignment.Center
                ) { Text("🍒", fontSize = 18.sp) }
                Column {
                    Text("cherry studio.", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EqualizerBars()
                        Text("v16.0 • UNLOCKED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    }
                }
            }

            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF2A5F), Color(0xFFFF7E47), Color(0xFFFFB800))))
                    .clickable(onClick = onSupport)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("💖 Support", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }
    }
}

// ───────────────────────── Shared Glass Components ─────────────────────────
@Composable
fun GlassPanel(header: String, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                header.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
            if (badge != null) {
                Text(
                    badge, color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color(0x2210B981)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        content()
    }
}

@Composable
fun PillOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) Brush.horizontalGradient(listOf(c.primary, c.secondary))
                else Brush.horizontalGradient(listOf(c.surfaceVariant, c.surfaceVariant))
            )
            .border(1.dp, if (selected) c.primary else c.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) c.onPrimary else c.onSurfaceVariant,
            fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
        )
    }
}

@Composable
fun GradientButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(c.primary, c.secondary)), alpha = if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

@Composable
fun Thumb(url: String?, size: Int = 64) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(12.dp))
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

// ───────────────────────── Studio (Get) Screen ─────────────────────────
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
        toast(ctx, "🤖 AI preset applied")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // AI Omnibar
        GlassPanel("✦ AI Omnibar", "OFFLINE NLP") {
            OutlinedTextField(
                ai, { ai = it }, Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("e.g. '4k with english subs and sponsorblock'", fontSize = 13.sp) },
                trailingIcon = { TextButton(onClick = { applyAi() }, enabled = ai.isNotBlank()) { Text("Apply ↵", fontWeight = FontWeight.Bold) } },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillOption("4K + CC + SponsorBlock", false, { applyAi("4k video with english subtitles and sponsorblock") })
                PillOption("320k Master MP3", false, { applyAi("extract audio mp3 320k") })
            }
        }

        // 01. Source Link
        GlassPanel("01 // Source Stream", if (Engine.hasCookies) "COOKIES ON" else "ANTI-BOT ON") {
            OutlinedTextField(
                url, { url = it; if (it.isBlank()) meta = null }, Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("https://youtube.com/watch?v=…") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillOption("📋 Paste", false, {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    URL_RE.find(t)?.value?.let { url = it } ?: toast(ctx, "No link on clipboard")
                }, Modifier.weight(1f))
                PillOption(if (busy) "Inspecting…" else "🔍 Inspect", false, { if (!busy && url.isNotBlank()) fetch() }, Modifier.weight(1f))
            }
            if (busy) LiquidProgressBar(0.65f, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
            if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

            meta?.let { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(m.thumb, 64)
                    Column(Modifier.weight(1f)) {
                        Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${m.uploader} • ${fmtDur(m.duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // 02. Master Quality Matrix (ALWAYS VISIBLE)
        GlassPanel("02 // Master Quality Matrix", "UP TO 8K & 320K") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillOption("🎬 Video (MP4)", !audio, { audio = false }, Modifier.weight(1f))
                PillOption("🎧 Audio (MP3)", audio, { audio = true }, Modifier.weight(1f))
            }
            if (!audio) {
                val resOpts = listOf(360, 480, 720, 1080, 1440, 2160, 4320)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resOpts.forEach { h ->
                        PillOption(
                            label = when (h) { 4320 -> "8K"; 2160 -> "4K"; 1440 -> "2K"; else -> "${h}p" },
                            selected = res == h,
                            onClick = { res = h }
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(128, 192, 256, 320).forEach { b ->
                        PillOption("${b}k", bit == b, { bit = b }, Modifier.weight(1f))
                    }
                }
            }
        }

        // 03. Smart Post-Processors (ALWAYS VISIBLE)
        GlassPanel("03 // Smart Post-Processors", "ALL UNLOCKED") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🛡️ SponsorBlock Auto-Cut", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Slices out sponsors, intros & outros", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(sb, { sb = it })
            }
            if (!audio) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("💬 Embed Subtitles", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Single-track subtitle muxing", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(subs, { subs = it })
                }
                if (subs) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("en", "hi", "es", "ja", "ko", "fr", "de", "ar").forEach { code ->
                            PillOption(code.uppercase(), lang == code, { lang = code })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Trim from") }, placeholder = { Text("0:00") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("to") }, placeholder = { Text("1:30") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "Uncapped", "500K" to "500 KB/s", "2M" to "2 MB/s", "5M" to "5 MB/s").forEach { (v, l) ->
                    PillOption(l, limit == v, { limit = v })
                }
            }
        }

        // Always-visible Download button -> switches straight to Queue
        GradientButton("🚀 Launch Studio Download", enabled = url.isNotBlank()) {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) {
                toast(ctx, "Paste a link first")
            } else {
                Engine.enqueueUrl(
                    cleanUrl,
                    Opts(audio, res, bit, subs && !audio, lang, sb, start.trim(), end.trim(), limit),
                    meta
                )
                goQueue()
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ───────────────────────── Queue Screen ─────────────────────────
@Composable
fun QueueScreen() {
    val jobs = Engine.jobs
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live Queue", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { Engine.clearFinished() }) { Text("Clear finished") }
        }
        if (jobs.isEmpty()) {
            GlassPanel("Telemetry") {
                Text("No active streams right now — launch a download from the ⚡ Studio tab.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { j ->
                GlassPanel("Stream • ${j.status.uppercase()}", "${(j.progress * 100).toInt()}%") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(j.thumb, 60)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(j.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val eta = if (j.status == "Downloading" && j.eta > 0) " • ETA ${j.eta}s" else ""
                            Text("${j.status}$eta", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (j.active) {
                        LiquidProgressBar(j.progress, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                    }
                    if (j.note.isNotBlank()) {
                        Text(j.note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (j.active) PillOption("⏹ Cancel", false, { Engine.cancel(j) })
                        if (j.status == "Failed" || j.status == "Cancelled") PillOption("🔄 Retry", true, { Engine.retry(j) })
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
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Vault Archive", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("${h.size} files • ${humanSize(h.sumOf { it.size })}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(h, key = { it.uri }) { e ->
                GlassPanel(if (e.audio) "AUDIO TRACK" else "VIDEO STREAM", humanSize(e.size)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(e.thumb, 60)
                        Column(Modifier.weight(1f)) {
                            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(DateFormat.getDateInstance().format(Date(e.time)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillOption("▶ Open", true, {
                            val u = Uri.parse(e.uri)
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, ctx.contentResolver.getType(u))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) { toast(ctx, "No app to open this") }
                        })
                        PillOption("↗ Share", false, {
                            val u = Uri.parse(e.uri)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(u) ?: "*/*")
                                .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        })
                        PillOption("✕", false, { Engine.deleteEntry(e) })
                    }
                }
            }
        }
    }
}

// ───────────────────────── Vibe (Settings) Screen ─────────────────────────
@Composable
fun SettingsScreen(onSupport: () -> Unit, onYoutubeLogin: () -> Unit) {
    val ctx = LocalContext.current
    var par by remember { mutableIntStateOf(Engine.parallel) }
    var msg by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassPanel("YouTube Sign-In (Bot-Check Fix)", if (Engine.hasCookies) "CONNECTED" else "OPTIONAL") {
            Text(
                if (Engine.hasCookies) "YouTube cookies are active. Downloads will bypass sign-in/bot walls."
                else "If YouTube says 'Sign in to confirm you're not a bot', sign in once here to save session cookies.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillOption("🔑 Sign in to YouTube", true, onYoutubeLogin, Modifier.weight(1f))
                if (Engine.hasCookies) {
                    PillOption("Clear", false, { Engine.clearYoutubeCookies(); toast(ctx, "Cookies cleared") })
                }
            }
        }

        GlassPanel("Engine & Anti-Bot Core") {
            Text(Engine.coreStatus, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            GradientButton("🔄 Update yt-dlp (Nightly)") {
                msg = "Updating to latest Nightly…"
                Engine.updateCore { msg = it }
            }
            if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        GlassPanel("Spatial Studio Themes") {
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
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(p.accent), Color(p.accent2)))),
                            contentAlignment = Alignment.Center
                        ) { if (selected) Text("✓", color = Color(p.fg), fontWeight = FontWeight.ExtraBold) }
                        Spacer(Modifier.height(6.dp))
                        Text(p.name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        GlassPanel("Downloads") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Parallel downloads", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                PillOption("−", false, { if (par > 1) { par--; Engine.setParallel(par) } })
                Text("$par", fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                PillOption("+", false, { if (par < 4) { par++; Engine.setParallel(par) } })
            }
            Text("Files save to Downloads/Cherry.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        GlassPanel("Community & Support") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillOption("💖 Support Creator", true, onSupport, Modifier.weight(1f))
                PillOption("💬 Discord", false, { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL))) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ───────────────────────── In-App YouTube Cookie Login Dialog ─────────────────────────
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
                Text("Sign in to YouTube, then tap Save", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillOption("Save Cookies", true, {
                        val cm = CookieManager.getInstance()
                        cm.flush()
                        val raw = cm.getCookie("https://www.youtube.com") ?: cm.getCookie("https://m.youtube.com") ?: ""
                        if (Engine.saveYoutubeCookies(raw)) {
                            toast(ctx, "✅ YouTube cookies saved!")
                            onDismiss()
                        } else {
                            toast(ctx, "No cookies found yet — finish signing in first")
                        }
                    })
                    PillOption("✕", false, onDismiss)
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
                Text("Cherry Studio is 100% free & unlocked (4K/8K, Subtitles, SponsorBlock & 320k MP3). Pick a tier to fuel future updates!", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("☕ ₹49" to "49", "🍕 ₹99" to "99", "🚀 ₹249" to "249", "💝 Any" to "").forEach { (l, a) ->
                        PillOption(l, selectedAmt == a, { selectedAmt = a }, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { GradientButton("🎉 Open UPI App") { pay(selectedAmt); onDismiss() } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
