#!/usr/bin/env bash
# smithy-lsp Claude Code plugin — SessionStart hook.
#
# Downloads the pinned smithy-language-server release into ${CLAUDE_PLUGIN_DATA},
# then symlinks the launcher into a directory on PATH so Claude Code's LSP loader
# (which resolves "command" via PATH only — ${CLAUDE_PLUGIN_DATA} is NOT expanded
# for lspServers.command in Claude Code 2.1.x) can find it.
#
# Idempotent: subsequent runs exit in milliseconds.
#
# Override the version with SMITHY_LSP_VERSION=0.8.0 in the environment.
# Override the symlink target with SMITHY_LSP_BIN_DIR=/some/dir/on/PATH.

set -euo pipefail

LSP_VERSION="${SMITHY_LSP_VERSION:-0.8.0}"
DATA_DIR="${CLAUDE_PLUGIN_DATA:?CLAUDE_PLUGIN_DATA must be set by Claude Code}"
BIN_PATH="${DATA_DIR}/bin/smithy-language-server"
VERSION_FILE="${DATA_DIR}/.smithy-ls-version"
SYMLINK_DIR="${SMITHY_LSP_BIN_DIR:-${HOME}/.local/bin}"
SYMLINK_PATH="${SYMLINK_DIR}/smithy-language-server"

log() { printf '[smithy-lsp] %s\n' "$*" >&2; }

ensure_symlink() {
    mkdir -p "$SYMLINK_DIR"
    if [[ -L "$SYMLINK_PATH" && "$(readlink -f "$SYMLINK_PATH")" == "$(readlink -f "$BIN_PATH")" ]]; then
        return 0
    fi
    ln -sf "$BIN_PATH" "$SYMLINK_PATH"
    log "Symlinked $SYMLINK_PATH -> $BIN_PATH"
    case ":${PATH}:" in
        *":${SYMLINK_DIR}:"*) ;;
        *) log "WARNING: $SYMLINK_DIR is not on PATH. Add it to your shell rc, or set SMITHY_LSP_BIN_DIR to a directory that is." ;;
    esac
}

# Fast path: already installed at the pinned version.
if [[ -x "$BIN_PATH" ]] && [[ -f "$VERSION_FILE" ]] && [[ "$(cat "$VERSION_FILE" 2>/dev/null)" == "$LSP_VERSION" ]]; then
    ensure_symlink
    exit 0
fi

# Detect platform. If unsupported, exit 0 so Claude Code still starts —
# LSP just won't attach. Plugin remains installed for when the user switches hosts.
case "$(uname -s)" in
    Darwin) OS_TAG="darwin" ;;
    Linux)  OS_TAG="linux"  ;;
    *)
        log "Unsupported OS: $(uname -s). Smithy LSP will not start on this host."
        exit 0
        ;;
esac

case "$(uname -m)" in
    x86_64|amd64)  ARCH_TAG="x86_64"  ;;
    arm64|aarch64) ARCH_TAG="aarch64" ;;
    *)
        log "Unsupported arch: $(uname -m). Smithy LSP will not start on this host."
        exit 0
        ;;
esac

URL="https://github.com/smithy-lang/smithy-language-server/releases/download/${LSP_VERSION}/smithy-language-server-${OS_TAG}-${ARCH_TAG}.zip"

log "Installing smithy-language-server ${LSP_VERSION} (${OS_TAG}-${ARCH_TAG})..."

TMP_ZIP="$(mktemp "${TMPDIR:-/tmp}/smithy-ls.XXXXXX.zip")"
trap 'rm -f "$TMP_ZIP"' EXIT

if ! curl -fLsS -o "$TMP_ZIP" "$URL"; then
    log "ERROR: download failed from $URL"
    exit 1
fi

mkdir -p "$DATA_DIR"
rm -rf "$DATA_DIR/bin" "$DATA_DIR/lib" "$DATA_DIR/legal"

if ! unzip -q -o "$TMP_ZIP" -d "$DATA_DIR"; then
    log "ERROR: unzip failed"
    exit 1
fi

if [[ ! -f "$BIN_PATH" ]]; then
    log "ERROR: expected binary at $BIN_PATH not found after extract"
    log "Extracted contents:"
    ls -la "$DATA_DIR" >&2 || true
    exit 1
fi

chmod +x "$BIN_PATH"
printf '%s\n' "$LSP_VERSION" > "$VERSION_FILE"
ensure_symlink

log "Installed to $BIN_PATH"
exit 0
