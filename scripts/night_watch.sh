#!/bin/bash
# 夜间值守(④):无人值班的回归长跑——你睡觉,它跑测试出报告;QA 这个班由机器值。
# 轮转跑全部套件 × 多 seed,失败摘要归档到 reports/night_<日期>.md,起床一眼看完。
# 用法: bash scripts/night_watch.sh [轮数,默认3]   (cron 例: 0 2 * * * cd <repo> && bash scripts/night_watch.sh)
set -u
cd "$(dirname "$0")/.." || exit 1
ROUNDS="${1:-3}"
SEEDS=(20260610 3000 777)            # 地狱关(云杉悬崖湖)/平原/随机第三地形
SUITES=(geo_suite mining food_suite assistant_suite material_suite extreme_suite nav_suite)
REPORT="reports/night_$(date +%Y%m%d_%H%M).md"
mkdir -p reports
{
echo "# 夜间值守报告 $(date '+%F %T')"
echo ""
for round in $(seq 1 "$ROUNDS"); do
  SEED="${SEEDS[$(( (round-1) % ${#SEEDS[@]} ))]}"
  echo "## 第 $round 轮 (seed=$SEED)"
  for suite in "${SUITES[@]}"; do
    # real/geo 等用例由 food_test.sh 自管世界重置;SEED 仅对 real* 生效,其余套件用当前世界
    line=$(SEED="$SEED" bash scripts/food_test.sh "$suite" 2400 2>/dev/null | grep -E "\[AIBot Verify\] summary" | tail -1)
    summary="${line#*summary }"
    echo "- $suite: ${summary:-NO_RESULT(server异常,查 /tmp/mc_test_${suite}_*.log)}"
    # 失败用例的存档日志路径附在报告里,起床直接取证
    if echo "$summary" | grep -q "FAIL"; then
      echo "  - 日志: $(ls -t /tmp/mc_test_${suite}_*.log 2>/dev/null | head -1)"
    fi
  done
  echo ""
done
echo "_全部存档日志: /tmp/mc_test_*.log ;诊断事件 grep: stall_dump / approach_rejected / timeout_diag_"
} | tee "$REPORT"
echo "[night_watch] report: $REPORT"
