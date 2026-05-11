package com.aiplayer.client;

import com.aiplayer.entity.AiPlayerEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AiPlayerGUI {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_PADDING = 6;
    private static final int ANIMATION_SPEED = 20;
    private static final int MESSAGE_HEIGHT = 12;
    private static final int MAX_MESSAGES = 500;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 4;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_GAP = 2;
    private static final int BACKPACK_COLUMNS = 5;
    private static final int BACKPACK_ROWS = 4;
    private static final int BACKPACK_SLOT_COUNT = BACKPACK_COLUMNS * BACKPACK_ROWS;
    private static final int PLAYER_COLUMNS = 9;
    private static final int PLAYER_ROWS = 4;
    private static final int PLAYER_SLOT_COUNT = PLAYER_COLUMNS * PLAYER_ROWS;
    private static final int BACKPACK_CONTENT_Y = 60;
    
    private static boolean isOpen = false;
    private static float slideOffset = PANEL_WIDTH;
    private static EditBox inputBox;
    private static List<String> commandHistory = new ArrayList<>();
    private static int historyIndex = -1;
    private static List<ChatMessage> messages = new ArrayList<>();
    private static int scrollOffset = 0;
    private static int maxScroll = 0;
    private static boolean showBackpack = false;
    private static InventoryPanel selectedInventoryPanel = InventoryPanel.NONE;
    private static int selectedInventorySlot = -1;
    private static final int BACKGROUND_COLOR = 0x15202020;
    private static final int BORDER_COLOR = 0x40404040;
    private static final int HEADER_COLOR = 0x25252525;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int USER_BUBBLE_COLOR = 0xC04CAF50;
    private static final int AI_PLAYER_BUBBLE_COLOR = 0xC02196F3;
    private static final int SYSTEM_BUBBLE_COLOR = 0xC0FF9800;
    private static final int BUTTON_COLOR = 0x70303030;
    private static final int BUTTON_HOVER_COLOR = 0x90505050;
    private static final int SLOT_SELECTED_COLOR = 0xFFE6C15C;
    private static final int SLOT_BORDER_COLOR = 0xAA555555;
    private static final int SLOT_BACKGROUND_COLOR = 0xAA111111;

    private enum InventoryPanel {
        NONE,
        AI,
        PLAYER
    }

    private static class ChatMessage {
        String sender;
        String text;
        int bubbleColor;
        boolean isUser;
        
        ChatMessage(String sender, String text, int bubbleColor, boolean isUser) {
            this.sender = sender;
            this.text = text;
            this.bubbleColor = bubbleColor;
            this.isUser = isUser;
        }
    }

    public static void toggle() {
        isOpen = !isOpen;
        
        Minecraft mc = Minecraft.getInstance();
        
        if (isOpen) {
            initializeInputBox();
            mc.setScreen(new AiPlayerOverlayScreen());
            if (inputBox != null) {
                inputBox.setFocused(true);
            }
        } else {
            if (inputBox != null) {
                inputBox = null;
            }
            clearInventorySelection();
            if (mc.screen instanceof AiPlayerOverlayScreen) {
                mc.setScreen(null);
            }
        }
    }

    public static boolean isOpen() {
        return isOpen;
    }

    private static void initializeInputBox() {
        Minecraft mc = Minecraft.getInstance();
        if (inputBox == null) {
            inputBox = new EditBox(mc.font, 0, 0, PANEL_WIDTH - 20, 20,
                Component.literal("指令"));
            inputBox.setMaxLength(256);
            inputBox.setHint(Component.literal("输入任务，或 spawn 召唤..."));
            inputBox.setFocused(true);
        }
    }

        public static void addMessage(String sender, String text, int bubbleColor, boolean isUser) {
        messages.add(new ChatMessage(sender, text, bubbleColor, isUser));
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
        scrollOffset = 0;
    }

        public static void addUserMessage(String text) {
        addMessage("你", text, USER_BUBBLE_COLOR, true);
    }

        public static void addAiPlayerMessage(String aiPlayerName, String text) {
        addMessage(aiPlayerName, text, AI_PLAYER_BUBBLE_COLOR, false);
    }

        public static void addSystemMessage(String text) {
        addMessage("系统", text, SYSTEM_BUBBLE_COLOR, false);
    }

    public static void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (isOpen && slideOffset > 0) {
            slideOffset = Math.max(0, slideOffset - ANIMATION_SPEED);
        } else if (!isOpen && slideOffset < PANEL_WIDTH) {
            slideOffset = Math.min(PANEL_WIDTH, slideOffset + ANIMATION_SPEED);
        }
        if (slideOffset >= PANEL_WIDTH) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        int panelX = (int) (screenWidth - PANEL_WIDTH + slideOffset);
        int panelY = 0;
        int panelHeight = screenHeight;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO
        );
        graphics.fillGradient(panelX, panelY, screenWidth, panelHeight, BACKGROUND_COLOR, BACKGROUND_COLOR);
        
        graphics.fillGradient(panelX - 2, panelY, panelX, panelHeight, BORDER_COLOR, BORDER_COLOR);

        int headerHeight = showBackpack ? 286 : 60;
        graphics.fillGradient(panelX, panelY, screenWidth, headerHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§lAI面板", panelX + PANEL_PADDING, panelY + 8, TEXT_COLOR);
        graphics.drawString(mc.font, "§7Alt/Option+2 关闭", panelX + PANEL_PADDING, panelY + 20, 0xFF888888);

        int buttonY = panelY + 36;
        int buttonWidth = (PANEL_WIDTH - PANEL_PADDING * 2 - BUTTON_GAP * 2) / 3;
        int backpackButtonX = panelX + PANEL_PADDING;
        int stopButtonX = backpackButtonX + buttonWidth + BUTTON_GAP;
        int recallButtonX = stopButtonX + buttonWidth + BUTTON_GAP;
        drawButton(graphics, mc.font, backpackButtonX, buttonY, buttonWidth, BUTTON_HEIGHT, showBackpack ? "关闭背包" : "背包", isInside(mouseX, mouseY, backpackButtonX, buttonY, buttonWidth, BUTTON_HEIGHT));
        drawButton(graphics, mc.font, stopButtonX, buttonY, buttonWidth, BUTTON_HEIGHT, "停止", isInside(mouseX, mouseY, stopButtonX, buttonY, buttonWidth, BUTTON_HEIGHT));
        drawButton(graphics, mc.font, recallButtonX, buttonY, buttonWidth, BUTTON_HEIGHT, "召回", isInside(mouseX, mouseY, recallButtonX, buttonY, buttonWidth, BUTTON_HEIGHT));

        if (showBackpack) {
            renderBackpack(graphics, mc, panelX, panelY + BACKPACK_CONTENT_Y, mouseX, mouseY);
        }

        int inputAreaY = screenHeight - 80;
        int messageAreaTop = headerHeight + 5;
        int messageAreaHeight = Math.max(20, inputAreaY - messageAreaTop - 5);
        int messageAreaBottom = messageAreaTop + messageAreaHeight;

        int totalMessageHeight = 0;
        for (ChatMessage msg : messages) {
            int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3);
            String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
            int bubbleHeight = MESSAGE_HEIGHT + 10;
            totalMessageHeight += bubbleHeight + 5 + 12;
        }
        maxScroll = Math.max(0, totalMessageHeight - messageAreaHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        int yPos = messageAreaTop + 5;
        graphics.enableScissor(panelX, messageAreaTop, screenWidth, messageAreaBottom);
        
        if (messages.isEmpty()) {
            graphics.drawString(mc.font, "§7还没有消息...", 
                panelX + PANEL_PADDING, yPos, 0xFF666666);
            graphics.drawString(mc.font, "§7输入 spawn 创建你的AI玩家。", 
                panelX + PANEL_PADDING, yPos + 12, 0xFF555555);
        } else {
            int currentY = messageAreaBottom - 5;
            
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                
                int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3);
                String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
                int textWidth = mc.font.width(wrappedText);
                int textHeight = MESSAGE_HEIGHT;
                int bubbleWidth = Math.min(textWidth + 10, maxBubbleWidth);
                int bubbleHeight = textHeight + 10;
                
                int msgY = currentY - bubbleHeight + scrollOffset;
                
                if (msgY + bubbleHeight < messageAreaTop - 20 || msgY > messageAreaBottom + 20) {
                    currentY -= bubbleHeight + 5;
                    continue;
                }
                if (msg.isUser) {
                    int bubbleX = screenWidth - bubbleWidth - PANEL_PADDING - 5;
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    graphics.drawString(mc.font, "§7" + msg.sender, bubbleX, msgY - 12, 0xFFCCCCCC);
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                    
                } else {
                    int bubbleX = panelX + PANEL_PADDING;
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    graphics.drawString(mc.font, "§l" + msg.sender, bubbleX, msgY - 12, TEXT_COLOR);
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                }
                
                currentY -= bubbleHeight + 5 + 12;
            }
        }
        
        graphics.disableScissor();
        
        if (maxScroll > 0) {
            int scrollBarHeight = Math.max(20, (messageAreaHeight * messageAreaHeight) / (maxScroll + messageAreaHeight));
            int scrollBarY = messageAreaTop + (int)((messageAreaHeight - scrollBarHeight) * (1.0f - (float)scrollOffset / maxScroll));
            graphics.fill(screenWidth - 4, scrollBarY, screenWidth - 2, scrollBarY + scrollBarHeight, 0xFF888888);
        }
        graphics.fillGradient(panelX, inputAreaY, screenWidth, screenHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§7指令:", panelX + PANEL_PADDING, inputAreaY + 10, 0xFF888888);

        if (inputBox != null && isOpen) {
            inputBox.setX(panelX + PANEL_PADDING);
            inputBox.setY(inputAreaY + 25);
            inputBox.setWidth(PANEL_WIDTH - (PANEL_PADDING * 2));
            inputBox.render(graphics, mouseX, mouseY, partialTick);
        }

        graphics.drawString(mc.font, "§8回车发送 | ↑↓历史 | 滚轮查看", 
            panelX + PANEL_PADDING, screenHeight - 15, 0xFF555555);
        
        RenderSystem.disableBlend();
    }

    private static void drawButton(GuiGraphics graphics, Font font, int x, int y, int width, int height, String label, boolean hovered) {
        int color = hovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR;
        graphics.fill(x, y, x + width, y + height, color);
        graphics.fill(x, y, x + width, y + 1, 0x80FFFFFF);
        int labelX = x + (width - font.width(label)) / 2;
        graphics.drawString(font, label, labelX, y + 5, 0xFFFFFFFF);
    }

    private static void renderBackpack(GuiGraphics graphics, Minecraft mc, int panelX, int y, int mouseX, int mouseY) {
        AiPlayerEntity aiPlayer = findOwnedAiPlayer(mc);
        graphics.drawString(mc.font, "§7先点来源，再点目标；右键目标只转移1个", panelX + PANEL_PADDING, y, 0xFFB0B0B0);
        graphics.drawString(mc.font, "§7AI背包：最多显示20格", panelX + PANEL_PADDING, y + 18, 0xFFB0B0B0);

        int gridX = backpackGridX(panelX);
        int gridY = backpackGridY(y);
        List<ItemStack> stacks = aiPlayer == null ? List.of() : parseBackpackSlots(aiPlayer.getClientBackpackSnapshot());

        for (int slot = 0; slot < BACKPACK_SLOT_COUNT; slot++) {
            int slotX = gridX + (slot % BACKPACK_COLUMNS) * (SLOT_SIZE + SLOT_GAP);
            int slotY = gridY + (slot / BACKPACK_COLUMNS) * (SLOT_SIZE + SLOT_GAP);
            boolean selected = selectedInventoryPanel == InventoryPanel.AI && selectedInventorySlot == slot;
            drawSlotBackground(graphics, slotX, slotY, selected);

            if (slot < stacks.size()) {
                ItemStack stack = stacks.get(slot);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, slotX + 1, slotY + 1);
                    graphics.renderItemDecorations(mc.font, stack, slotX + 1, slotY + 1);
                }
            }
        }

        if (aiPlayer == null) {
            graphics.drawString(mc.font, "§8未找到你的AI玩家", gridX, gridY + BACKPACK_ROWS * (SLOT_SIZE + SLOT_GAP) + 2, 0xFF777777);
        }

        renderPlayerInventory(graphics, mc, panelX, y);
    }

    private static void renderPlayerInventory(GuiGraphics graphics, Minecraft mc, int panelX, int backpackY) {
        if (mc.player == null) {
            return;
        }
        int labelY = playerInventoryLabelY(backpackY);
        graphics.drawString(mc.font, "§7玩家背包", panelX + PANEL_PADDING, labelY, 0xFFB0B0B0);

        int gridX = playerInventoryGridX(panelX);
        int gridY = playerInventoryGridY(backpackY);
        List<ItemStack> stacks = getPlayerInventoryStacks(mc);
        for (int slot = 0; slot < PLAYER_SLOT_COUNT; slot++) {
            int slotX = gridX + (slot % PLAYER_COLUMNS) * (SLOT_SIZE + SLOT_GAP);
            int slotY = gridY + (slot / PLAYER_COLUMNS) * (SLOT_SIZE + SLOT_GAP);
            boolean selected = selectedInventoryPanel == InventoryPanel.PLAYER && selectedInventorySlot == slot;
            drawSlotBackground(graphics, slotX, slotY, selected);

            if (slot < stacks.size()) {
                ItemStack stack = stacks.get(slot);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, slotX + 1, slotY + 1);
                    graphics.renderItemDecorations(mc.font, stack, slotX + 1, slotY + 1);
                }
            }
        }
    }

    private static void drawSlotBackground(GuiGraphics graphics, int slotX, int slotY, boolean selected) {
        graphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, selected ? SLOT_SELECTED_COLOR : SLOT_BORDER_COLOR);
        graphics.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, SLOT_BACKGROUND_COLOR);
    }

    private static int backpackGridX(int panelX) {
        int gridWidth = BACKPACK_COLUMNS * SLOT_SIZE + (BACKPACK_COLUMNS - 1) * SLOT_GAP;
        return panelX + (PANEL_WIDTH - gridWidth) / 2;
    }

    private static int backpackGridY(int backpackY) {
        return backpackY + 34;
    }

    private static int playerInventoryGridX(int panelX) {
        int gridWidth = PLAYER_COLUMNS * SLOT_SIZE + (PLAYER_COLUMNS - 1) * SLOT_GAP;
        return panelX + (PANEL_WIDTH - gridWidth) / 2;
    }

    private static int playerInventoryLabelY(int backpackY) {
        return backpackGridY(backpackY) + BACKPACK_ROWS * (SLOT_SIZE + SLOT_GAP) + 10;
    }

    private static int playerInventoryGridY(int backpackY) {
        return playerInventoryLabelY(backpackY) + 14;
    }

    private static List<ItemStack> getPlayerInventoryStacks(Minecraft mc) {
        List<ItemStack> stacks = new ArrayList<>();
        if (mc.player == null) {
            return stacks;
        }
        for (int slot = 0; slot < Math.min(PLAYER_SLOT_COUNT, mc.player.getInventory().items.size()); slot++) {
            stacks.add(mc.player.getInventory().items.get(slot));
        }
        while (stacks.size() < PLAYER_SLOT_COUNT) {
            stacks.add(ItemStack.EMPTY);
        }
        return stacks;
    }

    private static AiPlayerEntity findOwnedAiPlayer(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        UUID ownerUuid = mc.player.getUUID();
        AiPlayerEntity fallback = null;
        double fallbackDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AiPlayerEntity aiPlayer) {
                if (aiPlayer.isOwnedBy(ownerUuid)) {
                    return aiPlayer;
                }
                double distance = aiPlayer.distanceToSqr(mc.player);
                if (distance < fallbackDistance) {
                    fallback = aiPlayer;
                    fallbackDistance = distance;
                }
            }
        }
        return fallback;
    }

    private static List<ItemStack> parseBackpackSlots(String snapshot) {
        List<ItemStack> stacks = new ArrayList<>();
        if (snapshot == null || snapshot.isBlank()) {
            return stacks;
        }
        String[] slots = snapshot.split("\\|");
        for (int i = 0; i < Math.min(BACKPACK_SLOT_COUNT, slots.length); i++) {
            String[] parts = slots[i].split(",", 2);
            if (parts.length != 2) {
                stacks.add(ItemStack.EMPTY);
                continue;
            }
            int count;
            try {
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                stacks.add(ItemStack.EMPTY);
                continue;
            }
            if (count <= 0) {
                stacks.add(ItemStack.EMPTY);
                continue;
            }
            try {
                Item item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(parts[0]));
                stacks.add(item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count));
            } catch (RuntimeException e) {
                stacks.add(ItemStack.EMPTY);
            }
        }
        while (stacks.size() < BACKPACK_SLOT_COUNT) {
            stacks.add(ItemStack.EMPTY);
        }
        return stacks;
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

        private static String wrapText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(i));
            if (font.width(result.toString() + "...") >= maxWidth) {
                return result.substring(0, result.length() - 3) + "...";
            }
        }
        return result.toString();
    }

    public static boolean handleKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!isOpen || inputBox == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (keyCode == 256) {
            toggle();
            return true;
        }
        if (keyCode == 257) {
            String command = inputBox.getValue().trim();
            if (!command.isEmpty()) {
                sendCommand(command);
                inputBox.setValue("");
                historyIndex = -1;
            }
            return true;
        }
        if (keyCode == 265 && !commandHistory.isEmpty()) {
            if (historyIndex < commandHistory.size() - 1) {
                historyIndex++;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            }
            return true;
        }
        if (keyCode == 264) {
            if (historyIndex > 0) {
                historyIndex--;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            } else if (historyIndex == 0) {
                historyIndex = -1;
                inputBox.setValue("");
            }
            return true;
        }
        if (keyCode == 259 || keyCode == 261 || keyCode == 268 || keyCode == 269 || 
            keyCode == 263 || keyCode == 262) {
            inputBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        return true;
    }

    public static boolean handleCharTyped(char codePoint, int modifiers) {
        if (isOpen && inputBox != null) {
            inputBox.charTyped(codePoint, modifiers);
            return true;
        }
        return false;
    }

    public static void handleMouseClick(double mouseX, double mouseY, int button) {
        if (!isOpen) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int panelX = (int) (screenWidth - PANEL_WIDTH + slideOffset);

        if (button == 0) {
            int buttonY = 36;
            int buttonWidth = (PANEL_WIDTH - PANEL_PADDING * 2 - BUTTON_GAP * 2) / 3;
            int backpackButtonX = panelX + PANEL_PADDING;
            int stopButtonX = backpackButtonX + buttonWidth + BUTTON_GAP;
            int recallButtonX = stopButtonX + buttonWidth + BUTTON_GAP;
            if (isInside(mouseX, mouseY, backpackButtonX, buttonY, buttonWidth, BUTTON_HEIGHT)) {
                showBackpack = !showBackpack;
                clearInventorySelection();
                return;
            }
            if (isInside(mouseX, mouseY, stopButtonX, buttonY, buttonWidth, BUTTON_HEIGHT)) {
                sendStopCommand();
                return;
            }
            if (isInside(mouseX, mouseY, recallButtonX, buttonY, buttonWidth, BUTTON_HEIGHT)) {
                sendRecallCommand();
                return;
            }
        }

        if (showBackpack && (button == 0 || button == 1)) {
            InventorySlot clickedSlot = inventorySlotAt(panelX, mouseX, mouseY);
            if (clickedSlot != null) {
                handleInventorySlotClick(clickedSlot.panel(), clickedSlot.slot(), button == 1 ? 1 : 64);
                return;
            }
        }

        if (inputBox != null) {
            int inputAreaY = screenHeight - 80;
            if (mouseY >= inputAreaY + 25 && mouseY <= inputAreaY + 45) {
                inputBox.setFocused(true);
            } else {
                inputBox.setFocused(false);
            }
        }
    }

    public static void handleMouseScroll(double scrollDelta) {
        if (!isOpen) return;
        
        int scrollAmount = (int)(scrollDelta * 3 * MESSAGE_HEIGHT);
        scrollOffset -= scrollAmount;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static void sendCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        
        commandHistory.add(command);
        if (commandHistory.size() > 50) {
            commandHistory.remove(0);
        }
        
        addUserMessage(command);

        String lowerCommand = command.toLowerCase(Locale.ROOT);
        if (lowerCommand.equals("spawn") || lowerCommand.startsWith("spawn ")) {
            String name = command.length() > 5 ? command.substring(5).trim() : "";
            if (mc.player != null) {
                if (name.isEmpty()) {
                    mc.player.connection.sendCommand("ai spawn");
                    addSystemMessage("正在召唤你的AI玩家。");
                } else {
                    mc.player.connection.sendCommand("ai spawn " + name);
                    addSystemMessage("正在召唤AI玩家：" + name);
                }
            }
            return;
        }

        if (lowerCommand.equals("stop")) {
            sendStopCommand();
            return;
        }

        if (lowerCommand.equals("recall")) {
            sendRecallCommand();
            return;
        }

        if (lowerCommand.equals("snapshot")) {
            if (mc.player != null) {
                mc.player.connection.sendCommand("ai snapshot");
                addSystemMessage("已请求输出AI观察JSON。");
            }
            return;
        }

        if (lowerCommand.equals("backpack")) {
            if (mc.player != null) {
                mc.player.connection.sendCommand("ai backpack");
                addSystemMessage("已请求查看AI背包。");
            }
            return;
        }

        if (lowerCommand.startsWith("take ")) {
            if (mc.player != null) {
                mc.player.connection.sendCommand("ai backpack take " + command.substring(5).trim());
                addSystemMessage("已请求从AI背包取出物品。");
            }
            return;
        }

        if (lowerCommand.startsWith("put ")) {
            if (mc.player != null) {
                mc.player.connection.sendCommand("ai backpack put " + command.substring(4).trim());
                addSystemMessage("已请求向AI背包放入物品。");
            }
            return;
        }

        if (mc.player != null) {
            mc.player.connection.sendCommand("ai say " + command);
            addSystemMessage("已发送给你的AI玩家：" + command);
        }
    }

    private static void sendStopCommand() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand("ai stop");
            addSystemMessage("已请求AI停止当前任务并回到你身边。");
        }
    }

    private static void sendRecallCommand() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand("ai recall");
            addSystemMessage("已请求召回AI并停止所有任务。");
        }
    }

    private static InventorySlot inventorySlotAt(int panelX, double mouseX, double mouseY) {
        int aiSlot = backpackSlotAt(panelX, mouseX, mouseY);
        if (aiSlot >= 0) {
            return new InventorySlot(InventoryPanel.AI, aiSlot);
        }
        int playerSlot = playerInventorySlotAt(panelX, mouseX, mouseY);
        if (playerSlot >= 0) {
            return new InventorySlot(InventoryPanel.PLAYER, playerSlot);
        }
        return null;
    }

    private static int backpackSlotAt(int panelX, double mouseX, double mouseY) {
        int gridX = backpackGridX(panelX);
        int gridY = backpackGridY(BACKPACK_CONTENT_Y);
        int gridWidth = BACKPACK_COLUMNS * SLOT_SIZE + (BACKPACK_COLUMNS - 1) * SLOT_GAP;
        int gridHeight = BACKPACK_ROWS * SLOT_SIZE + (BACKPACK_ROWS - 1) * SLOT_GAP;
        return slotAt(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight, BACKPACK_COLUMNS, BACKPACK_ROWS);
    }

    private static int playerInventorySlotAt(int panelX, double mouseX, double mouseY) {
        int gridX = playerInventoryGridX(panelX);
        int gridY = playerInventoryGridY(BACKPACK_CONTENT_Y);
        int gridWidth = PLAYER_COLUMNS * SLOT_SIZE + (PLAYER_COLUMNS - 1) * SLOT_GAP;
        int gridHeight = PLAYER_ROWS * SLOT_SIZE + (PLAYER_ROWS - 1) * SLOT_GAP;
        return slotAt(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight, PLAYER_COLUMNS, PLAYER_ROWS);
    }

    private static int slotAt(
        double mouseX,
        double mouseY,
        int gridX,
        int gridY,
        int gridWidth,
        int gridHeight,
        int columns,
        int rows
    ) {
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + gridWidth || mouseY >= gridY + gridHeight) {
            return -1;
        }
        int localX = (int) mouseX - gridX;
        int localY = (int) mouseY - gridY;
        int column = localX / (SLOT_SIZE + SLOT_GAP);
        int row = localY / (SLOT_SIZE + SLOT_GAP);
        int insideX = localX % (SLOT_SIZE + SLOT_GAP);
        int insideY = localY % (SLOT_SIZE + SLOT_GAP);
        if (column < 0 || column >= columns || row < 0 || row >= rows || insideX >= SLOT_SIZE || insideY >= SLOT_SIZE) {
            return -1;
        }
        return row * columns + column;
    }

    private static void handleInventorySlotClick(InventoryPanel panel, int slot, int count) {
        if (selectedInventoryPanel == InventoryPanel.NONE) {
            selectInventorySlot(panel, slot);
            return;
        }
        if (selectedInventoryPanel == panel && selectedInventorySlot == slot) {
            clearInventorySelection();
            return;
        }
        if (selectedInventoryPanel == panel) {
            selectInventorySlot(panel, slot);
            return;
        }
        moveSelectedSlotTo(panel, slot, count);
    }

    private static void selectInventorySlot(InventoryPanel panel, int slot) {
        if (isDisplayedSlotEmpty(panel, slot)) {
            addSystemMessage("请选择有物品的格子。");
            clearInventorySelection();
            return;
        }
        selectedInventoryPanel = panel;
        selectedInventorySlot = slot;
        addSystemMessage("已选择" + inventoryPanelLabel(panel) + "第 " + slot + " 格。");
    }

    private static void moveSelectedSlotTo(InventoryPanel targetPanel, int targetSlot, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        AiPlayerEntity aiPlayer = findOwnedAiPlayer(mc);
        if (aiPlayer == null) {
            addSystemMessage("未找到你的AI玩家。");
            return;
        }
        if (selectedInventoryPanel == InventoryPanel.PLAYER && targetPanel == InventoryPanel.AI) {
            mc.player.connection.sendCommand("ai backpack put_slot " + selectedInventorySlot + " " + targetSlot + " " + count);
            addSystemMessage("已请求把玩家背包第 " + selectedInventorySlot + " 格放入AI背包第 " + targetSlot + " 格。");
        } else if (selectedInventoryPanel == InventoryPanel.AI && targetPanel == InventoryPanel.PLAYER) {
            mc.player.connection.sendCommand("ai backpack take_slot_to " + selectedInventorySlot + " " + targetSlot + " " + count);
            addSystemMessage("已请求把AI背包第 " + selectedInventorySlot + " 格取到玩家背包第 " + targetSlot + " 格。");
        }
        clearInventorySelection();
    }

    private static boolean isDisplayedSlotEmpty(InventoryPanel panel, int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (panel == InventoryPanel.AI) {
            AiPlayerEntity aiPlayer = findOwnedAiPlayer(mc);
            if (aiPlayer == null) {
                return true;
            }
            List<ItemStack> stacks = parseBackpackSlots(aiPlayer.getClientBackpackSnapshot());
            return slot < 0 || slot >= stacks.size() || stacks.get(slot).isEmpty();
        }
        if (panel == InventoryPanel.PLAYER) {
            List<ItemStack> stacks = getPlayerInventoryStacks(mc);
            return slot < 0 || slot >= stacks.size() || stacks.get(slot).isEmpty();
        }
        return true;
    }

    private static void clearInventorySelection() {
        selectedInventoryPanel = InventoryPanel.NONE;
        selectedInventorySlot = -1;
    }

    private static String inventoryPanelLabel(InventoryPanel panel) {
        return panel == InventoryPanel.AI ? "AI背包" : "玩家背包";
    }

    private record InventorySlot(InventoryPanel panel, int slot) {
    }

    public static void tick() {
        if (isOpen && inputBox != null) {
            if (!inputBox.isFocused()) {
                inputBox.setFocused(true);
            }
        }
    }
}
