#!/usr/bin/env bash
# Core measurement primitive. Boots the engine in a throwaway dir with the given JVM flags, warms up,
# runs a GC, prints honest RAM/CPU/heap/error figures, then GRACEFULLY stops (no killed-boot corruption).
# Usage: bash measure.sh "<label>" "<jvm-flags>"
#   JAR env var overrides the jar path (default /root/victus-test.jar).
set -u
LABEL="${1:?usage: measure.sh <label> <jvm-flags>}"; shift
FLAGS="${*:?need jvm flags}"
JAR="${JAR:-/root/victus-test.jar}"
D="/root/bench-run-$LABEL"
rm -rf "$D"; mkdir -p "$D"; cp "$JAR" "$D/victus.jar"; echo "eula=true" > "$D/eula.txt"
cd "$D"
mkfifo /tmp/in-$LABEL 2>/dev/null
java $FLAGS -XX:NativeMemoryTracking=summary -jar victus.jar --nogui < /tmp/in-$LABEL > "$D/boot.log" 2>&1 &
PID=$!
exec 9>/tmp/in-$LABEL   # hold the fifo open
DONE=0
for i in $(seq 1 90); do
  grep -q "Done (" "$D/boot.log" 2>/dev/null && { DONE=$i; break; }
  kill -0 $PID 2>/dev/null || { echo "[$LABEL] PROCESS DIED at ${i}s"; grep -iE 'ERROR|Exception' "$D/boot.log" | head -3; exit 1; }
  sleep 1
done
sleep 18                      # warm up / let chunks settle
jcmd $PID GC.run >/dev/null 2>&1; sleep 3
PSS=$(awk '/^Pss:/{print $2}' /proc/$PID/smaps_rollup 2>/dev/null)
RSS=$(awk '/VmRSS/{print $2}' /proc/$PID/status 2>/dev/null)
NMT=$(jcmd $PID VM.native_memory summary 2>/dev/null | awk -F'committed=' '/Total: reserved/{print $2}' | awk -F'KB' '{print $1}')
HEAP=$(jcmd $PID GC.heap_info 2>/dev/null | grep -oE 'used [0-9]+[KM]' | head -1)
T1=$(awk '{print $14+$15}' /proc/$PID/stat 2>/dev/null); sleep 3; T2=$(awk '{print $14+$15}' /proc/$PID/stat 2>/dev/null)
HZ=$(getconf CLK_TCK); CPU=$(awk "BEGIN{printf \"%.1f\", ($T2-$T1)/$HZ/3*100}")
printf 'RESULT %-8s bootDone=%ss  PSS=%sKB  VmRSS=%sKB  NMTcommitted=%sKB  heap=%s  cpu=%s%%\n' \
  "$LABEL" "$DONE" "${PSS:-?}" "${RSS:-?}" "${NMT:-?}" "${HEAP:-?}" "$CPU"
ERR=$(grep -icE 'ERROR|Exception|SEVERE' "$D/boot.log" 2>/dev/null)
echo "  errors-in-log: ${ERR:-0}$( [ "${ERR:-0}" -gt 0 ] && echo ' (see '$D'/boot.log)')"
echo "stop" >&9           # graceful shutdown (saves world, releases lock)
for i in $(seq 1 40); do kill -0 $PID 2>/dev/null || break; sleep 1; done
kill -0 $PID 2>/dev/null && kill -TERM $PID
rm -f /tmp/in-$LABEL
