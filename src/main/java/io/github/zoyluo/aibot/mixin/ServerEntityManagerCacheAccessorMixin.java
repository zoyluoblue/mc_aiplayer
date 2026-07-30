package io.github.zoyluo.aibot.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.world.entity.SectionedEntityCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes raw entity sections without filtering out their current tracking status. */
@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerCacheAccessorMixin {
    @Accessor("cache")
    SectionedEntityCache<Entity> aibot$getSectionCache();
}
