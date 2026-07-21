#!/usr/bin/env bash
# Chunk-gen A/B: fresh world, fixed seed, boot at different Moonrise worker-thread counts and time the
# spawn-area generation. NOTE: spawn-prep is a SMALL workload, so the delta is modest and noisy — this
# confirms the override takes effect and the direction; a Chunky/bot pregen harness (roadmap S6) gives
# clean chunks/s. Moonrise's default caps to ~cores/4 (1 thread on <=6-core boxes) — see RecommendedFlags.
# Run inside the java_25 container with /root mounted; needs /root/victus-test.jar.
set -u
JAR="${JAR:-/root/victus-test.jar}"
run_gen() {
  N=$1
  D="/root/bench-gen-$N"; rm -rf "$D"; mkdir -p "$D"; cp "$JAR" "$D/victus.jar"; echo "eula=true" > "$D/eula.txt"
  printf 'level-seed=victusbench\nview-distance=10\n' > "$D/server.properties"
  cd "$D"; mkfifo /tmp/gin-$N 2>/dev/null
  # both brand guesses so whichever PlatformHooks brand is active picks it up
  java -Xms1G -Xmx3G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions \
       -DPaper.WorkerThreadCount=$N -DVictus.WorkerThreadCount=$N \
       -jar victus.jar --nogui < /tmp/gin-$N > boot.log 2>&1 &
  PID=$!; exec 8>/tmp/gin-$N
  for i in $(seq 1 90); do grep -q "Done (" boot.log 2>/dev/null && break; kill -0 $PID 2>/dev/null || break; sleep 1; done
  WT=$(grep -oE 'using [0-9]+ worker threads(, [0-9]+ I/O)?' boot.log | head -1)
  PREP=$(grep -oE "Done preparing level[^(]*\([0-9.]+s\)" boot.log | grep -oE '[0-9.]+s' | head -1)
  DONE=$(grep -oE 'Done \([0-9.]+s\)' boot.log | grep -oE '[0-9.]+s' | head -1)
  echo "RESULT workers=$N  applied=[${WT:-NOT-APPLIED}]  spawn-prep=${PREP:-?}  total-boot=${DONE:-?}"
  echo "stop" >&8; for i in $(seq 1 30); do kill -0 $PID 2>/dev/null || break; sleep 1; done
  kill -0 $PID 2>/dev/null && kill -TERM $PID; rm -f /tmp/gin-$N
}
echo "=== Victus chunk-gen A/B (Moonrise worker-threads; small spawn workload — direction only) ==="
run_gen 1
run_gen 8
echo "=== done — if 'applied=[... using N ...]' shows the N you set, the override works ==="
