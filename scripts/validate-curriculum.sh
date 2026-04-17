#!/usr/bin/env bash
# Validate every /curriculum/NNN-*.json against /curriculum/schema.json.
# Uses ajv-cli if installed, otherwise python jsonschema.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCHEMA="${REPO_ROOT}/curriculum/schema.json"
CURR_DIR="${REPO_ROOT}/curriculum"

shopt -s nullglob
files=( "${CURR_DIR}"/[0-9][0-9][0-9]-*.json )
if [ ${#files[@]} -eq 0 ]; then
  echo "no lessons to validate"
  exit 0
fi

if command -v ajv >/dev/null 2>&1; then
  ajv validate -s "${SCHEMA}" -d "${CURR_DIR}/[0-9][0-9][0-9]-*.json" --spec=draft2020 --strict=false
elif command -v python3 >/dev/null 2>&1; then
  python3 - "$SCHEMA" "${files[@]}" <<'PY'
import json, sys
try:
    from jsonschema import validate, Draft202012Validator
except ImportError:
    print("error: install python jsonschema or npm i -g ajv-cli", file=sys.stderr)
    sys.exit(2)
schema = json.load(open(sys.argv[1]))
Draft202012Validator.check_schema(schema)
errors = 0
for path in sys.argv[2:]:
    try:
        validate(json.load(open(path)), schema)
    except Exception as e:
        print(f"FAIL {path}: {e.message if hasattr(e,'message') else e}")
        errors += 1
    else:
        print(f"ok   {path}")
print(f"\n{len(sys.argv)-2} lessons, {errors} failures")
sys.exit(1 if errors else 0)
PY
else
  echo "error: install ajv-cli (npm i -g ajv-cli) or python jsonschema" >&2
  exit 2
fi
