package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;

/**
 * 统一生存层(二期 V1):生存熔断从各任务收编至此,每个任务 tick 前无差别执行。
 *
 * 历史病灶:溺水熔断 MoveTask 有、OreDigTask 没有——bot 在湖底挖矿 air 278→0 淹死,
 * 任务全程无反应(geo_lake 实测)。横切关注点散在任务里,每写新任务重想一遍,漏一处就是一条命。
 *
 * 分工(三层正交):
 *  - DangerWatcher:威胁应对(战斗/逃跑/复活/黑暗撤离)——对"外敌"。
 *  - NavSafetyNet:位置矫正(窒息 snap/水面上浮)——"自救动作"。
 *  - SurvivalGuard(本层):任务终止性熔断——"叫停作业"。任务停了,前两层的自救才不会被
 *    作业动作每 tick 顶回去(实测活锁:安全网上浮一 tick、挖矿又把人挖回水里一 tick)。
 *
 * 定位是**兜底**:任务自带的领域熔断(如 OreDigTask 溺水时顺手拉黑水下矿、MoveTask 沾水转
 * 中继绕行)可以比这里做得更早更聪明——但任何任务都不能比这里做得更差。
 */
public final class SurvivalGuard {
    public static final SurvivalGuard INSTANCE = new SurvivalGuard();

    private SurvivalGuard() {
    }

    /**
     * 熔断检查:返回非 null=熔断理由,调用方(TaskManager)以该理由终止任务。
     * 自救型任务豁免——它们本身就是对危险的响应,打断=打断自救。
     */
    public String check(AIPlayerEntity bot, Task task) {
        if (task instanceof EvadeTask || task instanceof CombatTask
                || task instanceof EmergencyShelterTask || task instanceof EatTask
                || task instanceof LavaEscapeTask) {
            return null; // LavaEscapeTask 是入浆自救本身,绝不能被 guard_in_lava 反过来打断
        }
        // 注意:RecoverDropsTask 故意**不**豁免——水下跑尸 air 告急时斩掉它是对的:
        // 豁免=任务继续=淹死再掉一身,装备认亏换命是唯一正解(审查时曾被建议豁免,勿改)。
        // ① 溺水:头没入水且氧量只剩 5 秒——作业再要紧也得先有命换气。
        if (bot.isSubmergedInWater() && bot.getAir() < 100) {
            return "guard_drowning";
        }
        // ② 身陷岩浆:每 tick 都在烧,任何作业立刻停,让位 DangerWatcher 的脱困/灭火。
        if (bot.isInLava()) {
            return "guard_in_lava";
        }
        // ③ 着火且血线过半:火源可能就是作业对象旁(岩浆边挖矿),继续作业=钉在火上。
        if (bot.isOnFire() && bot.getHealth() < 10.0F) {
            return "guard_on_fire";
        }
        // ④ 垂死挨打:hp≤3 心且正被攻击——恋战作业等于送死,DangerWatcher 接管(逃/绝境反击)。
        if (bot.getHealth() <= 6.0F && bot.hurtTime > 0) {
            return "guard_low_hp_under_attack";
        }
        return null;
    }
}
