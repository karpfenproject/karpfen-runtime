#!/usr/bin/env bash
# karpfen-runtime/run_local.sh – Start the Karpfen runtime locally
#
# Builds and runs the karpfen-runtime server using the Java and Python
# installations available on the host system (no Docker required).
#
# Prerequisites:
#   - Java 21+ on PATH  (java --version)
#   - Python 3.12+      (python / python3 / py)
#
# Configuration:
#   Edit application.conf in this directory before running.
#   This script does NOT overwrite application.conf.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Karpfen Runtime – Local ==="
echo ""

# ── Build (skip tests for speed) --------------------------------------------
echo "[1/2] Compiling karpfen-runtime (this may take a moment on first run) ..."
./gradlew installDist -x test --quiet
echo "      → Build successful"
echo ""

# ── Start server -------------------------------------------------------------
echo "[2/2] Starting karpfen-runtime server ..."
echo "      → HTTP  API : http://127.0.0.1:8080"
echo "      → WebSocket  : ws://127.0.0.1:8080/ws"
echo ""
echo "Press Ctrl+C to stop the server."
echo ""

./build/install/karpfen-runtime/bin/karpfen-runtime
