#!/usr/bin/env bash
# karpfen-runtime/run_docker.sh – Start the Karpfen runtime in Docker
#
# Builds a Docker image from karpfen-runtime/Dockerfile and runs it as a
# one-shot container.  The container is removed automatically when it exits
# (--rm).
#
# Usage:
#   ./run_docker.sh [HOST_LOG_DIR]
#
#   HOST_LOG_DIR  Optional absolute or relative path on the host where
#                 engine trace log files will be written.
#                 Default: ./logs  (relative to karpfen-runtime/)
#
# Configuration:
#   Edit application.conf in this directory BEFORE running this script.
#   This script does NOT overwrite application.conf.
#
#   server.host does NOT need to be changed for Docker – this script
#   automatically overrides it to "0.0.0.0" inside the container so the
#   server is reachable on the mapped port.  Your application.conf keeps
#   "127.0.0.1" for safe local use.
#
#   To persist trace logs, enable tracing in application.conf and set:
#     engineTracing {
#       tracingEnabled      = true
#       tracingLogDirectory = "/app/logs"   # container-side mount point
#     }
#   Trace files will then appear in HOST_LOG_DIR on the host.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Resolve log directory ----------------------------------------------------
RAW_LOG_DIR="${1:-$SCRIPT_DIR/logs}"
# Resolve to absolute path without requiring realpath (portable)
mkdir -p "$RAW_LOG_DIR"
HOST_LOG_DIR="$(cd "$RAW_LOG_DIR" && pwd)"

# ── Prepare container config -------------------------------------------------
# Create a temporary application.conf with host overridden to "0.0.0.0" so
# the server binds to all interfaces inside the container.  The original file
# is never modified.
TMPCONF="$(mktemp)"
trap 'rm -f "$TMPCONF"' EXIT
sed 's/host *= *"[^"]*"/host = "0.0.0.0"/' "$SCRIPT_DIR/application.conf" > "$TMPCONF"

echo "=== Karpfen Runtime – Docker ==="
echo ""
echo "  Config : $SCRIPT_DIR/application.conf  (host overridden → 0.0.0.0 inside container)"
echo "  Logs   : $HOST_LOG_DIR  → /app/logs inside container"
echo ""

# ── Build image -------------------------------------------------------------
echo "[1/2] Building Docker image karpfen-runtime ..."
docker build -t karpfen-runtime "$SCRIPT_DIR"
echo "      → Image built"
echo ""

# ── Run container (one-shot) ------------------------------------------------
echo "[2/2] Starting container ..."
echo "      → HTTP  API : http://127.0.0.1:8080"
echo "      → WebSocket  : ws://127.0.0.1:8080/ws"
echo ""
echo "Press Ctrl+C to stop the container (it will be removed automatically)."
echo ""

docker run --rm \
    -p 8080:8080 \
    -v "$TMPCONF:/app/application.conf:ro" \
    -v "$HOST_LOG_DIR:/app/logs" \
    karpfen-runtime
