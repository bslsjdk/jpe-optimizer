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
import urllib.request
import zipfile
from pathlib import Path

VERSION = "26.3"
NEOFORGE_VERSION = "26.3.0.115"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VINEFLOWER_VERSION = "1.12.0"
VINEFLOWER_URL = (
    "https://repo.maven.apache.org/maven2/org/vineflower/vineflower/"
    f"{VINEFLOWER_VERSION}/vineflower-{VINEFLOWER_VERSION}.jar"
)
PATCHED_JAR = Path("build/moddev/artifacts") / f"minecraft-patched-{NEOFORGE_VERSION}.jar"
NESTED_SERVER_JAR = f"META-INF/versions/{VERSION}/server-{VERSION}.jar"
TARGET_CLASSES = (
    "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator",
    "net/minecraft/world/level/levelgen/NoiseChunk",
    "net/minecraft/world/level/levelgen/densityfunction/DensitySampler",
    "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler",
    "net/minecraft/world/level/levelgen/densityfunction/SamplerContext",
    "net/minecraft/world/level/levelgen/densityfunction/DensityBufferPool",
    "net/minecraft/world/level/levelgen/densityfunction/generator/NoiseFunction",
    "net/minecraft/world/level/levelgen/synth/NoiseStack",
    "net/minecraft/world/level/levelgen/synth/PerlinNoise",
)


def fetch(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "JPE-Optimizer-source-audit/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)


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
               for prefix in TARGET_CLASSES)


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
    fetch(VINEFLOWER_URL, vineflower)
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

    all_names = sorted(set(vanilla_hashes) | set(patched_hashes))
    changed = [name for name in all_names if vanilla_hashes.get(name) != patched_hashes.get(name)]
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
        f"Target classes present in both jars: {len(all_names)}",
        f"Target class bytecode identical: {len(all_names) - len(changed)}",
        f"Target class bytecode changed by NeoForge patching: {len(changed)}",
        "",
        "Changed target class entries:",
        *(changed or ["(none)"]),
        "",
        "Decompiled source generated locally in build/source-audit/decompiled/ (not committed or uploaded).",
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
