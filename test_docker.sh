#!/usr/bin/env bash
# karpfen-runtime/test_docker.sh – Run Gradle tasks of the runtime in Docker
#
# Runs Gradle tasks of karpfen-runtime inside a throwaway container that has the
# Python of the runtime image, so that the tests with the JUnit tag "python"
# (and anything else that needs Python) never run Python on the host.
#
# Usage:
#   ./test_docker.sh [--collect "DIR ..."] [GRADLE_ARGUMENT ...]
#   ./test_docker.sh --cleanup [--keep-cache]
#
#   GRADLE_ARGUMENT  Tasks and options for ./gradlew inside the container.
#                    Default: :test :execution-engine:test --continue
#                    (the whole test suite of both modules, Python included).
#   --collect DIRS   Folders below build/ that come back to the checkout after
#                    the run, separated by spaces.
#                    Default: "test-results reports"
#   --cleanup        Removes every container, image and volume with the label
#                    io.karpfen.runtime.test=true and lists what is left.
#   --keep-cache     With --cleanup: keeps the volume with Gradle's downloads.
#
# Examples:
#   ./test_docker.sh
#   ./test_docker.sh :execution-engine:test --tests 'io.karpfen.io.karpfen.exec.MacroProcessorTest'
#   ./test_docker.sh --collect "reports/benchmarks" :execution-engine:benchmark
#   ./test_docker.sh --cleanup
#
# What a run does:
#   1. Builds the image karpfen-runtime-test from the stage "test" of the
#      Dockerfile: the stage "python" of the runtime image plus a JDK.
#   2. Creates the volume karpfen-runtime-test-gradle for Gradle's downloads
#      (wrapper, dependencies) once; later runs reuse it.
#   3. Starts a container that mounts the checkout read-only, copies the sources
#      without build folders and Gradle state into the container and runs
#      ./gradlew there with --max-workers=3.  The host's build folders, .gradle
#      folders and the generated files of karpfen-dsl-tools stay untouched, and
#      every run starts from the same clean copy.
#   4. Copies the folders of --collect of every Gradle project back into
#      <project>/build/container/ of the checkout, e.g.
#      execution-engine/build/container/reports/tests/test/index.html.
#      The previous contents of build/container/ are replaced.
#   5. Removes the container, also after Ctrl+C or a failure.
#
# The script needs nothing on the host but Docker.  It publishes no port.
# It ends with the exit status of Gradle.
#
# Environment:
#   KARPFEN_TEST_WORKERS  Gradle workers in the container (default 3)
#   KARPFEN_TEST_MEMORY   Memory limit of the container (default 6g)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

LABEL="io.karpfen.runtime.test=true"
IMAGE="karpfen-runtime-test"
VOLUME="karpfen-runtime-test-gradle"
CONTAINER="karpfen-runtime-test-$$"
PROJECTS=(. execution-engine karpfen-dsl-tools)
WORKERS="${KARPFEN_TEST_WORKERS:-3}"
MEMORY="${KARPFEN_TEST_MEMORY:-6g}"

# ── Cleanup ------------------------------------------------------------------
cleanup() {
    local keep_cache="$1"
    echo "=== Karpfen Runtime – Docker test cleanup ==="
    local ids
    ids="$(docker ps -aq --filter "label=$LABEL")"
    if [ -n "$ids" ]; then
        # shellcheck disable=SC2086
        docker rm -f $ids > /dev/null
        echo "  Removed containers: $(echo "$ids" | wc -l | tr -d ' ')"
    fi
    ids="$(docker images -q --filter "label=$LABEL" | sort -u)"
    if [ -n "$ids" ]; then
        # shellcheck disable=SC2086
        docker rmi -f $ids > /dev/null
        echo "  Removed images: $(echo "$ids" | wc -l | tr -d ' ')"
    fi
    if [ "$keep_cache" = "no" ]; then
        ids="$(docker volume ls -q --filter "label=$LABEL")"
        if [ -n "$ids" ]; then
            # shellcheck disable=SC2086
            docker volume rm $ids > /dev/null
            echo "  Removed volumes: $(echo "$ids" | wc -l | tr -d ' ')"
        fi
    fi
    echo ""
    echo "  Left with the label $LABEL:"
    echo "  containers: $(docker ps -aq --filter "label=$LABEL" | wc -l | tr -d ' ')"
    echo "  images    : $(docker images -q --filter "label=$LABEL" | sort -u | wc -l | tr -d ' ')"
    echo "  volumes   : $(docker volume ls -q --filter "label=$LABEL" | tr '\n' ' ')"
}

