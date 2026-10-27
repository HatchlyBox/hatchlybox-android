title: AGSL shaders in Compose: living backgrounds, gradients and effects
tags: compose agsl runtime shader shaderbrush gradient mesh aurora background animated noise grain effect brand identity kotlin app ui android 13
styles: ui flat painted
cost: low
engine: compose
summary: Android 13+ RuntimeShader as a Compose ShaderBrush: animated aurora or mesh-gradient backgrounds and grain, with a plain gradient fallback on older phones

## Method
Shaders give an app a signature look no stock component has: a slowly moving aurora behind a login,
a mesh gradient in the brand colours, film grain over illustrations, a glow under the main action.
- `RuntimeShader(agslSource)` (API 33+) wrapped in `ShaderBrush` and drawn in `drawBehind` or
  `drawWithCache`. Pass size, time and brand colours as uniforms (`layout(color) uniform half4`
  receives colours correctly in the display's colour space).
- Animate time with `rememberInfiniteTransition`; reading it in the draw phase only redraws (no
  recomposition). Keep the shader small; full-screen and animated costs battery: slow motion, pause
  it off-screen.
- Always guard with `Build.VERSION.SDK_INT >= 33` and fall back to a `Brush` gradient.
- Gradient text: `TextStyle(brush = …)` takes the same brush.

## Code
```kotlin
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

private const val AURORA = """
uniform float2 size;
uniform float time;
layout(color) uniform half4 colorA;
layout(color) uniform half4 colorB;
layout(color) uniform half4 colorC;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

half4 main(float2 p) {
    float2 uv = p / size;
    float w1 = sin(uv.x * 5.0 + time * 0.5) * 0.5 + sin(uv.y * 3.0 - time * 0.3) * 0.5;
    float w2 = sin((uv.x + uv.y) * 4.0 - time * 0.4);
    half4 c = mix(colorA, colorB, smoothstep(-0.8, 0.8, w1 + uv.y - 0.4));
    c = mix(c, colorC, smoothstep(0.4, 1.0, w2) * 0.6 * (1.0 - uv.y));
    float grain = (hash(p + time) - 0.5) * 0.04;
    return half4(c.rgb + grain, 1.0);
}
"""

// A slowly moving aurora in three brand colours, with fine grain; a plain gradient before Android 13.
@Composable
fun AuroraBackground(a: Color, b: Color, c: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val time by rememberInfiniteTransition(label = "aurora")
            .animateFloat(0f, 120f, infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "time")
        val shader = remember { RuntimeShader(AURORA) }
        Box(modifier.drawWithCache {
            val brush = ShaderBrush(shader)
            onDrawBehind {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("time", time)
                shader.setColorUniform("colorA", a.toArgb())
                shader.setColorUniform("colorB", b.toArgb())
                shader.setColorUniform("colorC", c.toArgb())
                drawRect(brush)
            }
        }) { content() }
    } else {
        Box(modifier.background(Brush.verticalGradient(listOf(a, b))) ) { content() }
    }
}
```

## Validation
On an Android 13+ phone: a smooth, slowly drifting background in the brand colours with no banding
(grain hides it), 60 fps while scrolling content above it. Older phone: the gradient fallback.
`visual check` on a peek screenshot: text over it keeps contrast (add a scrim if not).

## Adaptation
Meditation or music: slower time, softer colours, bigger waves. Finance or productivity: static (no
time), as a header only. Games' menus in Compose: add sparkles (hash threshold) and a vignette.
Onboarding: shift colours per page by animating the colour uniforms.
