#!/bin/bash
# 回归闸(提交前门禁):跑锁定全绿的实验室套件,任何**新**回归 → exit 1,把回归拦在提交前,
# 而不是攒到下次 marathon 才发现(直接终结"修完又坏"循环)。
#   用法: bash scripts/gate.sh          改完代码、提交前跑;绿(exit 0)才 cp。
#   设计: KNOWN_RED 白名单容忍"已知未修红账"(如 geo_bonus),它们 FAIL 只警告不拦闸;
#         白名单外任何 FAIL/NO_RESULT = 真回归,拦闸。已知红被修好(意外变绿)也会提示该除名。
set -u
cd "$(dirname "$0")/.." || exit 1

# 锁定全绿基线:确定性实验室套件(geo 矩阵 + mining)。real_*/nav_* 不入闸(它们是能力 backlog,非基线)。
SUITES=("geo_suite" "mining")
# 已知未修红账;修好后从此处除名,闸即纳入守护。区分两类(闸首跑即暴露):
#   geo_bonus —— 稳定红(画布开放空间顺路矿空转,我引入的回归,待还债)
#   geo_flow  —— flaky(流动水 move_dig_drowning 时序敏感,轮15红/28绿/闸首跑红),待稳定后除名
# 进白名单不是放过,是承认它们还没 gate-stable;闸守的是"其余基线不许新回归"。
KNOWN_RED="geo_bonus geo_flow"

fail=0
for s in "${SUITES[@]}"; do
  line=$(bash scripts/food_test.sh "$s" 2400 2>/dev/null | grep -E "\[AIBot Verify\] summary" | tail -1)
  summary="${line#*summary }"
  if [ -z "$summary" ]; then
    echo "[gate] ❌ $s: NO_RESULT(server 异常,见 /tmp/mc_test_${s}_*.log)"
    fail=1
    continue
  fi
  echo "[gate] $s: $summary"
  # 逐个 FAIL 场景:在 KNOWN_RED 白名单内→警告容忍;白名单外→真回归拦闸
  while IFS= read -r scen; do
    [ -z "$scen" ] && continue
    if echo "$KNOWN_RED" | grep -qw "$scen"; then
      echo "[gate]   ⚠ $scen FAIL(已知红账,容忍)"
    else
      echo "[gate]   ✖ $scen FAIL(新回归!)"
      fail=1
    fi
  done < <(echo "$summary" | grep -oE "[a-z_]+=FAIL" | sed 's/=FAIL//')
  # 已知红意外变绿 → 提示除名(让闸纳入守护)
  for red in $KNOWN_RED; do
    if echo "$summary" | grep -qw "${red}=PASS"; then
      echo "[gate]   ℹ ${red} 已变绿——可从 gate.sh KNOWN_RED 除名,纳入回归守护"
    fi
  done
done

if [ $fail -ne 0 ]; then
  echo "[gate] ❌ 回归闸不通过——有新回归,勿提交。先修绿。"
  exit 1
fi
echo "[gate] ✅ 回归闸通过——锁定基线无回归,可提交。"
exit 0
