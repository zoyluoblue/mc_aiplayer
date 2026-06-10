#!/bin/bash
# 无头自动化测试 harness:启动 dedicated server → spawn bot → /aibot verify <feature> → 抓结果 → stop。
# verify 场景是确定性的(直接 submit Goal,不走 LLM),用来回归食物链/挖矿链等。
# 用法: bash scripts/food_test.sh [feature] [maxwait_seconds]
#   feature      默认 food;可传 all / mining / 任意 verify 用例名
#   maxwait_seconds 默认 900(verify 跑完的等待上限)
set -u
cd "$(dirname "$0")/.." || exit 1
FEATURE="${1:-food}"
MAXWAIT="${2:-900}"
LOG=/tmp/mc_foodtest.log
FIFO=/tmp/mc_foodtest.fifo
: > "$LOG"
rm -f "$FIFO"; mkfifo "$FIFO"

# 清上一局残留 bot(否则 verify 的 selectBot 可能选到旧 bot),保留 world 省重生成
rm -f run/world/aibot/bots.json 2>/dev/null
# 贴近实操(real_*)用例:重置世界再跑——固定 seed 重新生成,保证每轮从同一自然状态开始。
# 不重置的话前几轮把出生区的树/草/动物耗光(实测 96 格内无草、21t 速死),失败不可复现、互相污染。
case "$FEATURE" in
  real*)
    echo "[foodtest] realistic case: resetting world (fixed seed regen)"
    rm -rf run/world
    ;;
esac
# LLM 隔离:shell 里的 DEEPSEEK_API_KEY 会被 runServer 继承 → 大脑在 verify 失败注入时偷偷调 API
# (烧钱 + 给"确定性"测试掺入大脑干预,实测之前测试期间有 api_response)。默认 unset;
# 要测 LLM 全链(中文指令→意图→工具→执行)时 WITH_LLM=1 跑。
if [ "${WITH_LLM:-0}" != "1" ]; then
  unset DEEPSEEK_API_KEY
  echo "[foodtest] LLM isolated (set WITH_LLM=1 to enable brain)"
else
  echo "[foodtest] WITH_LLM=1: brain enabled, API will be billed"
fi
# 防 gradle 增量编译/build-cache stale(实测改了源码但 class 没真重编 → 跑旧逻辑):
# --stop 杀残留 daemon(避免 daemon VFS 缓存旧文件状态);--rerun-tasks 忽略 up-to-date;--no-build-cache 不从缓存恢复旧 class。
echo "[foodtest] compiling (clean, no-cache, rerun) ..."
./gradlew --stop >/dev/null 2>&1
./gradlew --no-daemon --rerun-tasks --no-build-cache clean classes >/dev/null 2>&1 || { echo "[foodtest] COMPILE FAILED"; exit 1; }

# 保持 FIFO 写端常开,server console 不会读到 EOF
sleep 100000 > "$FIFO" & HOLDER=$!
# 无头服务端;--no-daemon 让 stdin 直通到 console。不设 DEEPSEEK key → 大脑不调 LLM,verify 纯确定性
./gradlew --no-daemon --console=plain --no-build-cache runServer < "$FIFO" >> "$LOG" 2>&1 & SRV=$!
echo "[foodtest] server pid=$SRV feature=$FEATURE"

READY=0
for i in $(seq 1 480); do
  grep -q 'Done (' "$LOG" 2>/dev/null && { READY=1; echo "[foodtest] server READY at ${i}s"; break; }
  kill -0 "$SRV" 2>/dev/null || { echo "[foodtest] SERVER DIED EARLY"; tail -25 "$LOG"; break; }
  sleep 1
done

RESULT="NO_RESULT"
if [ "$READY" = 1 ]; then
  sleep 2
  echo "aibot spawn TestBob assistant" > "$FIFO"; sleep 5
  echo "aibot verify $FEATURE" > "$FIFO"
  echo "[foodtest] verify '$FEATURE' dispatched, waiting up to ${MAXWAIT}s ..."
  for i in $(seq 1 "$((MAXWAIT/2))"); do
    if grep -qE "\[AIBot Verify\] summary" "$LOG" 2>/dev/null; then
      echo "[foodtest] verify finished at ~$((i*2))s"; break
    fi
    kill -0 "$SRV" 2>/dev/null || { echo "[foodtest] SERVER DIED MID-VERIFY"; break; }
    sleep 2
  done
  RESULT=$(grep -E "\[AIBot Verify\] (summary|$FEATURE )" "$LOG" 2>/dev/null | tail -3)
fi

echo "[foodtest] stopping server"
echo "stop" > "$FIFO"; sleep 12
kill "$SRV" 2>/dev/null; kill "$HOLDER" 2>/dev/null
rm -f "$FIFO"

echo "================= TEST RESULT ($FEATURE) ================="
echo "${RESULT:-NO_RESULT}"
echo "=========================================================="
# 每轮日志存档(防下一轮覆盖诊断证据)
ARCHIVE="/tmp/mc_test_${FEATURE}_$(date +%H%M%S).log"
cp "$LOG" "$ARCHIVE" 2>/dev/null
echo "[foodtest] full server log: $LOG (archived: $ARCHIVE)"
