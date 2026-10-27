title: Post-processing and colour grading for 2D
tags: post processing vignette color grading bloom glow crt scanlines chromatic aberration film grain cinematic look lut
styles: all
cost: medium
engine: godot4
summary: one full-screen shader pass for vignette, grading, grain and optional CRT, plus WorldEnvironment glow

## Code
```gdshader
// res://post.gdshader
shader_type canvas_item;
// Final look: contrast/saturation/tint grading, vignette, film grain, optional scanlines.
uniform sampler2D screen_texture : hint_screen_texture, filter_linear;
uniform float contrast = 1.1;
uniform float saturation = 1.05;
uniform vec4 shadows_tint : source_color = vec4(0.15, 0.18, 0.35, 1.0);
uniform vec4 highlights_tint : source_color = vec4(1.0, 0.92, 0.8, 1.0);
uniform float vignette = 0.35;
uniform float grain = 0.04;
uniform float scanlines = 0.0;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void fragment() {
	vec3 c = texture(screen_texture, SCREEN_UV).rgb;
	float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
	c = mix(vec3(l), c, saturation);
	c = (c - 0.5) * contrast + 0.5;
	c *= mix(shadows_tint.rgb * 1.6, highlights_tint.rgb, smoothstep(0.0, 1.0, l));
	vec2 d = SCREEN_UV - 0.5;
	c *= 1.0 - dot(d, d) * vignette * 2.5;
	c += (hash(SCREEN_UV * 1000.0 + TIME) - 0.5) * grain;
	c *= 1.0 - scanlines * (0.5 + 0.5 * sin(FRAGCOORD.y * 3.14159));
	COLOR = vec4(clamp(c, 0.0, 1.0), 1.0);
}
```
Put it on a full-rect `ColorRect` in a `CanvasLayer` above the world and below the HUD
(`layer = 50`; HUD on `layer = 100`) with `mouse_filter = IGNORE`.

## Glow / bloom
Add a `WorldEnvironment` with `background_mode = BG_CANVAS`, `glow_enabled = true`, `glow_hdr_threshold`
≈ 0.9 and make glowing things brighter than 1 (modulate `Color(2, 2, 2)`): only they bloom.

## Validation
`visual check --against` a render without the pass: mean difference 5–20 is a grade; more is heavy.
Vignette must not darken the HUD (it's above). Costs one full-screen pass.

## Adaptation
Retro: scanlines 0.3, slight saturation boost, grain 0. Cinematic: teal shadows / orange highlights,
vignette 0.5. Cozy: warm highlights, low contrast 1.0, no grain.
