#!/bin/bash
# 快速验证:排除一切 gradle/loom 缓存,确认 runServer 跑的是含 food 的最新 class。
# 只看 food 是否被 verify 识别(不等完整跑完)。
set -u
cd "$(dirname "$0")/.." || exit 1
LOG=/tmp/mc_quickcheck.log
FIFO=/tmp/mc_quickcheck.fifo
: > "$LOG"
echo "[qc] killing gradle daemons + wiping build (排除一切缓存)"
pkill -9 -f "GradleDaemon" 2>/dev/null
pkill -9 -f "runServer" 2>/dev/null
pkill -9 -f "loom-cache/launch" 2>/dev/null
sleep 1
rm -rf build 2>/dev/null
rm -f run/world/aibot/bots.json 2>/dev/null
rm -f "$FIFO"; mkfifo "$FIFO"
sleep 100000 > "$FIFO" & HOLDER=$!

# 一条 invocation:clean + runServer,全量编译(无增量 stale)+ 启动
./gradlew --no-daemon --console=plain clean runServer < "$FIFO" >> "$LOG" 2>&1 & SRV=$!
echo "[qc] server pid=$SRV, building+booting ..."
READY=0
for i in $(seq 1 600); do
  grep -q 'Done (' "$LOG" 2>/dev/null && { READY=1; echo "[qc] READY at ${i}s"; break; }
  kill -0 "$SRV" 2>/dev/null || { echo "[qc] SERVER DIED"; tail -15 "$LOG"; break; }
  sleep 1
done

if [ "$READY" = 1 ]; then
  sleep 2
  echo "aibot spawn QCBot assistant" > "$FIFO"; sleep 5
  echo "aibot verify food" > "$FIFO"; sleep 15
fi

echo "[qc] ===== food 是否被识别 ====="
if grep -q "unknown feature" "$LOG" 2>/dev/null; then
  echo "[qc] RESULT = STILL_UNKNOWN (food 没进运行 class)"
elif grep -qE "goal_plan.*Food|AIBot Verify.*food|step=.*猎|step=.*采集" "$LOG" 2>/dev/null; then
  echo "[qc] RESULT = FOOD_RECOGNIZED (verify food 开跑了!)"
else
  echo "[qc] RESULT = INCONCLUSIVE"
fi
grep -E "unknown feature|goal_plan|AIBot Verify" "$LOG" 2>/dev/null | tail -4

echo "stop" > "$FIFO"; sleep 8
kill "$SRV" 2>/dev/null; kill "$HOLDER" 2>/dev/null; rm -f "$FIFO"
echo "[qc] DONE"
