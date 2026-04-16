#!/usr/bin/env bash
# sync-curriculum.sh
# Copies /curriculum/*.json into /android/app/src/main/assets/curriculum/.
# Idempotent. Safe to run on a clean checkout (creates the destination directory).
# Wired in as a Gradle preBuild dependency via app/build.gradle.kts:syncCurriculum.

set -euo pipefail

# Resolve paths relative to the repo root, regardless of CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SRC="$REPO_ROOT/curriculum"
DST="$REPO_ROOT/android/app/src/main/assets/curriculum"

if [ ! -d "$SRC" ]; then
    echo "sync-curriculum: source directory $SRC does not exist; nothing to do." >&2
    exit 0
fi

mkdir -p "$DST"

shopt -s nullglob
copied=0
skipped=0
# Only ship lesson JSON + index.json — schema.json is build-time validation only and
# would just bloat the APK.
for f in "$SRC"/*.json; do
    name="$(basename "$f")"
    if [ "$name" = "schema.json" ]; then
        continue
    fi
    target="$DST/$name"
    if [ -f "$target" ] && cmp -s "$f" "$target"; then
        skipped=$((skipped + 1))
    else
        cp "$f" "$target"
        echo "sync-curriculum: copied $name"
        copied=$((copied + 1))
    fi
done

# Prune stale curriculum files that no longer exist in /curriculum.
for f in "$DST"/*.json; do
    name="$(basename "$f")"
    if [ ! -f "$SRC/$name" ] || [ "$name" = "schema.json" ]; then
        rm -f "$f"
        echo "sync-curriculum: removed stale $name"
    fi
done

echo "sync-curriculum: done ($copied copied, $skipped unchanged)"
