#!/usr/bin/env bash
# RAM A/B: ZGC vs G1 vs G1+CompactObjectHeaders, ELASTIC heap (low Xms so RSS tracks the working set).
# Reproduces the ZGC->G1 ~48% RAM finding. Compare PSS (honest resident) + NMT (honest committed).
# Run inside the java_25 container with /root mounted; needs /root/victus-test.jar.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
COMMON="-Xms512M -Xmx2048M -XX:+PerfDisableSharedMem"
G1="-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:G1HeapRegionSize=8M"
echo "=== Victus RAM GC A/B (elastic heap; PSS + NMT are the honest metrics) ==="
bash "$HERE/measure.sh" zgc   "$COMMON -XX:+UseZGC -XX:+ZGenerational"
bash "$HERE/measure.sh" g1    "$COMMON $G1"
bash "$HERE/measure.sh" g1coh "$COMMON $G1 -XX:+UseCompactObjectHeaders -XX:+UseStringDeduplication -XX:TrimNativeHeapInterval=5000"
echo "=== done — lower PSS/NMT is better; expect g1 << zgc, g1coh <= g1 ==="
