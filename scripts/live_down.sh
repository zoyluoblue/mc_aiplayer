#!/bin/bash
# 下播:停 watcher + 打印末次心跳统计。
set -u
PID_FILE="$HOME/.aibot/watcher.pid"
LOG_FILE="$HOME/.aibot/watcher.log"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  kill "$(cat "$PID_FILE")" 2>/dev/null
  echo "watcher(pid $(cat "$PID_FILE"))已停止。"
  rm -f "$PID_FILE"
else
  echo "watcher 不在运行。"
  rm -f "$PID_FILE"
fi

if [ -f "$LOG_FILE" ]; then
  echo "-- 末次心跳 --"
  grep "\[心跳\]" "$LOG_FILE" | tail -3
fi
echo "记得:OBS 停止推流 + 抖音 App 下播。"
