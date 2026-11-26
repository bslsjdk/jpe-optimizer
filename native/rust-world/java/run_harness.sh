#!/usr/bin/env bash
# Build the Rust world engine cdylib and run the Java 25 FFM harness against it.
#
#   java/run_harness.sh
#
# Environment:
#   JAVA_HOME   JDK 25 home. If unset, common install locations are probed, then
#               `java`/`javac` on PATH. Java 25 is required for the FFM API used
#               here (Arena, Linker, downcall handles).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
out="$root/target/java-harness"

find_jdk() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    echo "$JAVA_HOME"; return
  fi
  for d in /usr/lib/jvm/java-25-openjdk-amd64 /usr/lib/jvm/java-25*jdk* \
           /usr/lib/jvm/temurin-25* /usr/lib/jvm/openjdk-25*; do
    if [[ -x "$d/bin/javac" ]]; then echo "$d"; return; fi
  done
  if command -v javac >/dev/null 2>&1; then
    dirname "$(dirname "$(command -v javac)")"; return
  fi
  echo "error: JDK 25 not found; set JAVA_HOME" >&2
  exit 1
}

jdk="$(find_jdk)"

# The harness needs Java 25 FFM; verify rather than fail cryptically later.
major="$("$jdk/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1)"
if [[ -z "$major" || "$major" -lt 25 ]]; then
  echo "error: $jdk is Java ${major:-?}, need 25+" >&2
  exit 1
fi

echo "[run] building libjpe_world (release)"
( cd "$root" && cargo build --release --quiet )

lib="$root/target/release/libjpe_world.so"
if [[ ! -f "$lib" ]]; then
  echo "error: $lib not found after build" >&2
  exit 1
fi

echo "[run] compiling harness with $jdk"
rm -rf "$out"
mkdir -p "$out"
"$jdk/bin/javac" -d "$out" \
  "$here/src/dev/jpe/world/JpeWorldNative.java" \
  "$here/src/dev/jpe/world/WorldEngineHarness.java"

echo "[run] executing harness"
exec "$jdk/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  -cp "$out" \
  dev.jpe.world.WorldEngineHarness "$lib"
