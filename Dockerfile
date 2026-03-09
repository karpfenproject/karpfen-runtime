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
# Stage 2 – runtime  (Debian Trixie)
#
# Minimal image: only the JRE and Python 3 are installed.
# application.conf is NOT baked in — it is bind-mounted by run_docker.sh.
# Trace logs go to /app/logs, which run_docker.sh mounts from the host so that
# log files survive container shutdown.
# ──────────────────────────────────────────────────────────────────────────────
FROM debian:trixie-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
           openjdk-21-jre-headless \
           python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the compiled distribution from the builder stage
COPY --from=builder /build/build/install/karpfen-runtime/lib/ ./lib/
COPY --from=builder /build/build/install/karpfen-runtime/bin/ ./bin/

# /app/logs is the container-side mount point for trace log files.
# Mount a host directory here via  -v <host-path>:/app/logs  to persist traces.
VOLUME ["/app/logs"]

EXPOSE 8080

ENTRYPOINT ["./bin/karpfen-runtime"]
