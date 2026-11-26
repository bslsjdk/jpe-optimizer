#version 450
// Chunk fragment stage: samples the block atlas and applies one directional
// light term, so faces with different normals are clearly distinct.
layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec2 vUv;

layout(set = 0, binding = 0) uniform sampler2D uAtlas;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 light = vec3(0.40824829, 0.81649658, 0.40824829);
    float shade = 0.5 + 0.5 * max(dot(normalize(vNormal), light), 0.0);
    fragColor = vec4(texture(uAtlas, vUv).rgb * shade, 1.0);
}
