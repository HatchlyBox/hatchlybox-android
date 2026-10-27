package skill.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

private const val RADIUS = 44f
private const val ROUND_SECONDS = 30f

// Tap the ball before time runs out; it speeds up with every hit.
@Composable
fun BallGame(modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var ball by remember { mutableStateOf(Offset(200f, 300f)) }
    var velocity by remember { mutableStateOf(Offset(380f, 260f)) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(0) }
    var timeLeft by remember { mutableFloatStateOf(ROUND_SECONDS) }
    var running by remember { mutableStateOf(false) }

    // The game loop: one step per frame, moved by the real time since the last frame.
    LaunchedEffect(running) {
        var last = 0L
        while (running) {
            withFrameNanos { now ->
                if (last != 0L && size != IntSize.Zero) {
                    val dt = (now - last) / 1_000_000_000f
                    var v = velocity
                    val next = ball + v * dt
                    val maxX = max(RADIUS, size.width - RADIUS)
                    val maxY = max(RADIUS, size.height - RADIUS)
                    if (next.x < RADIUS || next.x > maxX) v = v.copy(x = -v.x)
                    if (next.y < RADIUS || next.y > maxY) v = v.copy(y = -v.y)
                    ball = Offset(next.x.coerceIn(RADIUS, maxX), next.y.coerceIn(RADIUS, maxY))
                    velocity = v
                    timeLeft -= dt
                    if (timeLeft <= 0f) {
                        timeLeft = 0f
                        best = max(best, score)
                        running = false
                    }
                }
                last = now
            }
        }
    }

    val ballColor = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Score $score", style = MaterialTheme.typography.titleLarge)
            Text("${timeLeft.toInt()} s", style = MaterialTheme.typography.titleLarge)
            Text("Best $best", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { size = it }
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            if (running && (tap - ball).getDistance() < RADIUS * 1.4f) {
                                score++
                                velocity = Offset(velocity.x * 1.08f, velocity.y * 1.08f)
                                ball = Offset(Random.nextFloat() * size.width, Random.nextFloat() * size.height)
                            }
                        }
                    },
            ) {
                drawCircle(color = ballColor, radius = RADIUS, center = ball)
            }
            if (!running) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (timeLeft <= 0f) "Time's up: $score points" else "Tap the ball as often as you can", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = {
                        score = 0
                        timeLeft = ROUND_SECONDS
                        velocity = Offset(380f, 260f)
                        running = true
                    }) { Text(if (timeLeft <= 0f) "Play again" else "Start") }
                }
            }
        }
    }
}
