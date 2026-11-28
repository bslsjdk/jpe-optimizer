#version 450
// Chunk fragment stage: wraps the quad-local uv into the block atlas and
// applies one directional light term, so faces with different orientations are
// clearly distinct.
layout(location = 0) in vec2 vUv;
layout(location = 1) flat in uint vTile;
layout(location = 2) flat in uint vFace;

layout(set = 0, binding = 0) uniform sampler2D uAtlas;

layout(location = 0) out vec4 fragColor;

const float TILES = 8.0;
const float HALF_TEXEL = 0.5 / 256.0;
// 0.5 + 0.5 * max(dot(n, light), 0) for n = +X, -X, +Y, -Y, +Z, -Z.
const float SHADE[6] = float[6](0.704, 0.5, 0.908, 0.5, 0.704, 0.5);

void main() {
    vec2 cell = vec2(float(vTile & 7u), float(vTile >> 3u));
    vec2 span = vec2(1.0 / TILES) - 2.0 * HALF_TEXEL;
    vec2 uv = cell / TILES + HALF_TEXEL + fract(vUv) * span;
    fragColor = vec4(texture(uAtlas, uv).rgb * SHADE[int(vFace)], 1.0);
}
