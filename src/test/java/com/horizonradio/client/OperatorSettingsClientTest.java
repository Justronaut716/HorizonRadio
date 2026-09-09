package com.horizonradio.client;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.junit.After;
import org.junit.Test;

import com.horizonradio.network.packets.ServerSettingsPacket;

public class OperatorSettingsClientTest {

    @After
    public void reset() {
        HorizonRadioClient.resetServerSettings();
    }

    @Test
    public void operatorTabUsesConfirmedLimitsAndDisappearsWhenPermissionIsRevoked() throws Exception {
        HorizonRadioClient.resetServerSettings();
        HorizonRadioSettingsScreen screen = new HorizonRadioSettingsScreen(null);
        screen.width = 640;
        screen.height = 360;
        screen.initGui();
        assertNull(button(screen, 13));
        HorizonRadioClient.handleServerSettings(new ServerSettingsPacket(false, 50, 15, ServerSettingsPacket.SYNC));
        screen.updateScreen();
        assertNull(button(screen, 13));
        HorizonRadioClient.handleServerSettings(new ServerSettingsPacket(true, 125, 30, ServerSettingsPacket.SYNC));
        screen.updateScreen();
        assertEquals("OP Settings", button(screen, 13).displayString);
        screen.actionPerformed(button(screen, 13));
        assertEquals("125", field(screen, "queueLimitField").getText());
        assertEquals("30", field(screen, "durationLimitField").getText());
        field(screen, "queueLimitField").setText("0");
        screen.actionPerformed(button(screen, 40));
        assertEquals(
            125,
            HorizonRadioClient.serverSettings()
                .getQueueLimit());
        HorizonRadioClient.handleServerSettings(new ServerSettingsPacket(false, 125, 30, ServerSettingsPacket.DENIED));
        screen.updateScreen();
        assertNull(button(screen, 13));
        assertNull(button(screen, 40));
        screen.onGuiClosed();
    }

    @Test
    public void synchronizedDurationControlsServerSelectionsAndIsResetOnDisconnect() throws Exception {
        PlaybackMode previous = HorizonRadioClient.getPlaybackMode();
        try {
            HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
            Method valid = HorizonRadioClient.class.getDeclaredMethod("isValidChartDuration", String.class, long.class);
            valid.setAccessible(true);
            HorizonRadioClient.handleServerSettings(new ServerSettingsPacket(false, 50, 2, ServerSettingsPacket.SYNC));
            assertTrue((Boolean) valid.invoke(null, "abcdefghijk", 119_999L));
            assertFalse((Boolean) valid.invoke(null, "abcdefghijk", 120_000L));
            HorizonRadioClient.handleServerSettings(new ServerSettingsPacket(false, 50, 3, ServerSettingsPacket.SYNC));
            assertTrue((Boolean) valid.invoke(null, "abcdefghijk", 120_000L));
            HorizonRadioClient.resetServerSettings();
            assertNull(HorizonRadioClient.serverSettings());
        } finally {
            HorizonRadioClient.setPlaybackMode(previous);
        }
    }

    @Test
    public void displayedQueueLimitFollowsServerUpdatesAndPlaybackMode() {
        PlaybackMode previous = HorizonRadioClient.getPlaybackMode();
        try {
            HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
            HorizonRadioClient.resetServerSettings();
            assertEquals(50, HorizonRadioClient.queueLimit());
            HorizonRadioClient
                .handleServerSettings(new ServerSettingsPacket(false, 125, 15, ServerSettingsPacket.SYNC));
            assertEquals(125, HorizonRadioClient.queueLimit());
            HorizonRadioClient.handleServerSettings(new ServerSettingsPacket(true, 20, 15, ServerSettingsPacket.SAVED));
            assertEquals(20, HorizonRadioClient.queueLimit());
            HorizonRadioClient.setPlaybackMode(PlaybackMode.PRIVATE);
            assertEquals(50, HorizonRadioClient.queueLimit());
            HorizonRadioClient.setPlaybackMode(PlaybackMode.SERVER);
            assertEquals(20, HorizonRadioClient.queueLimit());
            HorizonRadioClient.resetServerSettings();
            assertEquals(50, HorizonRadioClient.queueLimit());
        } finally {
            HorizonRadioClient.setPlaybackMode(previous);
        }
    }

    @Test
    public void invalidNumericInputIsRejectedWithoutOverflowOrClamping() {
        assertEquals(0, HorizonRadioSettingsScreen.parsePositiveInteger(""));
        assertEquals(0, HorizonRadioSettingsScreen.parsePositiveInteger("-5"));
        assertEquals(0, HorizonRadioSettingsScreen.parsePositiveInteger("9999999999"));
        assertEquals(0, HorizonRadioSettingsScreen.parsePositiveInteger("abc"));
        assertEquals(125, HorizonRadioSettingsScreen.parsePositiveInteger("125"));
    }

    private static GuiTextField field(HorizonRadioSettingsScreen screen, String name) throws Exception {
        Field field = HorizonRadioSettingsScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return (GuiTextField) field.get(screen);
    }

    private static GuiButton button(GuiScreen screen, int id) throws Exception {
        Field field = GuiScreen.class.getDeclaredField("buttonList");
        field.setAccessible(true);
        for (Object value : (List<?>) field.get(screen)) {
            GuiButton button = (GuiButton) value;
            if (button.id == id) return button;
        }
        return null;
    }
}
