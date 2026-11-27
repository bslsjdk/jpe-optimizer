#!/usr/bin/env bash
# Builds the native runtime if needed, compiles the Minecraft-free density
# classes, and starts the standalone density-graph server.
#
#   scripts/run-density-server.sh [--port 8787] [--bind 127.0.0.1] [--library PATH]
#
# Requires JDK 22+ (java.lang.foreign) and a Rust toolchain for the first build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
if ! command -v "$JAVA" >/dev/null 2>&1; then
  echo "java not found; set JAVA_HOME to a JDK 22+ installation" >&2
  exit 1
fi

MAJOR="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [ -z "$MAJOR" ] || [ "$MAJOR" -lt 22 ]; then
  echo "JDK 22+ is required (found: ${MAJOR:-unknown}); java.lang.foreign must be final" >&2
  exit 1
fi

LIBRARY="native/rust-runtime/target/release/libjpe_runtime.so"
if [ ! -f "$LIBRARY" ]; then
  echo "building the native runtime..."
  cargo build --release --manifest-path native/rust-runtime/Cargo.toml
fi

OUT="build/density-server"
rm -rf "$OUT"
mkdir -p "$OUT"
cat > "$OUT/sources.txt" <<'SOURCES'
src/main/java/dev/jpe/optimizer/runtime/DensityGraphIr.java
src/main/java/dev/jpe/optimizer/runtime/DensityGraphProgram.java
src/main/java/dev/jpe/optimizer/runtime/NativeDensityProgramBridge.java
src/main/java/dev/jpe/optimizer/runtime/DensityGraphCapture.java
src/main/java/dev/jpe/optimizer/runtime/DensityGraphPayloadCapture.java
src/main/java/dev/jpe/optimizer/runtime/SamplerGraphProgramAdapter.java
src/main/java/dev/jpe/optimizer/runtime/CompiledSamplerGraphTrace.java
src/main/java/dev/jpe/optimizer/runtime/CompiledSamplerGraphIr.java
src/main/java/dev/jpe/optimizer/runtime/NativeFinalDensityPlan.java
src/main/java/dev/jpe/optimizer/server/Json.java
src/main/java/dev/jpe/optimizer/server/Scenarios.java
src/main/java/dev/jpe/optimizer/server/JavaDensityReference.java
src/main/java/dev/jpe/optimizer/server/Dashboard.java
src/main/java/dev/jpe/optimizer/server/DensityGraphServer.java
SOURCES

"$JAVAC" -d "$OUT" --release 22 @"$OUT/sources.txt"
exec "$JAVA" -cp "$OUT" dev.jpe.optimizer.server.DensityGraphServer "$@"
