#!/bin/bash
# 自动化测试驱动(根治"被杀/起不来"):一个分离、串行、带锁、可续跑的进程跑完所有轮。
#   根因:food_test.sh 启动即 ./gradlew --stop + 单台 MC 独占端口 → 任意两次重叠互杀;
#         过去用聊天循环逐轮发后台任务,wakeup/用户消息/后台任务三者重叠 → 互杀+轮被回收。
#   解法:① 串行(本脚本内一轮接一轮,绝不并发)② mkdir 原子锁(重复启动直接退,不互杀)
#         ③ 可续跑(每轮结果落 state 文件,被杀后重启跳过已完成轮——中断变无害)。
# 用法: nohup bash scripts/auto30.sh </dev/null >/tmp/auto30.out 2>&1 &   (聊天侧只读 state 文件)
set -u
cd "$(dirname "$0")/.." || exit 1

LOCK=/tmp/auto30.lock
STATE=reports/auto30_state.tsv
mkdir -p reports
# 原子锁:mkdir 失败=已有实例在跑,直接退(幂等,重复启动无害,不互杀)
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "[auto30] another instance running (lock $LOCK), exit."
  exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
[ -f "$STATE" ] || printf "round\tfeature\tresult\tsummary\n" > "$STATE"

# 轮次表: 标签 | feature参数 | 超时秒 | seed(空=当前世界)
# 设计: geo/mining 全矩阵各一遍(画布确定性) → real 双 seed(验 EXPLORE+R2 真实战力)
#        → food/nav/assistant/material 各一遍 → geo/mining/real 再一轮(flaky 检测)。
ROUNDS=(
  "14|geo_vertical+geo_slope+geo_overhang+geo_wall+geo_pocket+geo_deep+geo_lava|1500|"
  "15|geo_gravel+geo_fullinv+geo_rich+geo_water+geo_bonus+geo_flow+geo_lake+geo_guard|1800|"
  "16|dig_down+mine_exposed+ore_dig_buried+mine_to_iron+mine_buried_iron+mine_iron_pocket|1800|"
  "17|mine_with_mob+mine_iron_from_scratch+achieve_iron_ingot+achieve_gold_ingot+achieve_obsidian|1800|"
  "18|achieve_iron_pickaxe+achieve_diamond+geo_recover+geo_stockpile+geo_resume+explore_wood|2400|"
  "19|real_wood|2400|20260610"
  "20|real_iron|3000|20260610"
  "21|real_diamond|3600|20260610"
  "22|real_wood+real_food+real_wheat|3000|777"
  "23|real_iron+real_diamond|3600|777"
  "24|food+food_full+farm+farm_wheat_from_scratch|1800|"
  "25|food_farm+forage+farm_irrigate+cake|1800|"
  "26|nav_buried_escape+nav_unreachable+real_nav_far+nav_pillar_out|1800|"
  "27|geo_vertical+geo_slope+geo_overhang+geo_wall+geo_pocket+geo_deep+geo_lava|1500|"
  "28|geo_gravel+geo_fullinv+geo_rich+geo_water+geo_bonus+geo_flow+geo_lake+geo_guard|1800|"
  "29|dig_down+mine_exposed+ore_dig_buried+mine_to_iron+mine_buried_iron+mine_iron_pocket+mine_with_mob+mine_iron_from_scratch|2400|"
  "30|achieve_iron_ingot+achieve_gold_ingot+achieve_obsidian+achieve_iron_pickaxe+achieve_diamond+geo_recover+geo_stockpile+geo_resume+explore_wood|2700|"
)

run_one() {
  local label="$1" feature="$2" timeout="$3" seed="$4"
  # 可续跑(awk 读最后一条该 label 记录):已 DONE/HASFAIL 跳过,ERR/无记录则跑。
  # 用 awk 而非 grep -P(BSD/GNU 行为差异)+不重写文件(纯追加,杜绝并发读/mv 清空)。
  if awk -F'\t' -v l="$label" '$1==l{r=$3} END{exit (r=="DONE"||r=="HASFAIL")?0:1}' "$STATE" 2>/dev/null; then
    echo "[auto30] round $label already done, skip."
    return
  fi
  echo "[auto30] === round $label: $feature (seed=${seed:-current}) ==="
  local out summary
  if [ -n "$seed" ]; then
    out=$(SEED="$seed" bash scripts/food_test.sh "$feature" "$timeout" 2>&1)
  else
    out=$(bash scripts/food_test.sh "$feature" "$timeout" 2>&1)
  fi
  summary=$(echo "$out" | grep -E "\[AIBot Verify\] summary" | tail -1)
  summary="${summary#*summary }"
  [ -z "$summary" ] && summary="NO_SUMMARY(server异常,见 /tmp/mc_test_*.log)"
  local result="DONE"
  echo "$summary" | grep -q "FAIL" && result="HASFAIL"
  echo "$summary" | grep -q "NO_SUMMARY" && result="ERR"
  # 纯追加(不重写文件):重跑产生重复行无妨,读取方(报告/skip)都取最后一条。
  printf "%s\t%s\t%s\t%s\n" "$label" "$feature" "$result" "$summary" >> "$STATE"
  echo "[auto30] round $label -> $result: $summary"
}

# caffeinate 防睡眠(自带,失败无妨);整轮串行
command -v caffeinate >/dev/null && caffeinate -is -w $$ &
for entry in "${ROUNDS[@]}"; do
  IFS='|' read -r label feature timeout seed <<< "$entry"
  run_one "$label" "$feature" "$timeout" "$seed"
done

echo "[auto30] ALL ROUNDS COMPLETE $(date '+%F %T')"
echo "ALLDONE" >> "$STATE"
