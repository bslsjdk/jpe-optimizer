package dev.jpe.optimizer.world;

/**
 * Pure, Minecraft-independent view-projection maths for the Java world host.
 *
 * <p>The Rust world engine consumes a single column-major 4x4 clip matrix per frame
 * ({@code jpe_pipeline_frame}). Two things about it are fixed by the Rust side and
 * must be matched here, so this class reproduces the exact conventions of
 * {@code native/rust-world/src/render/mod.rs}:
 *
 * <ul>
 *   <li><b>Storage is column-major</b> ({@code out[col * 4 + row]}), identical to
 *       {@code mat4_mul} / {@code ortho_look_at} in Rust.</li>
 *   <li><b>Depth maps to {@code 0..=1}</b> (Vulkan), not the OpenGL {@code -1..=1}.
 *       {@code frustum_from_view_proj} extracts the near plane from row&nbsp;2 alone
 *       precisely because of this, so feeding a GL-depth matrix would cull wrong.</li>
 * </ul>
 *
 * <p>Keeping the maths here (rather than calling into the Rust camera helper) lets a
 * live Minecraft host build the matrix from its own camera every frame, and lets it be
 * unit-tested without a GPU or a running game.
 */
public final class WorldCamera {

    private WorldCamera() {
    }

    /**
     * Build a column-major perspective view-projection matrix looking from
     * {@code (eyeX, eyeY, eyeZ)} along the Minecraft-style view angles.
     *
     * <p>Angles match {@code Entity.getViewXRot}/{@code getViewYRot}: {@code pitch} is
     * {@code xRot} (positive looks down) and {@code yaw} is {@code yRot} (positive looks
     * left), both in degrees. Up is {@code +Y}. The matrix uses a Vulkan {@code 0..=1}
     * depth range and a right-handed view where the camera looks down its own
     * {@code -Z}.
     *
     * @param fovYDeg vertical field of view in degrees (must be in {@code (0, 180)})
     * @param aspect  width / height (must be positive)
     * @param near    near plane distance (must be positive)
     * @param far     far plane distance (must be greater than {@code near})
     * @return 16 floats, column-major, ready for {@code pipelineFrame}
     */
    public static float[] perspectiveViewProj(double eyeX, double eyeY, double eyeZ,
                                              float yawDeg, float pitchDeg,
                                              float fovYDeg, float aspect,
                                              float near, float far) {
        if (fovYDeg <= 0.0f || fovYDeg >= 180.0f) {
            throw new IllegalArgumentException("fovYDeg must be in (0, 180): " + fovYDeg);
        }
        if (!(aspect > 0.0f) || !(near > 0.0f) || !(far > near)) {
            throw new IllegalArgumentException(
                    "need aspect>0, near>0, far>near (aspect=" + aspect
                            + " near=" + near + " far=" + far + ")");
        }

        // Minecraft's view direction from the two look angles (degrees).
        float pitch = (float) Math.toRadians(pitchDeg);
        float yaw = (float) Math.toRadians(yawDeg);
        float cp = (float) Math.cos(pitch);
        float fx = -(float) Math.sin(yaw) * cp;
        float fy = -(float) Math.sin(pitch);
        float fz = (float) Math.cos(yaw) * cp;

        // target = eye + forward; replicate Rust ortho_look_at's basis exactly.
        float[] forward = normalize(new float[]{fx, fy, fz});
        float[] side = normalize(cross(forward, new float[]{0.0f, 1.0f, 0.0f}));
        float[] up = cross(side, forward);

        float[] eye = {(float) eyeX, (float) eyeY, (float) eyeZ};

        // View matrix (column-major), same arrangement as render/mod.rs ortho_look_at.
        float[] view = new float[]{
                side[0], up[0], -forward[0], 0.0f, //
                side[1], up[1], -forward[1], 0.0f, //
                side[2], up[2], -forward[2], 0.0f, //
                -dot(side, eye), -dot(up, eye), dot(forward, eye), 1.0f,
        };

        // Perspective projection, right-handed (looks down -Z), depth mapped to 0..1.
        //   f = 1 / tan(fovY / 2)
        //   proj[col*4 + row]:
        //     [0]=f/aspect  [5]=f  [10]=far/(near-far)  [11]=-1
        //     [14]=far*near/(near-far)
        // Derived so that view-space z=-near -> ndcZ=0 and z=-far -> ndcZ=1.
        float halfFov = (float) Math.toRadians(fovYDeg) * 0.5f;
        float f = (float) (1.0 / Math.tan(halfFov));
        float nf = near - far;
        float[] proj = new float[]{
                f / aspect, 0.0f, 0.0f, 0.0f, //
                0.0f, f, 0.0f, 0.0f, //
                0.0f, 0.0f, far / nf, -1.0f, //
                0.0f, 0.0f, far * near / nf, 0.0f,
        };

        return mat4Mul(proj, view);
    }

    /** Column-major 4x4 multiply mirroring Rust {@code mat4_mul}. */
    static float[] mat4Mul(float[] a, float[] b) {
        float[] out = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
        return out;
    }

    /** Transform a world-space point by a column-major matrix into clip space. */
    static float[] transformClip(float[] m, float x, float y, float z) {
        return new float[]{
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
                m[3] * x + m[7] * y + m[11] * z + m[15],
        };
    }

    private static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len <= 1e-6f) {
            throw new IllegalArgumentException("degenerate camera basis (forward/up parallel)");
        }
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
