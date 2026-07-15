#!/bin/bash
# 开播一键 SOP:自检整套链路(桥/bot/浮层/配置/登录态/TTS/大脑 key)→ 后台拉起 watcher → 打印人工步骤清单。
# 用法:
#   bash scripts/live_up.sh                # 自检 + 起 watcher(房间号:~/.aibot/room_id > 默认)
#   bash scripts/live_up.sh 925175887814   # 指定房间号
#   bash scripts/live_up.sh --check        # 只自检不起 watcher
#   bash scripts/live_up.sh --no-watcher   # 同 --check
set -u

BRIDGE="http://127.0.0.1:8787"
DEFAULT_ROOM="925175887814"
ROOM_FILE="$HOME/.aibot/room_id"
PID_FILE="$HOME/.aibot/watcher.pid"
LOG_FILE="$HOME/.aibot/watcher.log"
INST_A="$HOME/Library/Application Support/PrismLauncher/instances/1.21.3-DeepSeek/minecraft/config"
INST_B="$HOME/Library/Application Support/PrismLauncher/instances/1.21.3(1)/minecraft/config"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

CHECK_ONLY=0
ROOM=""
for arg in "$@"; do
  case "$arg" in
    --check|--no-watcher) CHECK_ONLY=1 ;;
    *) ROOM="$arg" ;;
  esac
done
if [ -z "$ROOM" ] && [ -f "$ROOM_FILE" ]; then ROOM="$(cat "$ROOM_FILE" | tr -d '[:space:]')"; fi
if [ -z "$ROOM" ]; then ROOM="$DEFAULT_ROOM"; fi

PASS=0; WARN=0; FAIL=0
ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
warn() { echo "  ⚠️  $1"; WARN=$((WARN+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

echo "== 开播自检(房间号 $ROOM)=="

# [1] 桥在线(桥随 1.21.3-DeepSeek 实例进世界启动)
if curl -sf --max-time 2 "$BRIDGE/health" >/dev/null 2>&1; then
  ok "桥在线 $BRIDGE"
else
  bad "桥不通 $BRIDGE —— 先开 1.21.3-DeepSeek 客户端进世界(开局域网)"
fi

# [2] bot 已 spawn(/status 里有 bot 字段)
STATUS_JSON="$(curl -sf --max-time 2 "$BRIDGE/status" 2>/dev/null || true)"
BOT_NAME="$(printf '%s' "$STATUS_JSON" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); print(d.get("bot",{}).get("name",""))
except Exception: print("")' 2>/dev/null)"
if [ -n "$BOT_NAME" ]; then
  ok "bot 已 spawn:$BOT_NAME"
else
  bad "bot 未 spawn —— 游戏内执行 /aibot spawn DeepSeek"
fi

# [3] 浮层可用
if curl -sf --max-time 2 -o /dev/null "$BRIDGE/overlay" 2>/dev/null; then
  ok "OBS 浮层 $BRIDGE/overlay"
else
  bad "浮层不通(overlayEnabled=false 需改配置后重启客户端)"
fi

# [4] 礼物配置(直读服务端实例的 aibot_gifts.json)
GIFTS_JSON="$INST_A/aibot_gifts.json"
if [ -f "$GIFTS_JSON" ]; then
  CFG_SUMMARY="$(python3 - "$GIFTS_JSON" "$BOT_NAME" <<'PY' 2>/dev/null
import json,sys
d=json.load(open(sys.argv[1])); bot=sys.argv[2]
issues=[]
if not d.get("enabled",False): issues.append("enabled=false")
if bot and d.get("defaultBot","")!=bot: issues.append(f"defaultBot={d.get('defaultBot')}≠{bot}")
if d.get("dedupMs",1500)>=2000: issues.append("dedupMs≥2000(会误杀连击聚合批次)")
flags=" ".join(f"{k}={'开' if d.get(k,True) else '关'}" for k in ("danmakuEnabled","followThanksEnabled","idleEnabled"))
print(("BAD:" + ";".join(issues)) if issues else ("OK:"+flags))
PY
)"
  case "$CFG_SUMMARY" in
    OK:*) ok "礼物配置正常(${CFG_SUMMARY#OK:})" ;;
    BAD:*) bad "礼物配置问题:${CFG_SUMMARY#BAD:}" ;;
    *) warn "礼物配置无法解析($GIFTS_JSON)" ;;
  esac
