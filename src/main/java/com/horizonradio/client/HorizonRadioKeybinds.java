package com.horizonradio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;

/** Registers the one client key and opens the GUI from the FML client tick. */
public final class HorizonRadioKeybinds {

    private static KeyBinding openGuiKey;

    private HorizonRadioKeybinds() {}

    public static void register() {
        if (openGuiKey == null) {
            openGuiKey = new KeyBinding("key.horizonradio.open_gui", Keyboard.KEY_N, "key.categories.horizonradio");
            ClientRegistry.registerKeyBinding(openGuiKey);
        }
    }

    public static void onClientTick() {
        if (openGuiKey == null || !openGuiKey.isPressed()) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (hasLoadedClientWorld(minecraft)) {
            minecraft.displayGuiScreen(new HorizonRadioScreen());
        }
    }

    /**
     * Runtime GUI gate: the title screen and a player-null client are not a
     * valid HorizonRadio GUI test environment.
     */
    static boolean hasLoadedClientWorld(Minecraft minecraft) {
        return minecraft != null && minecraft.theWorld != null && minecraft.thePlayer != null;
    }
}
