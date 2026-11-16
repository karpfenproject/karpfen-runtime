# ──────────────────────────────────────────────────────────────────────────────
# Stage 1 – builder
#
# Uses a JDK image with a reliable Gradle toolchain.  The entire Karpfen
# subproject tree is compiled here; the resulting installDist distribution is
# what we carry over to the runtime stage.
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build


# Copy the full karpfen-runtime source tree (build context = karpfen-runtime/)
COPY . .

# Build the distribution, skip tests for speed
RUN ./gradlew installDist -x test --no-daemon --quiet

# ──────────────────────────────────────────────────────────────────────────────
# Stage 2 – python  (Debian Trixie)
#
# The base of the runtime image and of the test image: the JRE and Python 3
# with the packages that EVAL blocks and macros may import.  Both images take
# Python from this one stage, so tests see the Python of the real runtime.
# ──────────────────────────────────────────────────────────────────────────────
FROM debian:trixie-slim AS python

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
           openjdk-21-jre-headless \
           python3 \
           python3-pip \
    && pip3 install --break-system-packages \
           numpy \
           scipy \
           sympy \
           pandas \
           regex \
           python-dateutil \
           chardet \
    && rm -rf /var/lib/apt/lists/*

# ──────────────────────────────────────────────────────────────────────────────
# Stage 3 – test
#
# The image of test_docker.sh: the python stage plus the JDK that Gradle needs
# and curl, with which benchmark_integration.sh drives the runtime over HTTP.
# It holds no sources; test_docker.sh copies them in for every run.  A plain
# "docker build" does not build this stage, because runtime is the last stage.
# ──────────────────────────────────────────────────────────────────────────────
FROM python AS test

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
           openjdk-21-jdk-headless \
           curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# ──────────────────────────────────────────────────────────────────────────────
# Stage 4 – runtime
#
# Minimal image: only the JRE and Python 3 are installed.
# application.conf is NOT baked in — it is bind-mounted by run_docker.sh.
# Trace logs go to /app/logs, which run_docker.sh mounts from the host so that
# log files survive container shutdown.
# ──────────────────────────────────────────────────────────────────────────────
FROM python AS runtime

WORKDIR /app

# Copy the compiled distribution from the builder stage
COPY --from=builder /build/build/install/karpfen-runtime/lib/ ./lib/
COPY --from=builder /build/build/install/karpfen-runtime/bin/ ./bin/

# /app/logs is the container-side mount point for trace log files.
# Mount a host directory here via  -v <host-path>:/app/logs  to persist traces.
VOLUME ["/app/logs"]

EXPOSE 8080

ENTRYPOINT ["./bin/karpfen-runtime"]
