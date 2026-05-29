package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.client.screen.BotPanelScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class AIBotKeyBindings {
    private static KeyBinding openPanel;
    private static KeyBinding openActions;
    private static boolean altZeroDown;
    private static boolean altNineDown;

    private AIBotKeyBindings() {
    }

    public static void register() {
        openPanel = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibot.open_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.aibot"));
        openActions = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibot.open_actions",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.aibot"));
    }

    public static BotPanelScreen.Mode pollToggle(MinecraftClient client) {
        boolean chatPressed = false;
        while (openPanel.wasPressed()) {
            chatPressed = true;
        }
        boolean actionsPressed = false;
        while (openActions.wasPressed()) {
            actionsPressed = true;
        }
        long handle = client.getWindow().getHandle();
        boolean altPressed = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean zeroPressed = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_0);
        boolean altZeroPressed = altPressed && zeroPressed;
        boolean chatComboOpened = altZeroPressed && !altZeroDown;
        altZeroDown = altZeroPressed;
        boolean ninePressed = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_9);
        boolean altNinePressed = altPressed && ninePressed;
        boolean actionsComboOpened = altNinePressed && !altNineDown;
        altNineDown = altNinePressed;
        if (!(client.currentScreen == null || client.currentScreen instanceof BotPanelScreen)) {
            return null;
        }
        if (actionsPressed || actionsComboOpened) {
            return BotPanelScreen.Mode.ACTIONS;
        }
        if (chatPressed || chatComboOpened) {
            return BotPanelScreen.Mode.CHAT_STATUS;
        }
        return null;
    }
}
