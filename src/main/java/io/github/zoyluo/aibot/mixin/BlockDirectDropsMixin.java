package io.github.zoyluo.aibot.mixin;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AI 假人破坏方块 → 掉落物直接进背包,不生成 ItemEntity。
 * 免去"挖完还要寻路过去捡"的整段动作,也避免掉落物弹进水里/岩浆丢失。
 * 两条破坏路径(interactionManager.processBlockBreakingAction 与
 * world.breakBlock(pos, true, bot))最终都汇入本方法拦截的 Entity 重载,一处全覆盖。
 * 背包满时 offerOrDrop 按原版掉在 bot 脚下;矿石经验球走 onStacksDropped 原版逻辑不受影响。
 * 真实玩家不受影响(仅 AIPlayerEntity 分支)。
 */
@Mixin(Block.class)
public abstract class BlockDirectDropsMixin {

    @Inject(
            method = "dropStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/BlockEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void aibot$dropsDirectToInventory(BlockState state, World world, BlockPos pos,
                                                     BlockEntity blockEntity, Entity entity, ItemStack tool,
                                                     CallbackInfo ci) {
        if (!(entity instanceof AIPlayerEntity bot) || !(world instanceof ServerWorld serverWorld)) {
            return;
        }
        for (ItemStack stack : Block.getDroppedStacks(state, serverWorld, pos, blockEntity, entity, tool)) {
            bot.getInventory().offerOrDrop(stack);
        }
        state.onStacksDropped(serverWorld, pos, tool, true);
        ci.cancel();
    }
}
