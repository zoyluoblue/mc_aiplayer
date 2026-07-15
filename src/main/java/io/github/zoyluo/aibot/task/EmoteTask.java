package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;

/**
 * 直播肢体语言:一段 1~3 秒的短表演动作(挥手/点头/摇头/跳/转圈/鞠躬/东张西望/尬舞),
 * 纯输入驱动、不碰世界方块、可随时被打断。给 bot 一点"活人感/综艺感"——之前除了 speak
 * 它没有任何身体动作。短任务跑完即结束,follow 这类长期意图会自动恢复(与礼物动作同机制)。
 * "庆祝放烟花"不走这里,由 emote 工具直接调 GiftCelebrator(现成的烟花+转圈)。
 */
public final class EmoteTask extends AbstractTask {
    private final String style;
    private final int duration;
    private float baseYaw;

    public EmoteTask(String style) {
        String s = style == null ? "" : style.trim().toLowerCase();
        this.style = normalize(s);
        this.duration = durationFor(this.style);
    }

    private static String normalize(String s) {
        return switch (s) {
            case "wave", "挥手", "打招呼", "hi", "hello" -> "wave";
            case "nod", "点头", "yes" -> "nod";
            case "shake", "摇头", "no" -> "shake";
            case "jump", "跳", "蹦" -> "jump";
            case "spin", "转圈", "转身", "旋转" -> "spin";
            case "bow", "鞠躬", "行礼" -> "bow";
            case "look_around", "东张西望", "张望", "四处看" -> "look_around";
            case "dance", "跳舞", "尬舞", "扭" -> "dance";
            default -> "dance"; // 没指定/不认识 → 最有节目感的尬舞
        };
    }

    private static int durationFor(String style) {
        return switch (style) {
            case "jump" -> 44;
            case "look_around" -> 50;
            case "dance" -> 64;
            default -> 32;
        };
    }

    @Override
    public String name() {
        return "emote";
    }

    @Override
    public String describe() {
        return "Emote " + style + " " + elapsed + "/" + duration;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.99D, elapsed / (double) duration);
    }

    @Override
    public boolean isWaiting() {
        return true; // 原地表演,位置不变属正常,别让 StuckWatcher 误判
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        baseYaw = bot.getYaw();
        bot.getActionPack().stopMovement();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed >= duration) {
            reset(bot);
            complete();
            return;
        }
        switch (style) {
            case "wave" -> {
                LookAction.setYawPitch(bot, baseYaw, -10.0F);
                if (elapsed % 4 == 0) {
                    bot.swingHand(Hand.MAIN_HAND);
                }
            }
            case "nod" -> {
                float pitch = 10.0F + 20.0F * MathHelper.sin(elapsed * 0.5F);
                LookAction.setYawPitch(bot, baseYaw, pitch);
            }
            case "shake" -> {
                float yaw = baseYaw + 28.0F * MathHelper.sin(elapsed * 0.6F);
                LookAction.setYawPitch(bot, yaw, 0.0F);
            }
            case "jump" -> {
                if (elapsed % 12 == 0) {
                    bot.getActionPack().jumpOnce();
                }
            }
            case "spin" -> LookAction.setYawPitch(bot, baseYaw + elapsed * 12.0F, 0.0F);
            case "bow" -> {
                bot.getActionPack().setSneaking(true);
                LookAction.setYawPitch(bot, baseYaw, 55.0F);
            }
            case "look_around" -> {
                float yaw = baseYaw + 65.0F * MathHelper.sin(elapsed * 0.16F);
                LookAction.setYawPitch(bot, yaw, -5.0F);
            }
            case "dance" -> {
                float yaw = baseYaw + 22.0F * MathHelper.sin(elapsed * 0.9F);
                LookAction.setYawPitch(bot, yaw, 5.0F * MathHelper.sin(elapsed * 0.7F));
                bot.getActionPack().setSneaking(elapsed % 8 < 4); // 蹲起律动
                if (elapsed % 5 == 0) {
                    bot.swingHand(Hand.MAIN_HAND);
                }
                if (elapsed % 20 == 0) {
                    bot.getActionPack().jumpOnce();
                }
            }
            default -> {
            }
        }
    }

    private void reset(AIPlayerEntity bot) {
        bot.getActionPack().setSneaking(false);
        bot.getActionPack().setStrafing(0.0F);
        bot.getActionPack().setForward(0.0F);
        LookAction.setYawPitch(bot, baseYaw, 0.0F);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        reset(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        reset(bot);
        bot.getActionPack().stopAll();
    }
}
