#!/usr/bin/env bash
# Installs a NeoForge dedicated server, drops the built mod into mods/, boots it,
# and force-loads a 16x16 chunk square so world generation actually runs.
#
#   scripts/run-mc-server.sh              # install (once), start, force-load, report
#   scripts/run-mc-server.sh --stop       # stop through RCON
#   scripts/run-mc-server.sh --no-start   # install and configure only
#
# Environment:
#   JAVA_HOME              JDK 25 (Minecraft 26.3 requires it)
#   JPE_SERVER_DIR         server directory, default <repo>/.mc-server
#   JPE_MOD_JAR            mod jar, default build/libs/jpe_optimizer-0.1.0.jar
#   JPE_SERVER_MEMORY      heap for the server, default 4G
#   JPE_SEED               level seed, default jpe-verify-1
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${JPE_SERVER_DIR:-$ROOT/.mc-server}"
NEO_VERSION="26.3.0.115"
MEMORY="${JPE_SERVER_MEMORY:-4G}"
SEED="${JPE_SEED:-jpe-verify-1}"
RCON_PORT="${JPE_RCON_PORT:-25575}"
RCON_PASSWORD="${JPE_RCON_PASSWORD:-jpe}"
JAR="${JPE_MOD_JAR:-$ROOT/build/libs/jpe_optimizer-0.1.0.jar}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_BIN="$(command -v "$JAVA" || true)"

rcon() {
  python3 - "$RCON_PORT" "$RCON_PASSWORD" "$1" <<'PY'
import socket, struct, sys
port, password, command = int(sys.argv[1]), sys.argv[2], sys.argv[3]
def packet(request_id, kind, payload):
    body = struct.pack("<ii", request_id, kind) + payload.encode() + b"\x00\x00"
    return struct.pack("<i", len(body)) + body
def read(sock):
    head = sock.recv(4)
    if len(head) < 4:
        return b""
    (length,) = struct.unpack("<i", head)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    return data[8:-2]
with socket.create_connection(("127.0.0.1", port), timeout=20) as sock:
    sock.sendall(packet(1, 3, password))
    read(sock)
    sock.sendall(packet(2, 2, command))
    sys.stdout.write(read(sock).decode("utf-8", "replace") + "\n")
PY
}

if [[ "${1:-}" == "--stop" ]]; then
  rcon "stop"
  exit 0
fi

if [[ -z "$JAVA_BIN" ]] || ! "$JAVA_BIN" -version 2>&1 | head -1 | grep -qE '"25|"26'; then
  echo "Minecraft 26.3 needs JDK 25; set JAVA_HOME to a JDK 25 install." >&2
  "$JAVA_BIN" -version >&2 || true
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "Mod jar not found: $JAR" >&2
  echo "Build it first: gradle jar   (or set JPE_MOD_JAR)" >&2
  exit 1
fi

mkdir -p "$SERVER_DIR"
if [[ ! -f "$SERVER_DIR/libraries/net/neoforged/neoforge/$NEO_VERSION/unix_args.txt" ]]; then
  echo "Installing NeoForge $NEO_VERSION into $SERVER_DIR ..."
  curl -fsSL -o "$SERVER_DIR/neoforge-installer.jar" \
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VERSION/neoforge-$NEO_VERSION-installer.jar"
  ( cd "$SERVER_DIR" && "$JAVA_BIN" -jar neoforge-installer.jar --installServer . > installer.log 2>&1 )
fi

printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
cat > "$SERVER_DIR/server.properties" <<EOF
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
level-name=world
level-seed=$SEED
level-type=minecraft:normal
gamemode=creative
difficulty=peaceful
spawn-protection=0
max-tick-time=-1
view-distance=8
simulation-distance=6
sync-chunk-writes=false
# A headless verification server has no players; without this it pauses after
# 60s and force-loaded chunks never generate.
pause-when-empty-seconds=0
EOF
cat > "$SERVER_DIR/user_jvm_args.txt" <<EOF
-Xmx$MEMORY
-Djpe.nativeFinalDensity=shadow
EOF
mkdir -p "$SERVER_DIR/mods"
cp "$JAR" "$SERVER_DIR/mods/"

if [[ "${1:-}" == "--no-start" ]]; then
  echo "Configured $SERVER_DIR; start it with: cd $SERVER_DIR && ./run.sh --nogui"
  exit 0
fi

if pgrep -f "net.neoforged" >/dev/null 2>&1; then
  echo "A server process is already running; use --stop first." >&2
  exit 1
fi

echo "Starting server (log: $SERVER_DIR/server.log) ..."
# run.sh resolves java from PATH, so prepend the JDK we validated above.
( cd "$SERVER_DIR" && { setsid env PATH="$(dirname "$JAVA_BIN"):$PATH" ./run.sh --nogui > server.log 2>&1 < /dev/null & echo $! > server.pid; } )

for _ in $(seq 1 240); do
  if grep -q "Done (" "$SERVER_DIR/server.log" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$(cat "$SERVER_DIR/server.pid")" 2>/dev/null; then
    echo "Server process exited before Done; last lines:" >&2
    tail -20 "$SERVER_DIR/server.log" >&2
    exit 1
  fi
  sleep 2
done
if ! grep -q "Done (" "$SERVER_DIR/server.log" 2>/dev/null; then
  echo "Server did not reach Done; last lines:" >&2
  tail -40 "$SERVER_DIR/server.log" >&2
  exit 1
fi

echo "Force-loading a 16x16 chunk square ..."
rcon "execute in minecraft:overworld run forceload add 0 0 240 240" || true
sleep 30

echo
echo "== startup evidence =="
grep -E "Native runtime ready|Native final-density coverage|Done \(" "$SERVER_DIR/server.log" | tail -6
echo
echo "== world files =="
find "$SERVER_DIR/world" -name "*.mca" | wc -l | sed 's/^/region files: /'
du -sh "$SERVER_DIR/world" | sed 's/^/world size: /'
echo
echo "Server keeps running. Stop it with: $0 --stop"
