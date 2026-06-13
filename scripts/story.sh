#!/bin/bash
# 真实故事 harness:把"产品本身"当测试——真实种子世界 + 真实对话层(中文指令→DeepSeek→工具→执行),
# 只看一个指标:说一句话 → 活着办成 → 耗时合理。这是离用户愿景("对话让他帮我采矿/觅食/挖钻")
# 最近的度量,补上实验室套件测不到的"真实地形 × 真实大脑"那一层。
#
#   ⚠ 计费:走真实 DeepSeek API,本脚本强制 WITH_LLM=1。**会产生 API 费用**,故不默认、不进 gate。
#   用法: DEEPSEEK_API_KEY=sk-xxx bash scripts/story.sh            (跑全部故事 × 多 seed)
#          DEEPSEEK_API_KEY=sk-xxx bash scripts/story.sh llm_diamond  (单故事最省钱)
#   产出: reports/story_state.tsv(每行: 故事 seed 结果 summary);可续跑(被杀重启跳过已完成)。
#   分离跑: nohup bash scripts/story.sh >/tmp/story.out 2>&1 &     (聊天侧只读 state)
set -u
cd "$(dirname "$0")/.." || exit 1

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[story] 需要 DEEPSEEK_API_KEY(真实对话层走 API,会计费)。用法见脚本头。"
  exit 2
fi
export WITH_LLM=1

LOCK=/tmp/story.lock
STATE=reports/story_state.tsv
mkdir -p reports
mkdir "$LOCK" 2>/dev/null || { echo "[story] another instance running, exit."; exit 0; }
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
[ -f "$STATE" ] || printf "story\tseed\tresult\tsummary\n" > "$STATE"

# 故事集:覆盖愿景四面(移动/觅食/采矿/深层矿)。可传参只跑指定故事。
STORIES=("${@:-llm_move llm_food llm_iron llm_diamond}")
# 真实地形多 seed(地狱关/平原)——同一故事跨地形都得活着办成才算真鲁棒。
SEEDS=(20260610 3000)

run_story() {
  local story="$1" seed="$2"
  if awk -F'\t' -v s="$story" -v d="$seed" '$1==s&&$2==d{r=$3} END{exit (r=="PASS"||r=="FAIL")?0:1}' "$STATE" 2>/dev/null; then
    echo "[story] $story@$seed already run, skip."; return
  fi
  echo "[story] === $story @ seed=$seed ==="
  local out summary
  out=$(SEED="$seed" bash scripts/food_test.sh "$story" 6000 2>&1)
  summary=$(echo "$out" | grep -E "\[AIBot Verify\] summary" | tail -1)
  summary="${summary#*summary }"
  [ -z "$summary" ] && summary="NO_SUMMARY(server异常/未配key,见 /tmp/mc_test_${story}_*.log)"
  local result="FAIL"; echo "$summary" | grep -q "${story}=PASS" && result="PASS"
  printf "%s\t%s\t%s\t%s\n" "$story" "$seed" "$result" "$summary" >> "$STATE"
  echo "[story] $story@$seed -> $result"
}

command -v caffeinate >/dev/null && caffeinate -is -w $$ &
for story in ${STORIES[@]}; do
  for seed in "${SEEDS[@]}"; do
    run_story "$story" "$seed"
  done
done
echo "[story] ALL STORIES COMPLETE $(date '+%F %T')"
echo "ALLDONE" >> "$STATE"
