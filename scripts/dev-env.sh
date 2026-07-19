#!/usr/bin/env bash
# Source me:  source scripts/dev-env.sh
# Redirects all temp + Gradle caches onto E:, because the dev machine's C: drive is full (~0 bytes).
# Without this, Gradle/paperweight fail with ENOSPC while decompiling Paper.

set -a

# --- keep temp off C: ---
export VICTUS_TMP="/e/victus-tmp/victus-engine"
mkdir -p "$VICTUS_TMP"
export TMPDIR="$VICTUS_TMP"
export TMP="$VICTUS_TMP"
export TEMP="$VICTUS_TMP"

# --- keep the (large) Gradle cache + wrapper dists off C: ---
export GRADLE_USER_HOME="/e/victus-tmp/gradle-home"
mkdir -p "$GRADLE_USER_HOME"

# --- JVM: point java.io.tmpdir at E: too, modest heap ---
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3G -Djava.io.tmpdir=$VICTUS_TMP"

set +a

echo "[victus] dev env ready"
echo "  TMPDIR            = $TMPDIR"
echo "  GRADLE_USER_HOME  = $GRADLE_USER_HOME"
echo "  java              = $(java -version 2>&1 | head -1)"

# --- Reference: recommended RUNTIME flags for the built server (Generational ZGC, Java 21+) ---
# Replaces the legacy Aikar/G1 flag set. Validate on a pilot node before fleet rollout.
#   java -Xms<N>G -Xmx<N>G \
#     -XX:+UseZGC -XX:+ZGenerational \
#     -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem \
#     -Dusing.aikars.flags=false \
#     -jar victus-engine.jar --nogui
