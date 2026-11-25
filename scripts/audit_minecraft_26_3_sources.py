#!/usr/bin/env python3
"""Verify and inspect the exact Mojang 26.3 release and NeoForge-patched jar.

Minecraft binaries and decompiled output stay under build/ and are never committed
or uploaded as workflow artifacts. The report contains hashes and class-level
comparison metadata only.
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

VERSION = "26.3"
NEOFORGE_VERSION = "26.3.0.115"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VINEFLOWER_VERSION = "1.12.0"
VINEFLOWER_URL = (
    "https://github.com/Vineflower/vineflower/releases/download/"
    f"{VINEFLOWER_VERSION}/vineflower-{VINEFLOWER_VERSION}.jar"
)
VINEFLOWER_SHA256 = "1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d"
PATCHED_JAR = Path("build/moddev/artifacts") / f"minecraft-patched-{NEOFORGE_VERSION}.jar"
NESTED_SERVER_JAR = f"META-INF/versions/{VERSION}/server-{VERSION}.jar"
TARGET_CLASSES = (
    "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator",
    "net/minecraft/world/level/biome/BiomeManager",
    "net/minecraft/world/level/levelgen/NoiseChunk",
    "net/minecraft/world/level/levelgen/RandomState",
    "net/minecraft/world/level/levelgen/NoiseRouter",
    "net/minecraft/world/level/levelgen/densityfunction/DensitySampler",
    "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler",
    "net/minecraft/world/level/levelgen/densityfunction/SamplerContext",
    "net/minecraft/world/level/levelgen/densityfunction/DensityBufferPool",
    "net/minecraft/world/level/levelgen/densityfunction/DensityFunction",
    "net/minecraft/world/level/levelgen/densityfunction/DensityVolume",
    "net/minecraft/world/level/levelgen/densityfunction/op/RangeChoiceFunction",
    "net/minecraft/world/level/levelgen/densityfunction/op/BinaryFunction",
    "net/minecraft/world/level/levelgen/densityfunction/generator/NoiseFunction",
    "net/minecraft/world/level/levelgen/synth/NoiseStack",
    "net/minecraft/world/level/levelgen/synth/PerlinNoise",
)

TARGET_CLASS_PREFIXES = (
    "net/minecraft/world/level/levelgen/densityfunction/",
)


def fetch(url: str, destination: Path, retries: int = 5) -> None:
    """Download atomically with bounded retries for transient rate limits and network errors."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    for attempt in range(retries):
        request = urllib.request.Request(url, headers={"User-Agent": "JPE-Optimizer-source-audit/1.1"})
        try:
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
            os.replace(temporary, destination)
            return
        except urllib.error.HTTPError as exc:
            temporary.unlink(missing_ok=True)
            if exc.code not in (408, 425, 429, 500, 502, 503, 504) or attempt + 1 >= retries:
                raise
            retry_after = exc.headers.get("Retry-After")
            try:
                delay = min(30.0, max(1.0, float(retry_after))) if retry_after else min(30.0, 2.0 ** attempt)
            except ValueError:
                delay = min(30.0, 2.0 ** attempt)
            print(f"HTTP {exc.code} while downloading {url}; retry {attempt + 1}/{retries} in {delay:.0f}s")
            time.sleep(delay)
        except (urllib.error.URLError, TimeoutError) as exc:
            temporary.unlink(missing_ok=True)
            if attempt + 1 >= retries:
                raise
            delay = min(30.0, 2.0 ** attempt)
            print(f"Network error while downloading {url}: {exc}; retry {attempt + 1}/{retries} in {delay:.0f}s")
            time.sleep(delay)
    raise RuntimeError(f"Failed to download {url} after {retries} attempts")


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def selected_class(name: str) -> bool:
    if not name.endswith(".class"):
        return False
    return any(name == prefix + ".class" or name.startswith(prefix + "$") and name.endswith(".class")
               for prefix in TARGET_CLASSES) or any(
        name.startswith(prefix) and name.endswith(".class") for prefix in TARGET_CLASS_PREFIXES
    )


