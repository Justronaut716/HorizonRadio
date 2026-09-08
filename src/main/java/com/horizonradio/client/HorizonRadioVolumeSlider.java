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

    static int volumeTrackFillWidth(int width, float value) {
        return Math.round(Math.max(0, width - 4) * clamp(value));
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        int trackLeft = xPosition + 2;
        int trackTop = yPosition + (height - 3) / 2;
        int trackRight = xPosition + width - 2;
        int trackBottom = trackTop + 3;
        boolean hovered = enabled && mouseX >= xPosition
            && mouseX < xPosition + width
            && mouseY >= yPosition
            && mouseY < yPosition + height;
        drawRect(trackLeft, trackTop, trackRight, trackBottom, 0xFF303030);
        int fillWidth = volumeTrackFillWidth(width, value);
        if (fillWidth > 0) {
            drawRect(trackLeft, trackTop, trackLeft + fillWidth, trackBottom, 0xFF929292);
            drawRect(trackLeft, trackTop, trackLeft + fillWidth, trackTop + 1, 0xFFB0B0B0);
        }
        int thumbX = trackLeft + fillWidth;
        drawGradientRect(
            thumbX - 1,
            trackTop - 2,
            thumbX + 1,
            trackBottom + 2,
            hovered || dragging ? 0xFFFFFFFF : 0xFFDDDDDD,
            hovered || dragging ? 0xFFBDBDBD : 0xFF8C8C8C);
    }

    private static float clamp(float input) {
        return Math.max(0.0f, Math.min(1.0f, input));
    }
}
