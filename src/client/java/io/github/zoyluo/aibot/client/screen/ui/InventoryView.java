package io.github.zoyluo.aibot.client.screen.ui;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.client.BotCommandBridge;
import io.github.zoyluo.aibot.network.payload.BotItemMoveC2S;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 交互式背包面板:上半=AI 背包(点击拿出到玩家),下半=玩家背包(点击放入 AI)。
 * 左键=整堆;Shift+左键=单个。槽位以"真实 main 下标"寻址,直接映射到 {@link BotItemMoveC2S} 的 slot 字段。
 * 全程不触碰 ScreenHandler/Screen 容器逻辑——仅发 C2S 由服务端直改 Inventory(遵守铁律 G3)。
 */
public final class InventoryView implements PanelComponent {
    private static final int SLOT = 18;       // 单格边长(含 1px 边框,图标 16)
    private static final int COLS = 9;
    private static final int AI_ROWS = 4;     // AI main 36 格 = 4×9
    private static final int PL_MAIN_ROWS = 3; // 玩家主背包 slots 9..35
    private static final int HOVER = 0x40FFFFFF;

    private final String target;

    private int x;
    private int y;
    private int w;
    private int h;

    // setBounds 时算好的各分区原点,render 与 hit-test 共用,保证像素一致。
    private int gridX;
    private int equipRowY;
    private int aiLabelY;
    private int aiGridY;
    private int plLabelY;
    private int plMainY;
    private int plHotbarY;

    private BotSnapshotS2C snapshot;
    private final ItemStack[] aiSlots = new ItemStack[AI_ROWS * COLS];
    private final ItemStack[] equipSlots = new ItemStack[6]; // 0头/1胸/2腿/3脚/4主手/5副手

