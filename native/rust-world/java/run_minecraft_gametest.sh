#!/usr/bin/env bash
# Build libjpe_world and run the Minecraft GameTest suite against it.
#
#   native/rust-world/java/run_minecraft_gametest.sh
#
# What this proves that the standalone harness cannot: the Rust world engine runs
# inside a live Minecraft 26.3 server, taking block data out of real generated
# chunks (MinecraftChunkReader), then meshing, uploading, culling and drawing them
# through a native GPU backend without any C++ involvement.
#
# Environment:
#   JAVA_HOME       JDK 25 home (Gradle picks the toolchain itself, but the JVM
#                   that runs the game must be 25 for the FFM API).
#   VK_DRIVER_FILES Vulkan ICD manifest for software rendering (lavapipe), e.g.
#                   /usr/share/vulkan/icd.d/lvp_icd.x86_64.json
#   JPE_VULKAN_ICD  same manifest, used when VK_DRIVER_FILES is unset.
#   GRADLE          gradle executable (default: gradle on PATH).
#   GRADLE_OFFLINE  set to 1 to add --offline.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
crate="$(cd "$here/.." && pwd)"
repo="$(cd "$crate/../.." && pwd)"

if [[ ! -x "$crate/target/release/libjpe_world.so" ]]; then
  echo "[gametest] building libjpe_world (release)"
  (cd "$crate" && cargo build --release)
fi

runtime="$repo/native/rust-runtime/target/release/libjpe_runtime.so"
extra=()
if [[ -f "$runtime" ]]; then
  # The pre-existing worldgen parity tests share this Gradle run; only pass their
  # library when it is actually built, so this script never fakes a green suite.
  extra+=("-Djpe.nativeLibraryPath=$runtime" "-Djpe.nativePerlinEnabled=true"
          "-Djpe.perlinVectorEnabled=true" "-Djpe.structureIndexEnabled=true")
else
  echo "[gametest] note: $runtime is missing, the worldgen parity tests will fail" >&2
fi

icd="${VK_DRIVER_FILES:-${VK_ICD_FILENAMES:-${JPE_VULKAN_ICD:-}}}"
if [[ -z "$icd" && -f /usr/share/vulkan/icd.d/lvp_icd.x86_64.json ]]; then
  icd=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json
fi

gradle_flags=()
[[ "${GRADLE_OFFLINE:-0}" == "1" ]] && gradle_flags+=(--offline)

echo "[gametest] world library: $crate/target/release/libjpe_world.so"
echo "[gametest] vulkan icd  : ${icd:-<none, backend selection may fail>}"

env \
  ${icd:+VK_DRIVER_FILES="$icd"} \
  ${icd:+VK_ICD_FILENAMES="$icd"} \
  LIBGL_ALWAYS_SOFTWARE=1 \
  GALLIUM_DRIVER=llvmpipe \
  XDG_RUNTIME_DIR=/tmp \
  JAVA_TOOL_OPTIONS="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Djpe.worldLibrary=$crate/target/release/libjpe_world.so -Djpe.worldRequired=true -Djpe.structureIndexMetrics=true -Djpe.structureIndexEnabled=true -Djpe.paletteHashEnabled=true ${extra[*]-}" \
  "${GRADLE:-gradle}" runGameTestServer --console=plain "${gradle_flags[@]}"

echo "[gametest] summary line from the server log:"
grep -h "JPE_RUST_WORLD_GAME_TEST" "$repo/run/logs/latest.log" | tail -n 3
