#!/usr/bin/env bash
# Make Mesa's software Vulkan driver (lavapipe) visible to the loader in CI.
#
# The ICD manifest's file name is a packaging detail that has already differed
# between Ubuntu releases (lvp_icd.x86_64.json vs lvp_icd.json), and a
# hard-coded path that does not exist makes the loader report
# VK_ERROR_INCOMPATIBLE_DRIVER rather than "no driver". So: look for whatever
# manifest the installed package actually shipped, and if the driver library is
# present but its manifest is not, write an equivalent one to /tmp.
#
# Usage: icd="$(bash ci/vulkan-icd.sh)" && export VK_DRIVER_FILES="$icd"
#
# The discovered manifest path is printed on stdout (diagnostics go to stderr),
# and inside GitHub Actions the variables are additionally appended to
# $GITHUB_ENV so later steps inherit them. It never fails the build: if no
# lavapipe driver can be found at all, stdout stays empty and the backend tests
# report the real reason.
set -u

report() { printf 'vulkan-icd: %s\n' "$1" >&2; }

find_manifest() {
    local dir candidate
    for dir in /usr/share/vulkan/icd.d /etc/vulkan/icd.d "$HOME/.local/share/vulkan/icd.d"; do
        [ -d "$dir" ] || continue
        for candidate in "$dir"/lvp_icd*.json; do
            # An unmatched glob stays literal, so check it really exists.
            [ -f "$candidate" ] || continue
            printf '%s\n' "$candidate"
            return 0
        done
    done
    return 1
}

synthesise_manifest() {
    local lib out
    for lib in /usr/lib/x86_64-linux-gnu/libvulkan_lvp.so \
               /usr/lib/libvulkan_lvp.so \
               /usr/lib64/libvulkan_lvp.so; do
        [ -f "$lib" ] || continue
        mkdir -p /tmp/jpe-vulkan/icd.d || return 1
        out=/tmp/jpe-vulkan/icd.d/lvp_icd.json
        cat >"$out" <<EOF
{
    "file_format_version": "1.0.1",
    "ICD": {
        "library_path": "$lib",
        "api_version": "1.3.0"
    }
}
EOF
        printf '%s\n' "$out"
        return 0
    done
    return 1
}

icd="$(find_manifest || true)"
if [ -n "$icd" ]; then
    report "using packaged manifest $icd"
else
    icd="$(synthesise_manifest || true)"
    if [ -n "$icd" ]; then
        report "no packaged manifest, wrote $icd"
    else
        report "no lavapipe driver found; leaving the loader unconfigured"
        exit 0
    fi
fi

if [ -n "${GITHUB_ENV:-}" ]; then
    {
        printf 'VK_DRIVER_FILES=%s\n' "$icd"
        printf 'VK_ICD_FILENAMES=%s\n' "$icd"
    } >>"$GITHUB_ENV"
    report "exported VK_DRIVER_FILES/VK_ICD_FILENAMES to later steps"
fi

# The path goes to stdout so the caller can export it for the *current* shell as
# well; $GITHUB_ENV only reaches later steps.
printf '%s\n' "$icd"
