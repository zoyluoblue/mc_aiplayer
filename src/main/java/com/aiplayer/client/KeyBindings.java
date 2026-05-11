package com.aiplayer.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    private static boolean toggleWasDown = false;

    public static void registerKeys() {
    }

    public static boolean consumeToggle(Minecraft client) {
        long window = client.getWindow().getWindow();
        boolean altDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean twoDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_2);
        boolean pressed = altDown && twoDown;

        if (pressed && !toggleWasDown) {
            toggleWasDown = true;
            return true;
        }
        if (!pressed) {
            toggleWasDown = false;
        }
        return false;
    }
}