if [ "${1:-}" = "--cleanup" ]; then
    if [ "${2:-}" = "--keep-cache" ]; then cleanup yes; else cleanup no; fi
    exit 0
fi

# ── Arguments ----------------------------------------------------------------
COLLECT="test-results reports"
if [ "${1:-}" = "--collect" ]; then
    COLLECT="${2:?--collect needs a list of folders below build/}"
    shift 2
fi
if [ "$#" -eq 0 ]; then
    set -- :test :execution-engine:test --continue
fi

echo "=== Karpfen Runtime – Gradle in Docker ==="
echo ""
echo "  Sources : $SCRIPT_DIR  (copied into the container, read-only mount)"
echo "  Gradle  : ./gradlew $*  (--max-workers=$WORKERS)"
echo "  Results : <project>/build/container/{${COLLECT// /,}}"
echo ""

# ── Build image ---------------------------------------------------------------
echo "[1/3] Building Docker image $IMAGE (stage test of the Dockerfile) ..."
docker build --quiet --target test --label "$LABEL" -t "$IMAGE" "$SCRIPT_DIR" > /dev/null
echo "      Image built"
echo ""

# ── Gradle cache volume ------------------------------------------------------
if docker volume inspect "$VOLUME" > /dev/null 2>&1; then
    if [ -z "$(docker volume ls -q --filter "name=^${VOLUME}$" --filter "label=$LABEL")" ]; then
        echo "The volume $VOLUME exists without the label $LABEL; it is not touched." >&2
        exit 1
    fi
else
    docker volume create --label "$LABEL" "$VOLUME" > /dev/null
fi

# ── Result folders -------------------------------------------------------------
MOUNTS=()
for project in "${PROJECTS[@]}"; do
    out="$SCRIPT_DIR/$project/build/container"
    rm -rf "$out"
    mkdir -p "$out"
    MOUNTS+=(-v "$out:/out/$project")
done

# ── Run container ----------------------------------------------------------------
# The container is removed by --rm when it ends, and by the trap when the
# script is interrupted before docker run returns.
trap 'docker rm -f "$CONTAINER" > /dev/null 2>&1 || true' EXIT INT TERM

INNER='
set -uo pipefail
excludes=(--exclude=.gradle --exclude=.kotlin --exclude=.idea --exclude=.git --exclude=logs)
for project in $PROJECTS; do
    if [ "$project" = "." ]; then excludes+=(--exclude=./build); else excludes+=("--exclude=./$project/build"); fi
done
tar -C /src "${excludes[@]}" -cf - . | tar -C /work -xf -
cd /work
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
./gradlew --no-daemon --max-workers="$WORKERS" -Dorg.gradle.jvmargs=-Xmx1536m \
    -Porg.gradle.java.installations.auto-download=false "$@"
status=$?
for project in $PROJECTS; do
    for folder in $COLLECT; do
        if [ -e "/work/$project/build/$folder" ]; then
            mkdir -p "/out/$project/$(dirname "$folder")"
            cp -a "/work/$project/build/$folder" "/out/$project/$folder"
        fi
    done
done
exit $status
'

echo "[2/3] Running Gradle in container $CONTAINER ..."
echo ""
set +e
docker run --rm --init \
    --name "$CONTAINER" \
    --label "$LABEL" \
    --memory "$MEMORY" \
    -v "$SCRIPT_DIR:/src:ro" \
    -v "$VOLUME:/gradle" \
    "${MOUNTS[@]}" \
    -e GRADLE_USER_HOME=/gradle \
    -e WORKERS="$WORKERS" \
    -e COLLECT="$COLLECT" \
    -e PROJECTS="${PROJECTS[*]}" \
    "$IMAGE" bash -c "$INNER" bash "$@"
status=$?
set -e

echo ""
echo "[3/3] Results"
for project in "${PROJECTS[@]}"; do
    for folder in $COLLECT; do
        if [ -e "$SCRIPT_DIR/$project/build/container/$folder" ]; then
            echo "      Copied to $project/build/container/$folder"
        fi
    done
done
echo "      Gradle exit status: $status"
exit $status
