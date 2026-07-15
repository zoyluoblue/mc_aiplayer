package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.marker.TargetMarkerService;
import io.github.zoyluo.aibot.network.payload.TargetMarkerC2S;
import io.github.zoyluo.aibot.network.payload.TargetMarkerS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Client input, private visualization, and authoritative state for the owner's single target marker. */
public final class TargetMarkerClient {
    private static final int COLOR = 0x00E5FF;
    private static volatile MarkerState marker;

    private TargetMarkerClient() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(TargetMarkerClient::render);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> marker = null);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> marker = null);
    }

    /** Returns true when Shift is held and vanilla pick-block should be cancelled. */
    public static boolean handleShiftMiddleClick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        long window = client.getWindow().getHandle();
        boolean shift = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
        if (!shift) {
            return false;
        }
        if (client.player == null || client.world == null) {
            return true;
        }
        if (!ClientPlayNetworking.canSend(TargetMarkerC2S.ID)) {
            client.player.sendMessage(Text.literal("服务器未启用 AIBot 标记协议"), true);
            return true;
        }

        float tickDelta = client.getRenderTickCounter().getTickDelta(false);
        HitResult hit = client.player.raycast(TargetMarkerService.MAX_DISTANCE, tickDelta, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            String dimension = client.world.getRegistryKey().getValue().toString();
            BlockPos blockPos = blockHit.getBlockPos().toImmutable();
            Direction face = blockHit.getSide();
            marker = new MarkerState(dimension, blockPos, face, blockPos.offset(face));
            ClientPlayNetworking.send(new TargetMarkerC2S(true, dimension, blockPos, face.getId()));
        } else {
            marker = null;
            ClientPlayNetworking.send(new TargetMarkerC2S(false, "", BlockPos.ORIGIN, Direction.UP.getId()));
        }
        return true;
    }

    public static void apply(TargetMarkerS2C payload) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (payload.active()) {
            marker = new MarkerState(payload.dimension(), payload.blockPos(),
                    Direction.byId(payload.faceId()), payload.standPos());
        } else {
            marker = null;
        }
        if (client.player != null && payload.message() != null && !payload.message().isBlank()) {
            client.player.sendMessage(Text.literal(payload.message()), true);
        }
    }

    private static void render(WorldRenderContext context) {
        MarkerState current = marker;
        MatrixStack matrices = context.matrixStack();
        var consumers = context.consumers();
        if (current == null || matrices == null || consumers == null || context.world() == null) {
            return;
        }
        String dimension = context.world().getRegistryKey().getValue().toString();
        if (!dimension.equals(current.dimension())) {
            return;
        }

        BlockPos pos = current.blockPos();
        Vec3d camera = context.camera().getPos();
        matrices.push();
        matrices.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);

        int beamHeight = Math.max(1, context.world().getTopYInclusive() - pos.getY() + 1);
        BeaconBlockEntityRenderer.renderBeam(
                matrices,
                consumers,
                BeaconBlockEntityRenderer.BEAM_TEXTURE,
                context.tickCounter().getTickDelta(false),
                1.0F,
                context.world().getTime(),
                0,
                beamHeight,
                COLOR,
                0.08F,
                0.12F);
        VertexRendering.drawBox(
                matrices,
                consumers.getBuffer(RenderLayer.getLines()),
                new Box(-0.003D, -0.003D, -0.003D, 1.003D, 1.003D, 1.003D),
                0.0F,
                0.9F,
                1.0F,
                1.0F);
        matrices.pop();
    }

    private record MarkerState(String dimension, BlockPos blockPos, Direction face, BlockPos standPos) {
    }
}
