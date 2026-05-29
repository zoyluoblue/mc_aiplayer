package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.screen.ui.Theme;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class InventoryCard extends PanelCard {
    private static final int SLOT = 20;
    private static final int ICON = 16;
    private static final int COLS = 6;

    @Override
    protected String titleKey() {
        return "card.aibot.inventory";
    }

    @Override
    protected int bodyHeight() {
        return 66;
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("status.aibot.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        if (snapshot.inventory().isEmpty()) {
            context.drawTextWithShadow(renderer, Theme.tr("inventory.aibot.empty"), bx, by, Theme.TEXT_DIM);
            return;
        }
        int visible = Math.min(snapshot.inventory().size(), COLS * 2);
        for (int index = 0; index < visible; index++) {
            BotSnapshotS2C.ItemEntry entry = snapshot.inventory().get(index);
            int gx = bx + (index % COLS) * SLOT;
            int gy = by + (index / COLS) * SLOT;
            context.fill(gx, gy, gx + 18, gy + 18, Theme.TRACK);
            context.drawHorizontalLine(gx, gx + 17, gy, Theme.BORDER);
            context.drawHorizontalLine(gx, gx + 17, gy + 17, Theme.BORDER);
            context.drawVerticalLine(gx, gy, gy + 17, Theme.BORDER);
            context.drawVerticalLine(gx + 17, gy, gy + 17, Theme.BORDER);
            ItemStack stack = stack(entry);
            context.drawItem(stack, gx + 1, gy + 1);
            context.drawStackOverlay(renderer, stack, gx + 1, gy + 1);
        }
        int hidden = snapshot.inventory().size() - visible;
        if (hidden > 0) {
            String more = "+" + hidden;
            context.drawTextWithShadow(renderer, more, bx + bw - renderer.getWidth(more), by + SLOT + 5, Theme.TEXT_DIM);
        }
    }

    private static ItemStack stack(BotSnapshotS2C.ItemEntry entry) {
        Item item = Registries.ITEM.getOptionalValue(Identifier.of(entry.itemId())).orElse(net.minecraft.item.Items.BARRIER);
        return new ItemStack(item, entry.count());
    }
}
