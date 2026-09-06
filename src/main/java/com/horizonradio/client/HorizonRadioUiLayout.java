package com.horizonradio.client;

/** Reference geometry for the WebPrototype-aligned HorizonRadio panel. */
final class HorizonRadioUiLayout {

    static final int REFERENCE_PANEL_WIDTH = 360;
    static final int REFERENCE_PANEL_HEIGHT = 322;
    private static final int SCREEN_MARGIN = 10;
    private static final int HEADER_BOTTOM = 36;
    private static final int BODY_BOTTOM = 245;
    private static final int CONTENT_LEFT_INSET = 8;
    private static final int CONTENT_WIDTH = 220;
    private static final int QUEUE_GAP = 4;
    private static final int QUEUE_WIDTH = 120;
    private static final int FOOTER_TOP = 251;
    private static final int FOOTER_HEIGHT = 53;
    private static final int VOLUME_TOP = 304;
    private static final int VOLUME_HEIGHT = 14;

    private final int screenWidth;
    private final int screenHeight;
    private final float scale;
    private final int scaledPanelWidth;
    private final int scaledPanelHeight;
    private final int scaledPanelLeft;
    private final int scaledPanelTop;
    private final int referencePanelLeft;
    private final int referencePanelTop;

    private HorizonRadioUiLayout(int screenWidth, int screenHeight) {
        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
        float widthScale = (this.screenWidth - 2.0f * SCREEN_MARGIN) / REFERENCE_PANEL_WIDTH;
        float heightScale = (this.screenHeight - 2.0f * SCREEN_MARGIN) / REFERENCE_PANEL_HEIGHT;
        scale = Math.min(1.0f, Math.max(0.1f, Math.min(widthScale, heightScale)));
        scaledPanelWidth = Math.max(1, Math.round(REFERENCE_PANEL_WIDTH * scale));
        scaledPanelHeight = Math.max(1, Math.round(REFERENCE_PANEL_HEIGHT * scale));
        scaledPanelLeft = (this.screenWidth - scaledPanelWidth) / 2;
        scaledPanelTop = (this.screenHeight - scaledPanelHeight) / 2;
        referencePanelLeft = this.screenWidth / 2 - REFERENCE_PANEL_WIDTH / 2;
        referencePanelTop = this.screenHeight / 2 - REFERENCE_PANEL_HEIGHT / 2;
    }

    static HorizonRadioUiLayout create(int screenWidth, int screenHeight) {
        return new HorizonRadioUiLayout(screenWidth, screenHeight);
    }

    int panelWidth() {
        return REFERENCE_PANEL_WIDTH;
    }

    int panelHeight() {
        return REFERENCE_PANEL_HEIGHT;
    }

    float scale() {
        return scale;
    }

    int panelLeft() {
        return scaledPanelLeft;
    }

    int panelTop() {
        return scaledPanelTop;
    }

    int scaledPanelWidth() {
        return scaledPanelWidth;
    }

    int scaledPanelHeight() {
        return scaledPanelHeight;
    }

    int referencePanelLeft() {
        return referencePanelLeft;
    }

    int referencePanelTop() {
        return referencePanelTop;
    }

    int bodyTop() {
        return referencePanelTop + HEADER_BOTTOM;
    }

    int bodyBottom() {
        return referencePanelTop + BODY_BOTTOM;
    }

    int contentLeft() {
        return referencePanelLeft + CONTENT_LEFT_INSET;
    }

    int contentWidth() {
        return CONTENT_WIDTH;
    }

    int queueLeft() {
        return contentLeft() + CONTENT_WIDTH + QUEUE_GAP;
    }

    int queueWidth() {
        return QUEUE_WIDTH;
    }

    int footerTop() {
        return referencePanelTop + FOOTER_TOP;
    }

    int footerHeight() {
        return FOOTER_HEIGHT;
    }

    int volumeTop() {
        return referencePanelTop + VOLUME_TOP;
    }

    int volumeHeight() {
        return VOLUME_HEIGHT;
    }

    int toLogicalMouseX(int mouseX) {
        return referencePanelLeft + Math.round((mouseX - scaledPanelLeft) / scale);
    }

    int toLogicalMouseY(int mouseY) {
        return referencePanelTop + Math.round((mouseY - scaledPanelTop) / scale);
    }

    boolean containsScaledPanel(int mouseX, int mouseY) {
        return mouseX >= scaledPanelLeft && mouseX <= scaledPanelLeft + scaledPanelWidth
            && mouseY >= scaledPanelTop
            && mouseY <= scaledPanelTop + scaledPanelHeight;
    }
}
