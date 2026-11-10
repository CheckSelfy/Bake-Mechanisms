#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT_DIR/server"

if [[ ! -f "$SERVER_DIR/paper.jar" ]]; then
    echo "Run scripts/setup-test-server.sh first." >&2
    exit 1
fi

exec "$ROOT_DIR/gradlew" --project-dir "$ROOT_DIR" runServer