    public InventoryView(String target) {
        this.target = target;
        for (int i = 0; i < aiSlots.length; i++) {
            aiSlots[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < equipSlots.length; i++) {
            equipSlots[i] = ItemStack.EMPTY;
        }
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.gridX = x + Math.max(0, (w - COLS * SLOT) / 2);
        this.equipRowY = y + 11;                  // 装备行(标题在 y)
        this.aiLabelY = equipRowY + SLOT + 5;     // AI 背包标题
        this.aiGridY = aiLabelY + 11;
        int aiBottom = aiGridY + AI_ROWS * SLOT;
        this.plLabelY = aiBottom + 6;
        this.plMainY = plLabelY + 11;
        this.plHotbarY = plMainY + PL_MAIN_ROWS * SLOT + 3;
    }

    @Override
    public int preferredHeight() {
        // 装备:11(标题)+18(一行)+5;AI:11+72;玩家:6+11+54+3+18
        return 11 + SLOT + 5 + 11 + AI_ROWS * SLOT + 6 + 11 + PL_MAIN_ROWS * SLOT + 3 + SLOT;
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        this.snapshot = snapshot;
        for (int i = 0; i < aiSlots.length; i++) {
            aiSlots[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < equipSlots.length; i++) {
            equipSlots[i] = ItemStack.EMPTY;
        }
        if (snapshot != null) {
            for (BotSnapshotS2C.ItemEntry entry : snapshot.inventory()) {
                int slot = entry.slot();
                if (slot >= 0 && slot < aiSlots.length) {
                    aiSlots[slot] = stack(entry);
                }
            }
            for (BotSnapshotS2C.ItemEntry entry : snapshot.equipment()) {
                int slot = entry.slot();
                if (slot >= 0 && slot < equipSlots.length) {
                    equipSlots[slot] = stack(entry);
                }
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer) {
        PlayerInventory playerInv = playerInventory();

        ItemStack hovered = ItemStack.EMPTY;

        // —— AI 装备(头/胸/腿/脚/主手/副手,只展示不转移)——
        context.drawTextWithShadow(renderer, Theme.tr("inventory.aibot.section_equip"), gridX, y, Theme.TEXT_DIM);
        for (int i = 0; i < equipSlots.length; i++) {
            int gx = gridX + i * SLOT;
            boolean hot = inCell(mouseX, mouseY, gx, equipRowY);
            drawSlot(context, renderer, gx, equipRowY, equipSlots[i], hot);
            if (hot && !equipSlots[i].isEmpty()) {
                hovered = equipSlots[i];
            }
        }

        // —— AI 背包 ——
        context.drawTextWithShadow(renderer, Theme.tr("inventory.aibot.section_ai"), gridX, aiLabelY, Theme.TEXT_DIM);
        for (int slot = 0; slot < aiSlots.length; slot++) {
            int gx = gridX + (slot % COLS) * SLOT;
            int gy = aiGridY + (slot / COLS) * SLOT;
            boolean hot = inCell(mouseX, mouseY, gx, gy);
            drawSlot(context, renderer, gx, gy, aiSlots[slot], hot);
            if (hot && !aiSlots[slot].isEmpty()) {
                hovered = aiSlots[slot];
            }
        }

        // —— 玩家背包 ——
        context.drawTextWithShadow(renderer, Theme.tr("inventory.aibot.section_self"), gridX, plLabelY, Theme.TEXT_DIM);
        if (playerInv != null) {
            for (int row = 0; row < PL_MAIN_ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int slot = 9 + row * COLS + col;
                    int gx = gridX + col * SLOT;
                    int gy = plMainY + row * SLOT;
                    boolean hot = inCell(mouseX, mouseY, gx, gy);
                    ItemStack stack = playerInv.getStack(slot);
                    drawSlot(context, renderer, gx, gy, stack, hot);
                    if (hot && !stack.isEmpty()) {
                        hovered = stack;
                    }
                }
            }
            for (int col = 0; col < COLS; col++) {
                int gx = gridX + col * SLOT;
                boolean hot = inCell(mouseX, mouseY, gx, plHotbarY);
                ItemStack stack = playerInv.getStack(col);
                drawSlot(context, renderer, gx, plHotbarY, stack, hot);
                if (hot && !stack.isEmpty()) {
                    hovered = stack;
                }
            }
        }

        if (!hovered.isEmpty()) {
            context.drawItemTooltip(renderer, hovered, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean left = button == 0;
        boolean right = button == 1;
        if (!left && !right) {
            return false;
        }
        boolean single = left && Screen.hasShiftDown(); // Shift+左键=单个
        boolean half = right;                            // 右键=半堆

        // AI 槽:拿出
        for (int slot = 0; slot < aiSlots.length; slot++) {
            int gx = gridX + (slot % COLS) * SLOT;
            int gy = aiGridY + (slot / COLS) * SLOT;
            if (inCell(mouseX, mouseY, gx, gy)) {
                ItemStack src = aiSlots[slot];
                if (!src.isEmpty()) {
                    BotCommandBridge.moveItem(target, BotItemMoveC2S.TAKE, slot, amountFor(src, single, half));
                }
                return true;
            }
        }
        // 玩家主背包:放入
        PlayerInventory inv = playerInventory();
        for (int row = 0; row < PL_MAIN_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int gx = gridX + col * SLOT;
                int gy = plMainY + row * SLOT;
                if (inCell(mouseX, mouseY, gx, gy)) {
                    putFromPlayer(inv, 9 + row * COLS + col, single, half);
                    return true;
                }
            }
        }
        // 玩家快捷栏:放入
        for (int col = 0; col < COLS; col++) {
            int gx = gridX + col * SLOT;
            if (inCell(mouseX, mouseY, gx, plHotbarY)) {
                putFromPlayer(inv, col, single, half);
                return true;
            }
        }
        return false;
    }

    private void putFromPlayer(PlayerInventory inv, int slot, boolean single, boolean half) {
        if (inv == null) {
            return;
        }
        ItemStack src = inv.getStack(slot);
        if (!src.isEmpty()) {
            BotCommandBridge.moveItem(target, BotItemMoveC2S.PUT, slot, amountFor(src, single, half));
        }
    }

    // 整堆=0(服务端取整堆);单个=1;半堆=向上取整的一半。
    private static int amountFor(ItemStack src, boolean single, boolean half) {
        if (single) {
            return 1;
        }
        if (half) {
            return Math.max(1, src.getCount() / 2);
        }
        return 0;
    }

    private boolean inCell(double mx, double my, int gx, int gy) {
        return mx >= gx && mx < gx + SLOT && my >= gy && my < gy + SLOT;
    }

    private void drawSlot(DrawContext context, TextRenderer renderer, int gx, int gy, ItemStack stack, boolean hovered) {
        context.fill(gx, gy, gx + SLOT, gy + SLOT, Theme.TRACK);
        context.drawHorizontalLine(gx, gx + SLOT - 1, gy, Theme.BORDER);
        context.drawHorizontalLine(gx, gx + SLOT - 1, gy + SLOT - 1, Theme.BORDER);
        context.drawVerticalLine(gx, gy, gy + SLOT - 1, Theme.BORDER);
        context.drawVerticalLine(gx + SLOT - 1, gy, gy + SLOT - 1, Theme.BORDER);
        if (stack != null && !stack.isEmpty()) {
            context.drawItem(stack, gx + 1, gy + 1);
            context.drawStackOverlay(renderer, stack, gx + 1, gy + 1);
        }
        if (hovered) {
            context.fill(gx + 1, gy + 1, gx + SLOT - 1, gy + SLOT - 1, HOVER);
        }
    }

    private static PlayerInventory playerInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? null : client.player.getInventory();
    }

    private static ItemStack stack(BotSnapshotS2C.ItemEntry entry) {
        Item item = Registries.ITEM.getOptionalValue(Identifier.of(entry.itemId())).orElse(Items.BARRIER);
        return new ItemStack(item, entry.count());
    }
}
