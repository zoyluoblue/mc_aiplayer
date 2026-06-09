#!/bin/bash
# Production 自动化测试 harness:用正式 fabric dedicated server 加载 remapped mod jar
#(绕开 loom dev classpath 加载坑),无头跑 /aibot verify <feature>,抓 PASS/FAIL。
# 依赖:run-prod/ 已由 fabric-installer 生成(fabric-server-launch.jar + server.jar)。
# 用法: bash scripts/prod_test.sh [feature] [maxwait_seconds]
set -u
cd "$(dirname "$0")/.." || exit 1
PROD=run-prod
LOG=/tmp/mc_prodtest.log
FIFO=/tmp/mc_prodtest.fifo
FEATURE="${1:-food}"
MAXWAIT="${2:-600}"
: > "$LOG"

# 1) build remapped(intermediary)mod jar —— 含最新代码
echo "[prod] building aibot jar ..."
./gradlew --no-daemon build -x test >/dev/null 2>&1 || { echo "[prod] BUILD FAILED"; exit 1; }
AIBOT_JAR=$(ls build/libs/*.jar | grep -viE "sources|dev" | head -1)

# 2) 装 mods:aibot + fabric-api(都用 intermediary production jar)
mkdir -p "$PROD/mods"
rm -f "$PROD"/mods/*.jar
cp "$AIBOT_JAR" "$PROD/mods/aibot.jar"
# fabric-api 用官方 maven 的 production(intermediary)umbrella jar —— gradle 缓存里 find 容易选到子模块 jar
# (mod id 不是 fabric-api),导致 aibot 缺依赖。缓存到 run-prod 避免每次重下。
FABRIC_API_URL="https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.114.1+1.21.3/fabric-api-0.114.1+1.21.3.jar"
if [ ! -s "$PROD/fabric-api-cache.jar" ]; then
  echo "[prod] downloading fabric-api ..."
  curl -sL --max-time 90 -o "$PROD/fabric-api-cache.jar" "$FABRIC_API_URL"
fi
cp "$PROD/fabric-api-cache.jar" "$PROD/mods/fabric-api.jar"
echo "eula=true" > "$PROD/eula.txt"
rm -f "$PROD/world/aibot/bots.json" 2>/dev/null   # 清残留 bot
echo "[prod] mods: $(ls "$PROD/mods/")"

# 3) 启动正式 server(headless),FIFO 注入 console 命令
rm -f "$FIFO"; mkfifo "$FIFO"
sleep 100000 > "$FIFO" & HOLDER=$!
( cd "$PROD" && exec java -Xmx2G -jar fabric-server-launch.jar nogui ) < "$FIFO" >> "$LOG" 2>&1 & SRV=$!
echo "[prod] server pid=$SRV feature=$FEATURE"

READY=0
for i in $(seq 1 480); do
  grep -q 'Done (' "$LOG" 2>/dev/null && { READY=1; echo "[prod] READY at ${i}s"; break; }
  kill -0 "$SRV" 2>/dev/null || { echo "[prod] SERVER DIED EARLY"; tail -25 "$LOG"; break; }
  sleep 1
done

if [ "$READY" = 1 ]; then
  sleep 2
  echo "aibot spawn TestBob assistant" > "$FIFO"; sleep 5
  echo "aibot verify $FEATURE" > "$FIFO"
  echo "[prod] verify '$FEATURE' dispatched, waiting up to ${MAXWAIT}s ..."
  for i in $(seq 1 "$((MAXWAIT/2))"); do
    grep -qE "\[AIBot Verify\] summary" "$LOG" 2>/dev/null && { echo "[prod] finished ~$((i*2))s"; break; }
    grep -q "unknown feature" "$LOG" 2>/dev/null && { echo "[prod] UNKNOWN_FEATURE(jar没含food?)"; break; }
    kill -0 "$SRV" 2>/dev/null || { echo "[prod] SERVER DIED MID-VERIFY"; break; }
    sleep 2
  done
fi

echo "[prod] stopping"
echo "stop" > "$FIFO"; sleep 10
kill "$SRV" 2>/dev/null; kill "$HOLDER" 2>/dev/null; rm -f "$FIFO"
echo "================= PROD TEST RESULT ($FEATURE) ================="
grep -E "\[AIBot Verify\] (summary|$FEATURE )|unknown feature" "$LOG" 2>/dev/null | tail -3
echo "==============================================================="
