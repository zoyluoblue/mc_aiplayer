#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8787}"
USER_NAME="${1:-测试观众}"
GIFT_NAME="${2:-玫瑰}"
COUNT="${3:-1}"
BOT_NAME="${4:-Bob}"

curl -sS -X POST "http://${HOST}:${PORT}/gift" \
  -H 'Content-Type: application/json' \
  -d "{\"user\":\"${USER_NAME}\",\"gift\":\"${GIFT_NAME}\",\"count\":${COUNT},\"bot\":\"${BOT_NAME}\"}"
echo
