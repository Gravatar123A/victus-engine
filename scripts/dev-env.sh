#!/usr/bin/env bash
# Source me:  source scripts/dev-env.sh
# Machine-specific build environment for the Windows dev box. NOT needed on Linux CI.
# Fixes two local gotchas discovered while bootstrapping the build:
#   1. C: drive is full  -> redirect all temp + Gradle caches onto E:
#   2. Avast intercepts TLS -> Java/Gradle must trust the Windows cert store, or every
#      dependency/plugin download fails (git/curl work via the Windows store; Java does not by default)
# IMPORTANT: use Windows-style paths (E:/...) for anything a Windows JVM reads (java.io.tmpdir),
# NOT MINGW paths (/e/...) — the JVM mangles the latter.

set -a

VICTUS_TMP='E:/victus-tmp/nodetmp'
export TMP="$VICTUS_TMP" TEMP="$VICTUS_TMP" TMPDIR="$VICTUS_TMP"
export GRADLE_USER_HOME='E:/victus-tmp/gradle-home'
# -Djava.io.tmpdir keeps JVM temp off C:; Windows-ROOT lets Java trust the Windows cert store (Avast).
export GRADLE_OPTS="-Djava.io.tmpdir=E:/victus-tmp/nodetmp -Djavax.net.ssl.trustStoreType=Windows-ROOT"

# Prefer the wrapper if present, else the bootstrapped Gradle 9.4.1 on E:.
export VICTUS_GRADLE="/e/victus-tmp/gradle-dist/gradle-9.4.1/bin/gradle"

set +a

mkdir -p /e/victus-tmp/nodetmp /e/victus-tmp/gradle-home
echo "[victus] dev env ready"
echo "  TMP               = $TMP"
echo "  GRADLE_USER_HOME  = $GRADLE_USER_HOME"
echo "  GRADLE_OPTS       = $GRADLE_OPTS"
echo "  java              = $(java -version 2>&1 | head -1)"
echo "  (Gradle will auto-provision JDK 25 via the foojay resolver on first build.)"

# --- Recommended RUNTIME flags for the built server (Generational ZGC, Java 25) ---
#   java -Xms<N>G -Xmx<N>G -XX:+UseZGC -XX:+ZGenerational \
#     -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem -jar victus-engine.jar --nogui
