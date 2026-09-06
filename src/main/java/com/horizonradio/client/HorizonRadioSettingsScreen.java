package com.horizonradio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/** Small in-game screen for client-side YouTube audio settings and diagnostics. */
public final class HorizonRadioSettingsScreen extends GuiScreen {

    private static final int BUTTON_YOUTUBE_AUDIO = 1;
    private static final int BUTTON_TEST_AUDIO = 2;
    private static final int BUTTON_BACK = 3;
    private final GuiScreen parent;
    private GuiButton youtubeAudioButton;
    private GuiButton testAudioButton;

    public HorizonRadioSettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int left = width / 2 - 100;
        youtubeAudioButton = new GuiButton(BUTTON_YOUTUBE_AUDIO, left, height / 2 - 45, 200, 20, youtubeAudioLabel());
        testAudioButton = new GuiButton(BUTTON_TEST_AUDIO, left, height / 2 - 15, 200, 20, "Test YouTube audio");
        buttonList.add(youtubeAudioButton);
        buttonList.add(testAudioButton);
        buttonList.add(new GuiButton(BUTTON_BACK, left, height / 2 + 45, 200, 20, "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "HorizonRadio Settings", width / 2, height / 2 - 85, 0xFFFFFFFF);
        drawCenteredString(
            fontRendererObj,
            "Audio is downloaded by each client, not the server.",
            width / 2,
            height / 2 - 68,
            0xFFAAAAAA);
        drawCenteredString(
            fontRendererObj,
            HorizonRadioClient.getYoutubeAudioTestStatus(),
            width / 2,
            height / 2 + 20,
            0xFFFFFFFF);
        youtubeAudioButton.displayString = youtubeAudioLabel();
        testAudioButton.enabled = !HorizonRadioClient.getYoutubeAudioTestStatus()
            .startsWith("Testing");
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BUTTON_YOUTUBE_AUDIO) {
            HorizonRadioClient.setYoutubeAudioEnabled(!HorizonRadioClient.isYoutubeAudioEnabled());
        } else if (button.id == BUTTON_TEST_AUDIO) {
            HorizonRadioClient.startYoutubeAudioTest();
        } else if (button.id == BUTTON_BACK) {
            Minecraft.getMinecraft()
                .displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            Minecraft.getMinecraft()
                .displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String youtubeAudioLabel() {
        return "YouTube audio downloads: " + (HorizonRadioClient.isYoutubeAudioEnabled() ? "ON" : "OFF");
    }
}
