#!/usr/bin/env bash
# karpfen-runtime/benchmark_integration.sh – Measure the runtime as a server
#
# Runs only inside the container of test_docker.sh, which has the Python of the
# runtime image:
#
#   ./test_docker.sh --collect "reports/integration" --script benchmark_integration.sh [SECONDS]
#
# The script builds the runtime with installDist, starts it as a server on
# 127.0.0.1:8080 inside the container (no port leaves the container), uploads
# the cleaning robot of examples/single_robot_example through the HTTP API and
# runs it for SECONDS seconds (default 30) once with a tick delay of 0 and once
# with the default of 50 ms.  The robot waits for a start event from a
# WebSocket client; the script replaces that condition by VALUE("true"), so the
# robot starts at once.  After each run it reads GET /observatory/metrics.
#
# Results in build/reports/integration/ of the checkout's copy, which the
# harness brings back to build/container/reports/integration/:
#   metrics-delay<D>.json   the answer of the metrics route at the end of a run
#   summary.txt             ticks, seconds and ticks per second of each run
#   server.log              the output of the server

set -euo pipefail

if [ "${KARPFEN_TEST_CONTAINER:-}" != "1" ]; then
    echo "benchmark_integration.sh runs Python and belongs into the container:" >&2
    echo "  ./test_docker.sh --collect \"reports/integration\" --script benchmark_integration.sh" >&2
    exit 1
fi

SECONDS_PER_RUN="${1:-30}"
R="http://127.0.0.1:8080"
OUT="build/reports/integration"
EXAMPLE="examples/single_robot_example"
mkdir -p "$OUT"

./gradlew --no-daemon --quiet --max-workers="${WORKERS:-3}" -Dorg.gradle.jvmargs=-Xmx1536m \
    -Porg.gradle.java.installations.auto-download=false installDist -x test

build/install/karpfen-runtime/bin/karpfen-runtime > "$OUT/server.log" 2>&1 &
SERVER=$!
trap 'kill "$SERVER" 2> /dev/null || true; wait "$SERVER" 2> /dev/null || true' EXIT

for _ in $(seq 1 600); do
    if curl -sf "$R/health" > /dev/null; then break; fi
    sleep 0.1
done
curl -sf "$R/health" > /dev/null

sed 's/EVENT("public", "start")/VALUE("true")/' "$EXAMPLE/cleaning_robot.kstates" > "$OUT/cleaning_robot.kstates"

: > "$OUT/summary.txt"
for delay in 0 50; do
    env_key="$(curl -sf -X POST "$R/createEnvironment")"
    curl -sf -X PUT "$R/setMetamodel?envKey=$env_key" --data-binary "@$EXAMPLE/cleaning_robot.kmeta" > /dev/null
    curl -sf -X PUT "$R/setModel?envKey=$env_key" --data-binary "@$EXAMPLE/cleaning_robot.kmodel" > /dev/null
    curl -sf -X PUT "$R/setEventDefinitions?envKey=$env_key" --data-binary "@$EXAMPLE/EVENTS.kmeta" > /dev/null
    curl -sf -X PUT "$R/setStateMachine?envKey=$env_key&attachedTo=turtle" \
        --data-binary "@$OUT/cleaning_robot.kstates" > /dev/null
    curl -sf -X POST "$R/setTickDelay?envKey=$env_key&milliseconds=$delay" > /dev/null
    curl -sf -X POST "$R/runEnvironment?envKey=$env_key" > /dev/null

    started="$(date +%s%N)"
    curl -sf -X POST "$R/startEnvironment?envKey=$env_key" > /dev/null
    sleep "$SECONDS_PER_RUN"
    curl -sf -X POST "$R/stopEnvironment?envKey=$env_key" > /dev/null
    stopped="$(date +%s%N)"

    curl -sf "$R/observatory/metrics?envKey=$env_key" > "$OUT/metrics-delay$delay.json"
    curl -sf -X POST "$R/dropEnvironment?envKey=$env_key" > /dev/null

    ticks="$(sed -E 's/.*"ticks":([0-9]+).*/\1/' "$OUT/metrics-delay$delay.json")"
    seconds="$(awk -v a="$started" -v b="$stopped" 'BEGIN { printf "%.3f", (b - a) / 1e9 }')"
    rate="$(awk -v t="$ticks" -v s="$seconds" 'BEGIN { printf "%.2f", t / s }')"
    echo "delay=${delay}ms ticks=$ticks seconds=$seconds ticksPerSecond=$rate" | tee -a "$OUT/summary.txt"
done
