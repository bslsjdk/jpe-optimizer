#version 450
// Chunk vertex stage: positions are block-local (0..1 per block). The mesh is
// scaled into clip space so an offscreen readback reliably shows coverage.
layout(location = 0) in vec3 aPos;
void main() {
    gl_Position = vec4(aPos.x * 0.5, aPos.y * 0.5, 0.0, 1.0);
}
