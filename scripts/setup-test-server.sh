#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT_DIR/server"
PAPER_JAR="$SERVER_DIR/paper.jar"
PAPER_URL="https://fill-data.papermc.io/v1/objects/d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c/paper-26.3-246.jar"
PAPER_SHA256="d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c"
MOJANG_JAR="$SERVER_DIR/cache/mojang_26.3.jar"
MOJANG_URL="https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar"
MOJANG_SHA256="d052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64"
USER_AGENT="Bake-Mechanisms/0.1 (https://github.com/CheckSelfy/Bake-Mechanisms)"

download() {
    local url="$1"
    local output="$2"

    if [[ ! -f "$output" ]]; then
        curl -4 --http1.1 --fail --location --retry 3 --retry-all-errors \
            -H "User-Agent: $USER_AGENT" \
            --output "$output" \
            "$url"
    fi
}

verify_checksum() {
    local file="$1"
    local expected="$2"
    local actual

    if command -v sha256sum >/dev/null 2>&1; then
        read -r actual _ < <(sha256sum "$file")
    else
        read -r actual _ < <(shasum -a 256 "$file")
    fi

    if [[ "$actual" != "$expected" ]]; then
        echo "Checksum mismatch for $file. Delete it and run setup again." >&2
        exit 1
    fi
}

mkdir -p "$SERVER_DIR/plugins" "$SERVER_DIR/cache"
download "$PAPER_URL" "$PAPER_JAR"
download "$MOJANG_URL" "$MOJANG_JAR"
verify_checksum "$PAPER_JAR" "$PAPER_SHA256"
verify_checksum "$MOJANG_JAR" "$MOJANG_SHA256"

if [[ ! -f "$SERVER_DIR/eula.txt" ]]; then
    printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
fi

if [[ ! -f "$SERVER_DIR/server.properties" ]]; then
    printf 'online-mode=false\nwhite-list=false\n' > "$SERVER_DIR/server.properties"
fi

echo "Paper 26.3 build 246 is ready in server/."
