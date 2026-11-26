# Rust World Engine — native renderer tests on software GPUs (CI notes)

Scope: why the three native backends are testable without a GPU, and what the CI
environment has to provide for that to be true. This is a build/verification
note, not a feature stage.

## What the crate actually loads

Every backend resolves its own native library by name at runtime and never
statically links a GPU stack:

| Backend | EGL/display | Client API library | Vulkan |
| --- | --- | --- | --- |
| `vulkan` | — | — | `libvulkan.so.1`, then `libvulkan.so` |
| `gl` | `libEGL.so.1`, then `libEGL.so` | `libGL.so.1`, then `libGL.so` | — |
| `gles` | `libEGL.so.1`, then `libEGL.so` | `libGLESv2.so.2`, then `libGLESv2.so` | — |

The unversioned names are tried second because distributions do not agree on
what exists at runtime: the SONAME is always installed by the runtime package,
the bare linker name only by the `-dev` package. `render::egl::dlopen_first`
implements the fallback for EGL and GL/GLES, and `open_vulkan` in
`render::vulkan` carries its own two-name loop.

Nothing here picks a *different* driver. Software rendering on CI comes from
Mesa (`lavapipe` for Vulkan, `llvmpipe` for GL/GLES); the crate just needs the
loader packages present:

```
mesa-vulkan-drivers libegl-mesa0 libgl1-mesa-dri libegl1 libgl1 libgles2 libglvnd0 libvulkan1
```

`libegl1` is the one that is easy to miss: `libegl-mesa0` is the Mesa EGL
*driver*, but the `libEGL.so.1` SONAME itself belongs to `libegl1`.

## Vulkan ICD discovery

`vkCreateInstance` returning `VK_ERROR_INCOMPATIBLE_DRIVER` means the loader
found no usable ICD — it does not distinguish "no driver installed" from "the
manifest path I was handed does not exist". A workflow that hard-codes

```
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json
```

therefore turns a packaging rename into a confusing driver error. `ci/vulkan-icd.sh`
replaces that hard-coded path:

1. search `/usr/share/vulkan/icd.d`, `/etc/vulkan/icd.d` and the per-user
   equivalent for any `lvp_icd*.json`;
2. if none exists but the driver library does, write an equivalent manifest to
   `/tmp/jpe-vulkan/icd.d/lvp_icd.json`;
3. print the chosen path on stdout (diagnostics on stderr) and, inside Actions,
   append `VK_DRIVER_FILES`/`VK_ICD_FILENAMES` to `$GITHUB_ENV` for later steps.

`VK_DRIVER_FILES` is the current variable and `VK_ICD_FILENAMES` is set to the
same value for older loaders. If no lavapipe driver exists at all, stdout is
empty and the workflow leaves the loader untouched, so the test reports the real
reason instead of a fabricated path.

Step 2 was verified end-to-end inside the development sandbox: with the packaged
manifest hidden, the generated manifest still produced a working device —
`vulkan device: llvmpipe (LLVM 15.0.7, 256 bits)` and
`vulkan shading: 8626 lit pixels, 4 distinct colours`.

## Three jobs, three different guarantees

- **Build and test (no GPU required)** — no Mesa packages are installed at all.
  Every backend test must *skip*, never pass vacuously-by-accident:
  `require_or_skip` turns a skip into a panic when
  `JPE_REQUIRE_RENDER_BACKENDS` is set, and `ffi::tests::renderer_over_c_abi_draws_and_reads_pixels`
  returns early when no backend could be created. That early return is the fix
  for the first failure seen in CI, where the loop skipped all three backends and
  the "auto selection must work" assertion then ran with nothing available.
- **Native render backends on software Mesa** — `JPE_REQUIRE_RENDER_BACKENDS=1`,
  so a missing driver is a build failure. This is the job that proves Vulkan, GL
  and GLES each created a real device/context and drew a real frame.
- **Java 25 FFM bridge harness** — same requirement, reached through
  `java/run_harness.sh`, i.e. the renderer is exercised over the C ABI from a JVM.

## History of the breakage (so it is not repeated)

The workflow was green through `84c141b`/`0f28096` (no GPU code) and red from
`8e613ae` onward. The `gpu-backends` log for `c968d16` said, verbatim:

```
render backend required but unavailable: egl: libEGL.so.1 not loadable
render backend required but unavailable: vulkan: incompatible driver
required backends unavailable: ["vulkan", "gl", "gles"]
```

and `java-bridge` failed with `render backend required but none available`.
The runner image is `ubuntu-26.04` (Mesa 26.0.8-1ubuntu0.3), which the original
apt list did not fully cover. The `gpu-backends` job now carries an advisory
(`continue-on-error`) diagnostics step printing the installed packages, the `.so`
files and ICD manifests each provides, the ICD search directories, and any
`VK_*`/`GALLIUM_*`/`LIBGL_*` variables preset by the image, plus a
`VK_LOADER_DEBUG=all` run of the Vulkan tests, so the next such break reads as a
missing file rather than an error code.

## Not claimed

- CI runs on **software** drivers. It shows the API usage is correct and
  deterministic; it is not evidence about real GPU performance or vendor driver
  behaviour.
- Whether the loader-name fallbacks are *needed* on any specific distribution was
  not verified package-by-package; only that `libegl1` was genuinely absent on the
  Actions runner (`libEGL.so.1 not loadable`).
- `rustfmt` and `clippy` steps are still `continue-on-error`, because the sandbox
  used for this crate cannot install those components.
- No presentation path exists yet: every frame in CI is an offscreen buffer, and
  Minecraft's own window/context is still untouched.
