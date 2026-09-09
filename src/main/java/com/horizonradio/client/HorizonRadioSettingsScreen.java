package com.horizonradio.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.network.packets.ServerSettingsPacket;

/** Client preferences and server-authoritative operator settings. */
public final class HorizonRadioSettingsScreen extends GuiScreen {

    private final GuiScreen parent;
    private int page;
    private GuiTextField songCountField, radioCountField;
    private GuiTextField queueLimitField, durationLimitField;
    private boolean requestedServerSettings;
    private long settingsRevision = -1L;
    private long applyStartedAt;
    private String operatorStatus = "";
    private int left, top, panelWidth;

    public HorizonRadioSettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        commitResultCounts();
        songCountField = null;
        radioCountField = null;
        queueLimitField = null;
        durationLimitField = null;
        if (!requestedServerSettings && mc != null && mc.thePlayer != null) {
            requestedServerSettings = true;
            HorizonRadioClient.requestServerSettings();
        }
        ServerSettingsPacket server = HorizonRadioClient.serverSettings();
        settingsRevision = HorizonRadioClient.serverSettingsRevision();
        boolean operator = server != null && server.canEdit();
        if (page == 3 && !operator) page = 0;
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        panelWidth = Math.min(340, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 230) / 2);
        String[] tabs = operator ? new String[] { "Notifications", "Search", "Audio", "OP Settings" }
            : new String[] { "Notifications", "Search", "Audio" };
        int tabWidth = (panelWidth - 20 - 2 * (tabs.length - 1)) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            GuiButton tab = add(10 + i, left + 10 + i * (tabWidth + 2), top + 28, tabWidth, tabs[i]);
            tab.enabled = page != i;
        }
        ClientUiSettings settings = HorizonRadioClient.uiSettings();
        if (page == 0) {
            add(20, left + 10, top + 55, panelWidth - 20, "Notifications: " + onOff(settings.notifications));
            int column = (panelWidth - 24) / 2;
            ClientUiSettings.Category[] categories = ClientUiSettings.Category.values();
            int visibleIndex = 0;
            for (int i = 0; i < categories.length; i++) {
                ClientUiSettings.Category category = categories[i];
                if (category == ClientUiSettings.Category.VOLUME) continue;
                GuiButton toggle = add(
                    30 + i,
                    left + 10 + (visibleIndex % 2) * (column + 4),
                    top + 80 + (visibleIndex / 2) * 23,
                    column,
                    category.label + ": " + onOff(settings.enabled.contains(category)));
                toggle.enabled = settings.notifications;
                visibleIndex++;
            }
            add(21, left + 10, top + 175, panelWidth - 20, "Position: " + settings.position.label);
        } else if (page == 1) {
            add(22, left + 10, top + 64, panelWidth - 20, "Automatic search: " + onOff(settings.autoSearch));
            GuiButton minus = add(23, left + 10, top + 92, 30, "-");
            GuiButton plus = add(24, left + panelWidth - 40, top + 92, 30, "+");
            minus.enabled = settings.autoSearch && settings.searchDelay > 100;
            plus.enabled = settings.autoSearch && settings.searchDelay < 3000;
            songCountField = resultCountField(top + 119, settings.songResults);
            radioCountField = resultCountField(top + 146, settings.radioResults);
        } else if (page == 3) {
            queueLimitField = operatorNumberField(top + 76, server.getQueueLimit());
            durationLimitField = operatorNumberField(top + 106, server.getDurationMinutes());
            add(40, left + panelWidth / 2 - 50, top + 174, 100, "Apply").enabled = applyStartedAt == 0L;
        } else {
            add(
                1,
                left + 10,
                top + 64,
                panelWidth - 20,
                "YouTube audio: " + onOff(HorizonRadioClient.isYoutubeAudioEnabled()));
            GuiButton test = add(2, left + 10, top + 90, panelWidth - 20, "Test YouTube audio");
            test.enabled = !HorizonRadioClient.getYoutubeAudioTestStatus()
                .startsWith("Testing");
        }
        add(3, left + panelWidth / 2 - 50, top + 202, 100, "Done");
    }

    @SuppressWarnings("unchecked")
    private GuiButton add(int id, int x, int y, int width, String text) {
        GuiButton button = new GuiButton(id, x, y, width, 20, text);
        buttonList.add(button);
        return button;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, left + panelWidth, top + 230, 0xFF202020);
        drawGradientRect(left + 3, top + 3, left + panelWidth - 3, top + 51, 0xFF202020, 0xFF1B1B1B);
        drawRect(left + 6, top + 51, left + panelWidth - 6, top + 198, 0xFF555555);
        drawRect(left + 7, top + 52, left + panelWidth - 7, top + 197, 0xFF242424);
        drawRect(left, top, left + panelWidth, top + 1, 0xFFAAAAAA);
        drawRect(left, top + 229, left + panelWidth, top + 230, 0xFF555555);
        drawRect(left, top, left + 1, top + 230, 0xFFAAAAAA);
        drawRect(left + panelWidth - 1, top, left + panelWidth, top + 230, 0xFF555555);
        drawCenteredString(fontRendererObj, "HorizonRadio Settings", width / 2, top + 12, 0xFFFFFFFF);
        if (page == 1) {
            drawCenteredString(
                fontRendererObj,
                "Search delay: " + HorizonRadioClient.uiSettings().searchDelay + " ms",
                width / 2,
                top + 98,
                0xFFFFFFFF);
            drawString(fontRendererObj, "Song results", left + 12, top + 125, 0xFFFFFFFF);
            drawString(fontRendererObj, "Radio results", left + 12, top + 152, 0xFFFFFFFF);
            songCountField.drawTextBox();
            radioCountField.drawTextBox();
            drawCenteredString(fontRendererObj, "5-100 results | Enter to apply", width / 2, top + 179, 0xFFAAAAAA);
        } else if (page == 2) {
            for (Object item : buttonList) {
                GuiButton button = (GuiButton) item;
                if (button.id == 2) button.enabled = !HorizonRadioClient.getYoutubeAudioTestStatus()
                    .startsWith("Testing");
            }
            fontRendererObj.drawSplitString(
                HorizonRadioClient.getYoutubeAudioTestStatus(),
                left + 12,
                top + 122,
                panelWidth - 24,
                0xFFCCCCCC);
        }
        if (page == 3) {
            drawCenteredString(fontRendererObj, "Shared server queue", width / 2, top + 60, 0xFFAAAAAA);
            drawString(
                fontRendererObj,
                "Queue limit (1-" + HorizonRadioConfig.MAX_PLAYLIST_SIZE + ")",
                left + 12,
                top + 82,
                0xFFFFFFFF);
            drawString(fontRendererObj, "Max. song length (min)", left + 12, top + 112, 0xFFFFFFFF);
            queueLimitField.drawTextBox();
            durationLimitField.drawTextBox();
            drawCenteredString(
                fontRendererObj,
                "Applies to new entries; keeps existing songs.",
                width / 2,
                top + 137,
                0xFFAAAAAA);
            drawCenteredString(fontRendererObj, operatorStatus, width / 2, top + 155, 0xFFFFFFAA);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        commitResultCounts();
        ClientUiSettings settings = HorizonRadioClient.uiSettings();
        if (button.id == 40) {
            applyOperatorSettings();
            return;
        }
        if (button.id >= 10 && button.id <= 13) page = button.id - 10;
        else if (button.id == 20) settings.notifications = !settings.notifications;
        else if (button.id == 21) settings.position = ClientUiSettings.Position
            .values()[(settings.position.ordinal() + 1) % ClientUiSettings.Position.values().length];
        else if (button.id == 22) settings.autoSearch = !settings.autoSearch;
        else if (button.id == 23) settings.searchDelay = Math.max(100, settings.searchDelay - 100);
        else if (button.id == 24) settings.searchDelay = Math.min(3000, settings.searchDelay + 100);
        else if (button.id >= 30 && button.id < 30 + ClientUiSettings.Category.values().length) {
            ClientUiSettings.Category category = ClientUiSettings.Category.values()[button.id - 30];
            if (!settings.enabled.remove(category)) settings.enabled.add(category);
        } else
            if (button.id == 1) HorizonRadioClient.setYoutubeAudioEnabled(!HorizonRadioClient.isYoutubeAudioEnabled());
            else if (button.id == 2) HorizonRadioClient.startYoutubeAudioTest();
            else if (button.id == 3) {
                mc.displayGuiScreen(parent);
                return;
            }
        if (button.id >= 20) HorizonRadioClient.saveUiSettings();
        if (button.id == 21) NotificationOverlay.preview();
        initGui();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (queueLimitField != null) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                applyOperatorSettings();
                return;
            }
            if (keyCode == Keyboard.KEY_TAB) {
                boolean queueFocused = queueLimitField.isFocused();
                queueLimitField.setFocused(!queueFocused);
                durationLimitField.setFocused(queueFocused);
                return;
            }
            if (typeNumber(queueLimitField, typedChar, keyCode) || typeNumber(durationLimitField, typedChar, keyCode))
                return;
        }
        if (songCountField != null) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                commitResultCounts();
                return;
            }
            if (keyCode == Keyboard.KEY_TAB) {
                boolean songFocused = songCountField.isFocused();
                commitResultCounts();
                songCountField.setFocused(!songFocused);
                radioCountField.setFocused(songFocused);
                return;
            }
            if (typeNumber(songCountField, typedChar, keyCode) || typeNumber(radioCountField, typedChar, keyCode))
                return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private GuiTextField operatorNumberField(int y, int value) {
        GuiTextField field = new GuiTextField(fontRendererObj, left + panelWidth - 92, y, 80, 20);
        field.setMaxStringLength(10);
        field.setText(Integer.toString(value));
        return field;
    }

    private void applyOperatorSettings() {
        ServerSettingsPacket server = HorizonRadioClient.serverSettings();
        if (applyStartedAt != 0L || queueLimitField == null || server == null || !server.canEdit()) return;
        int queue = parsePositiveInteger(queueLimitField.getText());
        int minutes = parsePositiveInteger(durationLimitField.getText());
        if (!HorizonRadioConfig.validLimits(queue, minutes)) {
            operatorStatus = "Enter valid positive limits (queue max. " + HorizonRadioConfig.MAX_PLAYLIST_SIZE + ").";
            return;
        }
        operatorStatus = "Saving...";
        applyStartedAt = System.currentTimeMillis();
        for (Object item : buttonList) if (((GuiButton) item).id == 40) ((GuiButton) item).enabled = false;
        HorizonRadioClient.updateServerSettings(queue, minutes);
    }

    static int parsePositiveInteger(String text) {
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private GuiTextField resultCountField(int y, int value) {
        GuiTextField field = new GuiTextField(fontRendererObj, left + panelWidth - 72, y, 60, 20);
        field.setMaxStringLength(3);
        field.setText(Integer.toString(value));
        return field;
    }

    private static boolean typeNumber(GuiTextField field, char character, int key) {
        if (!field.isFocused()) return false;
        String previous = field.getText();
        field.textboxKeyTyped(character, key);
        if (!field.getText()
            .matches("[0-9]*")) field.setText(previous);
        return true;
    }

    private void commitResultCounts() {
        if (songCountField == null) return;
        ClientUiSettings settings = HorizonRadioClient.uiSettings();
        int songs = parseResultCount(songCountField.getText(), settings.songResults);
        int radios = parseResultCount(radioCountField.getText(), settings.radioResults);
        boolean changed = songs != settings.songResults || radios != settings.radioResults;
        settings.songResults = songs;
        settings.radioResults = radios;
        songCountField.setText(Integer.toString(songs));
        radioCountField.setText(Integer.toString(radios));
        if (changed) HorizonRadioClient.saveUiSettings();
    }

    static int parseResultCount(String text, int previous) {
        try {
            return Math.max(5, Math.min(100, Integer.parseInt(text)));
        } catch (NumberFormatException ignored) {
            return previous;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (songCountField != null) {
            boolean songFocused = songCountField.isFocused();
            boolean radioFocused = radioCountField.isFocused();
            songCountField.mouseClicked(mouseX, mouseY, button);
            radioCountField.mouseClicked(mouseX, mouseY, button);
            if ((songFocused && !songCountField.isFocused()) || (radioFocused && !radioCountField.isFocused()))
                commitResultCounts();
        }
        if (queueLimitField != null) {
            queueLimitField.mouseClicked(mouseX, mouseY, button);
            durationLimitField.mouseClicked(mouseX, mouseY, button);
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void updateScreen() {
        if (settingsRevision != HorizonRadioClient.serverSettingsRevision()) {
            ServerSettingsPacket server = HorizonRadioClient.serverSettings();
            applyStartedAt = 0L;
            operatorStatus = server == null ? ""
                : server.getStatus() == ServerSettingsPacket.SAVED ? "Server settings saved"
                    : server.getStatus() == ServerSettingsPacket.SAVE_FAILED ? "Could not save server settings"
                        : server.getStatus() == ServerSettingsPacket.DENIED ? "OP permission required"
                            : server.getStatus() == ServerSettingsPacket.INVALID ? "Invalid limits" : "";
            initGui();
        }
        if (applyStartedAt != 0L && System.currentTimeMillis() - applyStartedAt >= 10000L) {
            applyStartedAt = 0L;
            operatorStatus = "No response from server. Please try again.";
            for (Object item : buttonList) if (((GuiButton) item).id == 40) ((GuiButton) item).enabled = true;
        }
        if (queueLimitField != null) {
            queueLimitField.updateCursorCounter();
            durationLimitField.updateCursorCounter();
        }
        if (songCountField != null) {
            songCountField.updateCursorCounter();
            radioCountField.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        commitResultCounts();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
