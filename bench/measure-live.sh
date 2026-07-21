#!/usr/bin/env bash
# Non-invasively measure a RUNNING server by PID (e.g. the real wings process) via /proc.
# Usage: bash measure-live.sh <pid>   (run on the host, not in a container)
set -u
P="${1:?usage: measure-live.sh <pid>}"
kill -0 "$P" 2>/dev/null || { echo "no such pid $P"; exit 1; }
echo "=== live server PID $P ==="
ps -o pid,etime,args -p "$P" 2>/dev/null | tail -1 | cut -c1-140
echo "PSS:   $(awk '/^Pss:/{print $2" "$3}' /proc/$P/smaps_rollup 2>/dev/null)"
echo "VmRSS: $(awk '/VmRSS/{print $2" "$3}' /proc/$P/status 2>/dev/null)   VmHWM(peak): $(awk '/VmHWM/{print $2" "$3}' /proc/$P/status 2>/dev/null)"
T1=$(awk '{print $14+$15}' /proc/$P/stat); sleep 3; T2=$(awk '{print $14+$15}' /proc/$P/stat)
HZ=$(getconf CLK_TCK); awk "BEGIN{printf \"CPU: %.1f%% of one core (3s sample)\n\", ($T2-$T1)/$HZ/3*100}"
# cgroup memory (what the panel widget reports) — resolve the process's scope
SCOPE=$(awk -F/ '/0::/{print $NF}' /proc/$P/cgroup 2>/dev/null)
for base in /sys/fs/cgroup/system.slice/$SCOPE /sys/fs/cgroup/$SCOPE; do
  [ -f "$base/memory.current" ] && awk "{printf \"cgroup memory.current (panel RAM): %.0f MB\n\", \$1/1024/1024}" "$base/memory.current" && break
done
