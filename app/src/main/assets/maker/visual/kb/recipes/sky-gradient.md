title: Procedural sky with sun, stars and clouds
tags: sky gradient sun moon stars clouds sunset dusk night background atmosphere shader
styles: all
cost: low
engine: godot4
summary: one full-screen shader for a gradient sky, sun glow, twinkling stars and drifting clouds

## Code
```gdshader
shader_type canvas_item;
// Sky: vertical gradient, sun with glow, hashed twinkling stars, two noise cloud bands.
uniform vec4 top : source_color = vec4(0.10, 0.12, 0.23, 1.0);
uniform vec4 horizon : source_color = vec4(0.96, 0.72, 0.38, 1.0);
uniform vec2 sun_pos = vec2(0.7, 0.62);
uniform float sun_size = 0.05;
uniform float stars = 1.0;
uniform float aspect = 0.5625;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
	vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}

void fragment() {
	vec3 c = mix(top.rgb, horizon.rgb, pow(UV.y, 1.6));
	vec2 d = (UV - sun_pos) * vec2(1.0, 1.0 / aspect);
	float r = length(d);
	c += horizon.rgb * 0.6 * exp(-r * 6.0);
	c = mix(c, vec3(1.0, 0.95, 0.8), smoothstep(sun_size, sun_size * 0.8, r));
	vec2 g = UV * vec2(120.0, 120.0 / aspect);
	float s = step(0.995, hash(floor(g))) * (0.6 + 0.4 * sin(TIME * 3.0 + hash(floor(g)) * 40.0));
	c += s * stars * (1.0 - UV.y);
	float cl = noise(vec2(UV.x * 4.0 + TIME * 0.01, UV.y * 18.0)) * smoothstep(0.35, 0.5, UV.y) * smoothstep(0.75, 0.55, UV.y);
	c = mix(c, horizon.rgb * 1.1, cl * 0.35);
	COLOR = vec4(c, 1.0);
}
```
Put it on a `ColorRect` (full rect anchors) in a `CanvasLayer` with `layer = -10`.

## Validation
`visual check`: smooth (no banding warnings visible in the sheet); colourfulness matches the mood.
Dusk: horizon warm, top cool (complementary contrast).

## Adaptation
Pixel: quantize `c = floor(c * 8.0) / 8.0` and dither with `hash(floor(FRAGCOORD.xy))`.
Day: top light blue, horizon pale, `stars = 0`. Storm: low contrast grey, faster clouds.
