#version 450
// Chunk vertex stage: a section-local corner and quad-local uv packed into one
// word, a tile/face word, and a per-draw section origin from the push constant.
//
// Both inputs are integer words; the position is five bits per axis because a
// section is 16 blocks (corners reach 16) and the origin is applied here.
layout(location = 0) in uint aPosUv;
layout(location = 1) in uint aMeta;

layout(push_constant) uniform ChunkPush {
    mat4 viewProj;
    vec4 origin;
} push;

layout(location = 0) out vec2 vUv;
layout(location = 1) flat out uint vTile;
layout(location = 2) flat out uint vFace;

void main() {
    vec3 local = vec3(float(aPosUv & 31u), float((aPosUv >> 5) & 31u), float((aPosUv >> 10) & 31u));
    vUv = vec2(float((aPosUv >> 15) & 31u), float((aPosUv >> 20) & 31u));
    vTile = aMeta & 0xFFFFu;
    vFace = aMeta >> 16;
    gl_Position = push.viewProj * vec4(local + push.origin.xyz, 1.0);
}