def make_subset(source_jar: Path, subset_jar: Path) -> dict[str, str]:
    hashes: dict[str, str] = {}
    with zipfile.ZipFile(source_jar) as source, zipfile.ZipFile(subset_jar, "w", zipfile.ZIP_DEFLATED) as output:
        for info in source.infolist():
            if selected_class(info.filename):
                data = source.read(info.filename)
                output.writestr(info.filename, data)
                hashes[info.filename] = sha256_bytes(data)
    expected = {prefix + ".class" for prefix in TARGET_CLASSES}
    missing = sorted(expected - hashes.keys())
    if missing:
        raise RuntimeError(f"{source_jar} is missing target classes: {missing}")
    return hashes



def class_method_inventory(data: bytes) -> dict[tuple[str, str], dict[str, object]]:
    """Read method names/descriptors and Code lengths from a JVM class file."""
    import struct

    offset = 0

    def u1() -> int:
        nonlocal offset
        value = data[offset]
        offset += 1
        return value

    def u2() -> int:
        nonlocal offset
        value = struct.unpack_from(">H", data, offset)[0]
        offset += 2
        return value

    def u4() -> int:
        nonlocal offset
        value = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        return value

    def take(length: int) -> bytes:
        nonlocal offset
        value = data[offset:offset + length]
        if len(value) != length:
            raise ValueError("Truncated class file")
        offset += length
        return value

    if u4() != 0xCAFEBABE:
        raise ValueError("Invalid class-file magic")
    u2(); u2()
    cp_count = u2()
    cp: list[object] = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = u1()
        if tag == 1:
            length = u2()
            cp[i] = take(length).decode("utf-8", errors="replace")
        elif tag in (3, 4):
            take(4)
        elif tag in (5, 6):
            take(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = u2()
        elif tag in (9, 10, 11, 12, 17, 18):
            take(4)
        elif tag == 15:
            take(3)
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        i += 1

    def utf(index: int) -> str:
        value = cp[index]
        return value if isinstance(value, str) else ""

    u2(); u2(); u2()
    for _ in range(u2()):
        u2()
    for _ in range(u2()):
        u2(); u2(); u2()
        for _ in range(u2()):
            u2()
            length = u4()
            take(length)

    methods: dict[tuple[str, str], dict[str, object]] = {}
    for _ in range(u2()):
        access = u2()
        name = utf(u2())
        descriptor = utf(u2())
        code_length = None
        code_hash = None
        for _ in range(u2()):
            attribute_name = utf(u2())
            attribute_length = u4()
            attribute_end = offset + attribute_length
            if attribute_name == "Code":
                u2(); u2()
                length = u4()
                code = take(length)
                code_length = length
                code_hash = hashlib.sha256(code).hexdigest()
            offset = attribute_end
        methods[(name, descriptor)] = {
            "access": access,
            "code_length": code_length,
            "code_hash": code_hash,
        }
    return methods


def compare_method_inventories(
    vanilla_jar: Path, patched_jar: Path, class_names: list[str]
) -> list[str]:
    lines: list[str] = []
    with zipfile.ZipFile(vanilla_jar) as vanilla, zipfile.ZipFile(patched_jar) as patched:
        for class_name in class_names:
            old = class_method_inventory(vanilla.read(class_name))
            new = class_method_inventory(patched.read(class_name))
            if class_name == "net/minecraft/world/level/biome/BiomeManager.class":
                expected = ("getFiddledDistance", "(JIIIDDD)D")
                for label, methods in (("vanilla", old), ("NeoForge-patched", new)):
                    if expected not in methods:
                        raise RuntimeError(f"{label} BiomeManager is missing expected method {expected}")
                    if methods[expected]["access"] & 0x000A != 0x000A:
                        raise RuntimeError(f"{label} BiomeManager.getFiddledDistance is not private static")
                lines.append("  verified exact private static target getFiddledDistance(JIIIDDD)D")
            added = sorted(set(new) - set(old))
            removed = sorted(set(old) - set(new))
            common = sorted(set(old) & set(new))
            length_changed = [
                (key, old[key]["code_length"], new[key]["code_length"])
                for key in common
                if old[key]["code_length"] != new[key]["code_length"]
            ]
            raw_code_changed = [
                key for key in common
                if old[key]["code_hash"] != new[key]["code_hash"]
            ]
            lines.append(f"{class_name}: methods vanilla={len(old)}, patched={len(new)}")
            lines.append(f"  added signatures: {len(added)}; removed signatures: {len(removed)}")
            lines.append(f"  changed Code lengths: {len(length_changed)}; raw Code hashes differ: {len(raw_code_changed)}")
            if class_name in {
                "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class",
                "net/minecraft/world/level/levelgen/NoiseChunk.class",
                "net/minecraft/world/level/levelgen/RandomState.class",
                "net/minecraft/world/level/levelgen/NoiseRouter.class",
                "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler.class",
                "net/minecraft/world/level/levelgen/densityfunction/DensityBufferArena.class",
                "net/minecraft/world/level/levelgen/densityfunction/DensityBuffer.class",
                "net/minecraft/world/level/levelgen/densityfunction/DensityVolume.class",
                "net/minecraft/world/level/levelgen/densityfunction/SamplerContext.class",
            }:
                for method_name, descriptor in common:
                    lines.append(f"  = {method_name}{descriptor}")
            for name, descriptor in added:
                lines.append(f"  + {name}{descriptor}")
            for name, descriptor in removed:
                lines.append(f"  - {name}{descriptor}")
            for (name, descriptor), before, after in length_changed:
                lines.append(f"  * {name}{descriptor}: Code bytes {before} -> {after}")
    return lines

def extract_method_blocks(source: str, method_names: set[str]) -> list[str]:
    """Extract selected decompiled Java method bodies with multiline signatures."""
    import re

    names = "|".join(map(re.escape, sorted(method_names)))
    declaration = re.compile(
        r"(?m)^[ \t]*(?:(?:public|protected|private|static|final|synchronized|native|abstract|strictfp)\s+)*"
        + r"(?:[\w$.,<>?\[\]]+\s+)?(" + names
        + r")\s*\([^;{}]*\)\s*(?:throws\s+[^{};]+)?\s*\{",
        re.DOTALL,
    )
    results: list[str] = []
    for match in declaration.finditer(source):
        header_start = match.start()
        brace = source.find("{", match.start(), match.end())
        if brace < 0:
            continue
        depth = 0
        state = "code"
        i = brace
        while i < len(source):
            ch = source[i]
            nxt = source[i + 1] if i + 1 < len(source) else ""
            if state == "code":
                if ch == '"': state = "string"
                elif ch == "'": state = "char"
                elif ch == "/" and nxt == "*": state = "block"; i += 1
                elif ch == "/" and nxt == "/": state = "line"; i += 1
                elif ch == "{": depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        results.append(source[header_start:i + 1].strip())
                        break
            elif state == "string":
                if ch == "\\": i += 1
                elif ch == '"': state = "code"
            elif state == "char":
                if ch == "\\": i += 1
                elif ch == "'": state = "code"
            elif state == "block":
                if ch == "*" and nxt == "/": state = "code"; i += 1
            elif state == "line":
                if ch == "\n": state = "code"
            i += 1
    return results


def source_inspection(vanilla_root: Path, patched_root: Path) -> list[str]:
    """Emit selected exact 26.3 method bodies from both vanilla and patched sources."""
    selected = {
        "NoiseBasedChunkGenerator.java": {"buildTerrain", "doFill", "createNoiseChunk"},
        "NoiseChunk.java": {"NoiseChunk", "cachingSamplers", "volume", "close"},
        "RandomState.java": {"RandomState", "create", "getSampler", "samplersWithContext",
                             "sampleBlockValueUncached", "acquireDensityBufferPool"},
        "NoiseRouter.java": {"finalDensity", "createClimateSampler"},
        "DensityFunctionCompiler.java": {"DensityFunctionCompiler", "getSampler",
                                         "optimizeAndCompile", "compileSampler",
                                         "prepareCache", "reuseOrPrepareCache"},
        "DensitySampler.java": {"sampleVolume", "sampleValue", "bind"},
        "DensityBufferArena.java": {"acquire", "release"},
        "DensityBufferPool.java": {"tryTakeBest", "acquire", "release"},
        "DensityBuffer.java": {"addTo", "get", "set", "setRange", "copyFrom", "fill"},
        "DensityVolume.java": {"DensityVolume", "indexUnchecked", "sizeX", "sizeY", "sizeZ",
                               "blockX", "blockY", "blockZ"},
        "SamplerContext.java": {"SamplerContext", "acquireBuffer", "getCacheCell",
                                "sampleValueCached", "sampleVolumeCached"},
        "CachingDensitySampler.java": {"sampleVolume", "sampleValue", "bind"},
        "ContextBoundSampler.java": {"sampleVolume", "sampleValue", "bind"},
        "ScopedDensityBuffer.java": {"close"},
    }
    lines: list[str] = []
    for label, root in (("MOJANG_VANILLA", vanilla_root), ("NEOFORGE_PATCHED", patched_root)):
        lines.append(f"===== {label} =====")
        for filename, methods in selected.items():
            matches = list(root.rglob(filename))
            if not matches:
                lines.append(f"--- {filename}: NOT FOUND ---")
                continue
            for path in matches:
                source = path.read_text(encoding="utf-8", errors="replace")
                blocks = extract_method_blocks(source, methods)
                lines.append(f"--- {filename}: {len(blocks)} selected methods ---")
                lines.extend(blocks or ["(no selected declarations matched)"])
    return lines


def main() -> int:
    root = Path("build/source-audit")
    root.mkdir(parents=True, exist_ok=True)
    manifest_path = root / "version_manifest_v2.json"
    fetch(MANIFEST_URL, manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = [entry for entry in manifest["versions"] if entry.get("id") == VERSION]
    if len(entries) != 1 or entries[0].get("type") != "release":
        raise RuntimeError(f"Mojang manifest did not identify exactly one {VERSION} full release")
    version_entry = entries[0]

    version_json_path = root / f"{VERSION}.json"
    fetch(version_entry["url"], version_json_path)
    version_json_bytes = version_json_path.read_bytes()
    manifest_version_sha1 = version_entry.get("sha1")
    if manifest_version_sha1 and hashlib.sha1(version_json_bytes).hexdigest() != manifest_version_sha1:
        raise RuntimeError("Version metadata SHA-1 does not match Mojang's manifest")
    version_data = json.loads(version_json_bytes)
    if version_data.get("id") != VERSION or version_data.get("type") != "release":
        raise RuntimeError("Version metadata is not the 26.3 full release")

    server = version_data["downloads"]["server"]
    server_path = root / f"server-{VERSION}.jar"
    fetch(server["url"], server_path)
    actual_server_sha1 = sha1_file(server_path)
    if actual_server_sha1 != server["sha1"]:
        raise RuntimeError(f"Official server SHA-1 mismatch: {actual_server_sha1} != {server['sha1']}")
    if server_path.stat().st_size != server["size"]:
        raise RuntimeError("Official server jar size does not match Mojang's manifest")

    vanilla_inner = root / f"server-inner-{VERSION}.jar"
    with zipfile.ZipFile(server_path) as bundle:
        with bundle.open(NESTED_SERVER_JAR) as source, vanilla_inner.open("wb") as output:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
    with zipfile.ZipFile(vanilla_inner) as inner:
        if "version.json" in inner.namelist():
            inner_version = json.loads(inner.read("version.json"))
            if inner_version.get("id") != VERSION:
                raise RuntimeError("Nested server jar version.json does not identify 26.3")

    if not PATCHED_JAR.is_file():
        raise RuntimeError(f"NeoForge patched artifact not found: {PATCHED_JAR}")

    vanilla_subset = root / "vanilla-target-classes.jar"
    patched_subset = root / "neoforge-target-classes.jar"
    vanilla_hashes = make_subset(vanilla_inner, vanilla_subset)
    patched_hashes = make_subset(PATCHED_JAR, patched_subset)

    vineflower = root / f"vineflower-{VINEFLOWER_VERSION}.jar"
    if not vineflower.is_file() or hashlib.sha256(vineflower.read_bytes()).hexdigest() != VINEFLOWER_SHA256:
        fetch(VINEFLOWER_URL, vineflower)
    actual_vineflower_sha256 = hashlib.sha256(vineflower.read_bytes()).hexdigest()
    if actual_vineflower_sha256 != VINEFLOWER_SHA256:
        raise RuntimeError(
            f"Vineflower SHA-256 mismatch: {actual_vineflower_sha256} != {VINEFLOWER_SHA256}"
        )
    decompile_root = root / "decompiled"
    vanilla_out = decompile_root / "vanilla"
    patched_out = decompile_root / "neoforge"
    for subset, output in ((vanilla_subset, vanilla_out), (patched_subset, patched_out)):
        output.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["java", "-Xmx2G", "-jar", str(vineflower), "--folder", "--thread-count=2",
             str(subset), str(output)],
            check=True,
        )
        if not list(output.rglob("*.java")):
            raise RuntimeError(f"Vineflower produced no Java source for {subset}")

    vanilla_names = set(vanilla_hashes)
    patched_names = set(patched_hashes)
    common_names = sorted(vanilla_names & patched_names)
    vanilla_only = sorted(vanilla_names - patched_names)
    patched_only = sorted(patched_names - vanilla_names)
    method_report = compare_method_inventories(vanilla_inner, PATCHED_JAR, common_names)
    changed = [name for name in common_names if vanilla_hashes[name] != patched_hashes[name]]
    report = [
        f"Minecraft version: {VERSION} ({version_data['type']})",
        f"Mojang version metadata URL: {version_entry['url']}",
        f"Mojang server URL: {server['url']}",
        f"Mojang server SHA-1 expected: {server['sha1']}",
        f"Mojang server SHA-1 actual:   {actual_server_sha1}",
        f"Mojang server size: {server_path.stat().st_size}",
        f"NeoForge version: {NEOFORGE_VERSION}",
        f"NeoForge patched jar: {PATCHED_JAR}",
        f"NeoForge patched jar SHA-256: {hashlib.sha256(PATCHED_JAR.read_bytes()).hexdigest()}",
        f"Vineflower: {VINEFLOWER_VERSION}",
        f"Target classes present in both jars: {len(common_names)}",
        f"Vanilla-only target classes: {len(vanilla_only)}",
        f"NeoForge-only target classes: {len(patched_only)}",
        f"Target class bytecode identical: {len(common_names) - len(changed)}",
        f"Target class bytecode changed by NeoForge patching: {len(changed)}",
        "",
        "Vanilla-only target class entries:",
        *(vanilla_only or ["(none)"]),
        "",
        "NeoForge-only target class entries:",
        *(patched_only or ["(none)"]),
        "",
        "Changed target class entries:",
        *(changed or ["(none)"]),
        "",
        "DensityFunction package class inventory:",
        *(name for name in common_names if name.startswith("net/minecraft/world/level/levelgen/densityfunction/")),
        "",
        "Method-level inventory (raw Code hashes may differ when constant-pool indices are remapped):",
        *method_report,
        "",
        "",
        "Selected decompiled method bodies (for lifecycle and batch-boundary inspection):",
        *source_inspection(vanilla_out, patched_out),
    ]
    report_path = root / "report.txt"
    report_path.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"SOURCE AUDIT FAILED: {exc}", file=sys.stderr)
        raise
