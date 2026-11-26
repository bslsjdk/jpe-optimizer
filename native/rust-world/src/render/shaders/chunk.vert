#version 450
// Chunk vertex stage: block-space position, face normal and atlas uv, taken to
// clip space by the per-frame view-projection push constant.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;

layout(push_constant) uniform ChunkPush {
    mat4 viewProj;
} push;

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec2 vUv;

void main() {
    vNormal = aNormal;
    vUv = aUv;
    gl_Position = push.viewProj * vec4(aPos, 1.0);
}
