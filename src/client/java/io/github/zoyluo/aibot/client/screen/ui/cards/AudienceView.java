package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.AudienceClientState;
import io.github.zoyluo.aibot.client.BotCommandBridge;
import io.github.zoyluo.aibot.client.screen.ui.Theme;
import io.github.zoyluo.aibot.network.payload.AudienceControlC2S;
import io.github.zoyluo.aibot.network.payload.AudienceSnapshotS2C;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class AudienceView extends PanelCard {
    private static final int INPUT_H = 18;
    private static final int ROW_H = 22;

    private TextFieldWidget search;
    private ButtonWidget refreshButton;
    private ButtonWidget bindButton;
    private ButtonWidget unbindButton;
    private String selectedKey = "";
    private int scroll;
    private int listTop;
    private int listBottom;

    @Override
    protected String titleKey() {
        return "card.aibot.audience";
    }

    @Override
    protected int bodyHeight() {
        return 270;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void addWidgets(Consumer<ClickableWidget> sink) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        search = new TextFieldWidget(renderer, 0, 0, 120, INPUT_H, Text.translatable("audience.aibot.search"));
        search.setMaxLength(64);
        search.setSuggestion(Theme.tr("audience.aibot.search"));
        search.setChangedListener(ignored -> {
            scroll = 0;
            selectedKey = "";
        });
        refreshButton = button("btn.aibot.refresh", () ->
                BotCommandBridge.audienceControl(AudienceControlC2S.REFRESH, ""));
        bindButton = button("btn.aibot.bind", () -> {
            if (!selectedKey.isBlank()) {
                BotCommandBridge.audienceControl(AudienceControlC2S.BIND, selectedKey);
            }
        });
        unbindButton = button("btn.aibot.unbind", () ->
                BotCommandBridge.audienceControl(AudienceControlC2S.UNBIND, ""));
        layoutWidgets();
        sink.accept(search);
        sink.accept(refreshButton);
        sink.accept(bindButton);
        sink.accept(unbindButton);
        BotCommandBridge.audienceControl(AudienceControlC2S.REFRESH, "");
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta,
                              TextRenderer renderer, int bx, int by, int bw, int bh) {
        AudienceSnapshotS2C snapshot = AudienceClientState.INSTANCE.snapshot();
        if (selectedKey.isBlank() && search.getText().isBlank() && !snapshot.boundViewerKey().isBlank()) {
            selectedKey = snapshot.boundViewerKey();
        }
        String binding = snapshot.audienceBotName().isBlank()
                ? Theme.tr("audience.aibot.unbound")
                : Theme.tr("audience.aibot.bound", snapshot.boundViewerName(), snapshot.audienceBotName());
        context.drawTextWithShadow(renderer, renderer.trimToWidth(binding, bw), bx, by, Theme.TEXT);
        context.drawTextWithShadow(renderer, renderer.trimToWidth(snapshot.status(), bw),
                bx, by + 12, Theme.TEXT_DIM);

        List<AudienceSnapshotS2C.ViewerEntry> viewers = filtered(snapshot.viewers());
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, viewers.size() - visibleRows)));
        for (int row = 0; row < visibleRows && scroll + row < viewers.size(); row++) {
            AudienceSnapshotS2C.ViewerEntry viewer = viewers.get(scroll + row);
            int rowY = listTop + row * ROW_H;
            boolean selected = viewer.key().equals(selectedKey);
            boolean bound = viewer.key().equals(snapshot.boundViewerKey());
            int border = bound ? Theme.OK : selected ? Theme.ACCENT : Theme.BORDER;
            Theme.bubble(context, bx, rowY, bw, ROW_H - 2, border, selected || bound);
            int nameWidth = Math.max(30, bw - 106);
            context.drawTextWithShadow(renderer, renderer.trimToWidth(viewer.displayName(), nameWidth),
                    bx + 5, rowY + 6, Theme.TEXT_STRONG);
            String identity = viewer.reliableIdentity()
                    ? Theme.tr("audience.aibot.identity_id") : Theme.tr("audience.aibot.identity_name");
            String meta = identity + " · " + kindLabel(viewer.lastKind());
            context.drawTextWithShadow(renderer, renderer.trimToWidth(meta, 96),
                    bx + bw - 100, rowY + 6, viewer.reliableIdentity() ? Theme.OK : Theme.SYS);
        }
        if (viewers.isEmpty()) {
            context.drawCenteredTextWithShadow(renderer, Theme.tr("audience.aibot.empty"),
                    bx + bw / 2, listTop + 12, Theme.TEXT_DIM);
        }
        bindButton.active = viewers.stream().anyMatch(viewer -> viewer.key().equals(selectedKey));
        unbindButton.active = !snapshot.audienceBotName().isBlank();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || mouseY < listTop || mouseY >= listBottom || mouseX < x + Theme.PAD
                || mouseX >= x + w - Theme.PAD) {
            return false;
        }
        List<AudienceSnapshotS2C.ViewerEntry> viewers = filtered(AudienceClientState.INSTANCE.snapshot().viewers());
        int row = (int) ((mouseY - listTop) / ROW_H);
        int index = scroll + row;
        if (index >= 0 && index < viewers.size()) {
            selectedKey = viewers.get(index).key();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < x || mouseX >= x + w || mouseY < listTop || mouseY >= listBottom) {
            return false;
        }
        scroll = Math.max(0, scroll + (amount > 0 ? -1 : 1));
        return true;
    }

    private List<AudienceSnapshotS2C.ViewerEntry> filtered(List<AudienceSnapshotS2C.ViewerEntry> source) {
        String query = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return source;
        }
        return source.stream()
                .filter(viewer -> viewer.displayName().toLowerCase(Locale.ROOT).contains(query)
                        || viewer.key().toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    private void layoutWidgets() {
        if (search == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int bw = w - Theme.PAD * 2;
        int searchY = y + 51;
        search.setPosition(bx, searchY);
        search.setDimensions(Math.max(80, bw - 48), INPUT_H);
        refreshButton.setPosition(bx + bw - 44, searchY);
        refreshButton.setDimensions(44, INPUT_H);
        listTop = searchY + 23;
        int buttonsY = y + h - 26;
        listBottom = buttonsY - 4;
        int half = Math.max(60, (bw - 4) / 2);
        bindButton.setPosition(bx, buttonsY);
        bindButton.setDimensions(half, INPUT_H);
        unbindButton.setPosition(bx + half + 4, buttonsY);
        unbindButton.setDimensions(Math.max(50, bw - half - 4), INPUT_H);
    }

    private static ButtonWidget button(String key, Runnable action) {
        return ButtonWidget.builder(Text.translatable(key), ignored -> action.run())
                .dimensions(0, 0, 44, INPUT_H).build();
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "gift" -> Theme.tr("audience.aibot.kind_gift");
            case "follow" -> Theme.tr("audience.aibot.kind_follow");
            default -> Theme.tr("audience.aibot.kind_chat");
        };
    }
}