else
  warn "找不到 $GIFTS_JSON(首次进世界会自动生成)"
fi

# [5] 抖音登录态
if [ -d "$HOME/.aibot/douyin-profile" ] && [ -n "$(ls -A "$HOME/.aibot/douyin-profile" 2>/dev/null)" ]; then
  ok "抖音登录态存在(失效只能开播后从心跳发现:计数全 0 或提示登录页)"
else
  bad "无登录态 —— 先跑一次 python3 scripts/find_room.py 扫码登录"
fi

# [6] TTS 配置(两实例任一开着即可,通常主视角客户端开)
TTS_OK=0
for CFG in "$INST_A/aibot_voice.json" "$INST_B/aibot_voice.json"; do
  if [ -f "$CFG" ] && python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("enabled") and d.get("apiKey") else 1)' "$CFG" 2>/dev/null; then
    ok "TTS 已开:$(echo "$CFG" | sed 's|.*instances/||;s|/minecraft.*||')"
    TTS_OK=1
  fi
done
[ "$TTS_OK" = "0" ] && warn "两实例 TTS 都没开(aibot_voice.json enabled+apiKey)——观众听不到 bot 说话"

# [7] 大脑 key
BRAIN_OK=0
for CFG in "$INST_A/aibot.json" "$INST_B/aibot.json"; do
  if [ -f "$CFG" ] && python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("deepseek",{}).get("apiKey") else 1)' "$CFG" 2>/dev/null; then
    BRAIN_OK=1
  fi
done
if [ "$BRAIN_OK" = "1" ]; then ok "大脑 apiKey 已配"; else warn "aibot.json 无 apiKey(或走 DEEPSEEK_API_KEY 环境变量,确认客户端启动环境带了)"; fi

# [8] watcher 依赖
if python3 -c "import playwright" 2>/dev/null; then
  ok "playwright 可用"
else
  bad "缺 playwright —— pip3 install playwright && playwright install chromium"
fi

echo "== 自检:✅$PASS ⚠️$WARN ❌$FAIL =="

if [ "$CHECK_ONLY" = "1" ]; then exit $([ "$FAIL" = "0" ] && echo 0 || echo 1); fi
if [ "$FAIL" != "0" ]; then
  echo "有 ❌ 项,先修复再开播(只想看自检结果用 --check)。"
  exit 1
fi

# 起 watcher(幂等:已有活进程不重复起)
mkdir -p "$HOME/.aibot"
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "watcher 已在跑(pid $(cat "$PID_FILE")),不重复启动。日志:tail -f $LOG_FILE"
else
  echo "$ROOM" > "$ROOM_FILE"
  nohup python3 "$SCRIPT_DIR/douyin_gift_watch.py" "$ROOM" --headless >> "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "watcher 已启动(pid $(cat "$PID_FILE"),房间 $ROOM)。日志:tail -f $LOG_FILE"
fi

cat <<'EOF'

== 人工步骤(照做)==
 1. 1.21.3-DeepSeek 客户端进档,开局域网(桥/浮层随世界已起,自检已确认)
 2. 游戏内 /aibot spawn DeepSeek(自检已确认的话跳过)
 3. 1.21.3(1) 客户端加入局域网 → /aibot camera bind <相机号> DeepSeek(F5 别按 F1)
 4. OBS:游戏画面 + 浏览器源 http://127.0.0.1:8787/overlay(1920×1080 透明)
 5. 抖音 App 开播,核对房间号与本脚本一致
 6. tail -f ~/.aibot/watcher.log 等第一条 [心跳];首播建议先 --dry-run 校准再正式跑
 7. 礼物/弹幕规则热调:http://127.0.0.1:8787/admin(或桌面"礼物面板")
 8. 下播:bash scripts/live_down.sh
EOF
