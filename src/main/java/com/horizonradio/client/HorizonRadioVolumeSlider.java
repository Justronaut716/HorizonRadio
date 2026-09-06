package com.horizonradio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** Small 1.7.10-compatible volume control; it stores state only until audio is ported. */
public final class HorizonRadioVolumeSlider extends GuiButton {

    private float value;
    private boolean dragging;

    public HorizonRadioVolumeSlider(int id, int x, int y, int width, int height, float initialValue) {
        super(id, x, y, width, height, "");
        value = clamp(initialValue);
    }

    public float getValue() {
        return value;
    }

    @Override
    public boolean mousePressed(Minecraft minecraft, int mouseX, int mouseY) {
        if (!super.mousePressed(minecraft, mouseX, mouseY)) {
            return false;
        }
        setValueFromMouse(mouseX);
        dragging = true;
        return true;
    }

    @Override
    protected void mouseDragged(Minecraft minecraft, int mouseX, int mouseY) {
        if (dragging) {
            setValueFromMouse(mouseX);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        boolean wasDragging = dragging;
        dragging = false;
        if (wasDragging) {
            HorizonRadioClient.persistVolume();
        }
    }

    public void setValueFromMouse(int mouseX) {
        float fraction = (float) (mouseX - xPosition) / (float) Math.max(1, width - 1);
        value = clamp(fraction);
        HorizonRadioClient.setVolumePreview(value);
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        int knob = 0xFFB5B5B5;
        drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0xFF222222);
        drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, 0xFF111111);
        drawRect(xPosition + 2, yPosition + 3, xPosition + width - 2, yPosition + height - 3, 0xFF222222);
        int knobX = xPosition + (int) ((width - 1) * value);
        drawRect(knobX - 2, yPosition + 1, knobX + 2, yPosition + height - 1, knob);
    }

    private static float clamp(float input) {
        return Math.max(0.0f, Math.min(1.0f, input));
    }
}
