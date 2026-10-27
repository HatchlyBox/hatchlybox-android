// Hello World: the app Hatchlybox comes with, ready to install. Neon Run, a three-lane arcade game with an original
// chiptune and a saved best score, to show what a described app becomes.
package com.hatchlybox.hello_world

import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NeonApp() }
    }
}

private val Cyan = Color(0xFF63FFF2)
private val Pink = Color(0xFFFF66CF)
private val Ink = Color(0xFF090D1C)

@Composable
private fun NeonApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neon_runner", 0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    var music by remember { mutableStateOf(prefs.getBoolean("music", true)) }
    var screen by rememberSaveable { mutableStateOf("title") }
    var lane by rememberSaveable { mutableIntStateOf(1) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var distance by rememberSaveable { mutableFloatStateOf(-0.3f) }
    var obstacle by rememberSaveable { mutableIntStateOf(0) }
    var round by rememberSaveable { mutableIntStateOf(0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    var clock by remember { mutableFloatStateOf(0f) }
    val owner = context as ComponentActivity
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { active = false; if (screen == "game") paused = true }
            if (event == Lifecycle.Event.ON_START) active = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(music, active) {
        val player = if (music && active) MediaPlayer.create(context, R.raw.pixel_dawn)?.apply {
            isLooping = true
            setVolume(0.22f, 0.22f)
            start()
        } else null
        onDispose { player?.release() }
    }
    fun start() { lane = 1; score = 0; distance = -0.3f; round = 0; obstacle = 0; paused = false; screen = "game" }
    BackHandler(screen != "title") { if (screen == "game" && !paused) paused = true else screen = "title" }
    LaunchedEffect(screen, paused, active) {
        var last = 0L
        while (active) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now-last)/1_000_000_000f).coerceAtMost(0.05f)
                last = now
                clock += dt
                if (screen == "game" && !paused) {
                    distance += dt * (0.40f + score.coerceAtMost(30) * 0.012f)
                    if (distance in 0.73f..0.88f && lane == obstacle) {
                        screen = "over"
                        if (score > best) { best = score; prefs.edit().putInt("best", best).apply() }
                    } else if (distance > 1.1f) {
                        score++
                        if (score > best) { best = score; prefs.edit().putInt("best", best).apply() }
                        round++
                        obstacle = (round * 7 + round / 3) % 3
                        distance = -0.12f
                    }
                }
            }
        }
    }
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Cyan) else lightColorScheme(primary = Color(0xFF006B66))
    MaterialTheme(colorScheme = scheme) {
        Scaffold { insets ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
                val wide = maxWidth > 600.dp
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("HELLO WORLD / ARCADE", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { music = !music; prefs.edit().putBoolean("music", music).apply() }) { Text(if (music) "Music: ON" else "Music: OFF") }
                    }
                    if (screen == "title") {
                        if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            TitleArt(clock, Modifier.weight(1f).fillMaxHeight())
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Intro(best, ::start) }
                        } else {
                            TitleArt(clock, Modifier.weight(1f).fillMaxWidth())
                            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Intro(best, ::start) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SCORE ${score.toString().padStart(3, '0')}", fontFamily = FontFamily.Monospace)
                            Text("BEST $best", fontFamily = FontFamily.Monospace)
                            if (screen == "game") TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().background(Ink), contentAlignment = Alignment.Center) {
                            Track(clock, lane, obstacle, distance, Modifier.fillMaxSize().semantics {
                                contentDescription = "Three-lane track. Ship in lane ${lane + 1}. Obstacle in lane ${obstacle + 1}."
                            })
                            if (paused || screen == "over") Surface(Modifier.padding(16.dp).widthIn(max = 360.dp), shape = MaterialTheme.shapes.large) {
                                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (screen == "over") "SIGNAL LOST" else "PAUSED", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                                    Text(if (screen == "over") "$score gates cleared • best $best" else "Your run is waiting.")
                                    Button(onClick = { if (screen == "over") start() else paused = false }) { Text(if (screen == "over") "Try again" else "Resume run") }
                                    TextButton(onClick = { screen = "title" }) { Text("Back to title") }
                                }
                            }
                        }
                        if (screen == "game" && !paused) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { lane = (lane - 1).coerceAtLeast(0) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("◀  Left") }
                            Button(onClick = { lane = (lane + 1).coerceAtMost(2) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Right  ▶") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(best: Int, start: () -> Unit) {
    Text("NEON RUN / VOL. 01", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
    Text("Outrun the static.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Switch lanes with Left and Right. Dodge the pink blocks. Every gate you clear makes the city faster.", style = MaterialTheme.typography.bodyMedium)
    Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("START RUN  ▶", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
    Text("BEST $best  ·  OFFLINE  ·  ORIGINAL CHIPTUNE", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
}

private val glyphs = mapOf(
    'H' to "10001/10001/10001/11111/10001/10001/10001", 'A' to "01110/10001/10001/11111/10001/10001/10001",
    'T' to "11111/00100/00100/00100/00100/00100/00100", 'C' to "01110/10001/10000/10000/10000/10001/01110",
    'L' to "10000/10000/10000/10000/10000/10000/11111", 'Y' to "10001/10001/01010/00100/00100/00100/00100",
    'B' to "11110/10001/10001/11110/10001/10001/11110", 'O' to "01110/10001/10001/10001/10001/10001/01110",
    'X' to "10001/10001/01010/00100/01010/10001/10001"
)

@Composable
private fun TitleArt(time: Float, modifier: Modifier) {
    Box(modifier.background(Ink).semantics { contentDescription = "Hatchlybox. Animated neon pixel title" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0..45) {
                val x = ((i * 73f) % size.width)
                val y = ((i * 47f + time * (8 + i % 5)) % size.height)
                drawRect(Cyan.copy(alpha = 0.15f + (i % 3) * 0.15f), Offset(x,y), Size(3f,3f))
            }
            val horizon = size.height * .68f
            for (i in 0..10) {
                val y = horizon + ((i / 10f + time * .08f) % 1f) * (size.height-horizon)
                drawLine(Pink.copy(alpha=.25f), Offset(0f,y), Offset(size.width,y), 1f)
                drawLine(Cyan.copy(alpha=.18f), Offset(size.width/2,horizon), Offset(size.width*(i-5)/3f,size.height),1f)
            }
            val lines = listOf("HATCHLY", "BOX")
            val p = minOf(size.width / 41f, size.height / 25f)
            lines.forEachIndexed { line, word ->
                val startX = (size.width - (word.length * 6 - 1)*p)/2
                val startY = size.height*.24f + line*10*p
                word.forEachIndexed { index, char ->
                    glyphs[char]?.split('/')?.forEachIndexed { y, row -> row.forEachIndexed { x, dot ->
                        if (dot == '1') {
                            val pos = Offset(startX+(index*6+x)*p, startY+y*p+sin(time*2)*2)
                            drawRect(Pink.copy(alpha=.65f),pos+Offset(p*.5f,p*.5f),Size(p*.88f,p*.88f))
                            drawRect(if (line == 0) Cyan else Color(0xFFE0FF85),pos,Size(p*.88f,p*.88f))
                        }
                    } }
                }
            }
            for (y in 0..(size.height/5).toInt()) drawLine(Ink.copy(alpha=.2f),Offset(0f,y*5f),Offset(size.width,y*5f),1f)
        }
        Text("A PIXEL MOTION PICTURE", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
    }
}

@Composable
private fun Track(time: Float, lane: Int, obstacle: Int, progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val laneWidth = size.width/3
        for (i in 1..2) drawLine(Cyan.copy(alpha=.25f),Offset(i*laneWidth,0f),Offset(i*laneWidth,size.height),2f)
        for (i in 0..12) {
            val y = ((i/12f + time*.3f)%1f)*size.height
            drawLine(Cyan.copy(alpha=.08f),Offset(0f,y),Offset(size.width,y),1f)
        }
        val block = minOf(laneWidth*.5f, size.height*.13f)
        val x = laneWidth*(obstacle+.5f)
        val y = progress*size.height
        drawRect(Pink.copy(alpha=.15f),Offset(x-block*.7f,y-block*.7f),Size(block*1.4f,block*1.4f))
        drawRect(Pink,Offset(x-block/2,y-block/2),Size(block,block))
        drawRect(Ink,Offset(x-block*.25f,y-block*.25f),Size(block*.5f,block*.5f))
        val pixel = minOf(laneWidth/10, size.height/30)
        val ship = listOf("00100","01110","11111","10101")
        ship.forEachIndexed { row, pattern -> pattern.forEachIndexed { col, dot ->
            if (dot == '1') drawRect(Cyan,Offset(laneWidth*(lane+.5f)+(col-2.5f)*pixel,size.height*.8f+(row-2)*pixel),Size(pixel*.9f,pixel*.9f))
        } }
        drawRect(if ((time*10).toInt()%2==0) Pink else Color(0xFFE0FF85),Offset(laneWidth*(lane+.5f)-pixel/2,size.height*.8f+2*pixel),Size(pixel,pixel*2))
    }
}
