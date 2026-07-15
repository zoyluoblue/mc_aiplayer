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
    private static KeyBinding pushToTalk;
    private static KeyBinding brainInterrupt;
    private static KeyBinding traceHudToggle;
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
        pushToTalk = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibot.push_to_talk",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.aibot"));
        brainInterrupt = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibot.brain_interrupt",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                "key.categories.aibot"));
        traceHudToggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibot.trace_hud_toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "key.categories.aibot"));
    }

    public static boolean interruptPressed() {
        return brainInterrupt != null && brainInterrupt.wasPressed();
    }

    public static boolean traceTogglePressed() {
        return traceHudToggle != null && traceHudToggle.wasPressed();
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

    public static boolean pushToTalkDown(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        // Use the physical key state so a held key is not lost by KeyBinding's press queue.
        return InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_V);
    }
}
