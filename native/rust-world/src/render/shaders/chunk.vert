#version 450
// Chunk vertex stage: block-space position, quad-local uv in tiles, and a
// packed tile/face word, taken to clip space by the per-frame view-projection
// push constant.
//
// The uv is in tiles, not atlas coordinates: greedy meshing emits one quad for
// a whole run of identical faces, so the fragment stage has to wrap it. The
// normal is not an attribute -- there are six possible values and the face
// index is already in the packed word.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in uint aMeta;

layout(push_constant) uniform ChunkPush {
    mat4 viewProj;
} push;

layout(location = 0) out vec2 vUv;
layout(location = 1) flat out uint vTile;
layout(location = 2) flat out uint vFace;

void main() {
    vUv = aUv;
    vTile = aMeta & 0xFFFFu;
    vFace = aMeta >> 16;
    gl_Position = push.viewProj * vec4(aPos, 1.0);
}
