package com.horizonradio.server;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

import com.horizonradio.HorizonRadio;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.server.PlaylistState;
import com.horizonradio.network.packets.ServerSettingsPacket;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import sun.misc.Unsafe;

public class OperatorSettingsTest {

    @Test
    public void operatorChangesArePersistedAndImmediatelyEnforced() throws Exception {
        HorizonRadioConfig previous = HorizonRadio.getConfig();
        File directory = Files.createTempDirectory("radio-op-settings")
            .toFile();
        setConfig(HorizonRadioConfig.load(directory));
        List<IMessage> packets = new ArrayList<IMessage>();
        PlaylistManager manager = new PlaylistManager(null, directory, (packet, players) -> packets.add(packet));
        try {
            OperatorPlayer player = player(true);
            manager.handleServerSettings(player, true, 1, 2);
            assertEquals(ServerSettingsPacket.SAVED, last(packets).getStatus());
            assertTrue(last(packets).canEdit());
            HorizonRadioConfig saved = HorizonRadioConfig.load(directory);
            assertEquals(1, saved.getMaxPlaylistSize());
            assertEquals(2, saved.getMaxTrackDurationMinutes());
            manager.handleAddToPlaylist(player, "abcdefghijk", 120_000L);
            assertEquals(0, state(manager).size());
            manager.handleAddToPlaylist(player, "abcdefghijk", 119_999L);
            manager.handleAddToPlaylist(player, "lmnopqrstuv", 60_000L);
            assertEquals(1, state(manager).size());
            manager.handleServerSettings(player, true, 2, 3);
            manager.handleAddToPlaylist(player, "lmnopqrstuv", 120_000L);
            assertEquals(2, state(manager).size());
            manager.handleServerSettings(player, true, 1, 1);
            assertEquals(2, state(manager).size());
        } finally {
            manager.shutdown();
            setConfig(previous);
        }
    }

    @Test
    public void nonOperatorsAndRevokedOperatorsCannotChangeLimits() throws Exception {
        HorizonRadioConfig previous = HorizonRadio.getConfig();
        File directory = Files.createTempDirectory("radio-op-settings")
            .toFile();
        setConfig(HorizonRadioConfig.load(directory));
        List<IMessage> packets = new ArrayList<IMessage>();
        PlaylistManager manager = new PlaylistManager(null, directory, (packet, players) -> packets.add(packet));
        try {
            OperatorPlayer player = player(false);
            manager.handleServerSettings(player, true, 1, 1);
            assertEquals(ServerSettingsPacket.DENIED, last(packets).getStatus());
            assertFalse(last(packets).canEdit());
            assertEquals(50, last(packets).getQueueLimit());
            player.operator = true;
            manager.handleServerSettings(player, false, 0, 0);
            assertTrue(last(packets).canEdit());
            player.operator = false;
            manager.handleServerSettings(player, true, 1, 1);
            assertEquals(ServerSettingsPacket.DENIED, last(packets).getStatus());
            assertEquals(
                50,
                HorizonRadioConfig.load(directory)
                    .getMaxPlaylistSize());
        } finally {
            manager.shutdown();
            setConfig(previous);
        }
    }

    @Test
    public void invalidValuesAndSaveFailuresLeaveLiveLimitsUntouched() throws Exception {
        HorizonRadioConfig previous = HorizonRadio.getConfig();
        setConfig(HorizonRadioConfig.load(null));
        File notDirectory = Files.createTempFile("radio-settings", ".tmp")
            .toFile();
        List<IMessage> packets = new ArrayList<IMessage>();
        PlaylistManager manager = new PlaylistManager(null, notDirectory, (packet, players) -> packets.add(packet));
        try {
            OperatorPlayer player = player(true);
            for (int[] invalid : new int[][] { { 0, 1 }, { -1, 1 }, { 1025, 1 }, { 1, 0 }, { 1, -1 } }) {
                manager.handleServerSettings(player, true, invalid[0], invalid[1]);
                assertEquals(ServerSettingsPacket.INVALID, last(packets).getStatus());
                assertEquals(50, last(packets).getQueueLimit());
            }
            manager.handleServerSettings(player, true, 1, 1);
            assertEquals(ServerSettingsPacket.SAVE_FAILED, last(packets).getStatus());
            assertEquals(50, last(packets).getQueueLimit());
            assertEquals(15, last(packets).getDurationMinutes());
        } finally {
            manager.shutdown();
            setConfig(previous);
        }
    }

    private static void setConfig(HorizonRadioConfig config) throws Exception {
        java.lang.reflect.Method method = HorizonRadio.class.getDeclaredMethod("setConfig", HorizonRadioConfig.class);
        method.setAccessible(true);
        method.invoke(null, config);
    }

    private static ServerSettingsPacket last(List<IMessage> packets) {
        return (ServerSettingsPacket) packets.get(packets.size() - 1);
    }

    private static PlaylistState state(PlaylistManager manager) throws Exception {
        Field field = PlaylistManager.class.getDeclaredField("state");
        field.setAccessible(true);
        return (PlaylistState) field.get(manager);
    }

    private static OperatorPlayer player(boolean operator) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        OperatorPlayer player = (OperatorPlayer) ((Unsafe) unsafeField.get(null))
            .allocateInstance(OperatorPlayer.class);
        Field profile = EntityPlayer.class.getDeclaredField("field_146106_i");
        profile.setAccessible(true);
        profile.set(player, new GameProfile(java.util.UUID.randomUUID(), "operator"));
        player.operator = operator;
        return player;
    }

    public static class OperatorPlayer extends EntityPlayerMP {

        boolean operator;

        public OperatorPlayer() {
            super(null, null, null, null);
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            assertEquals(0, level);
            assertEquals("horizonradio", command);
            return operator;
        }
    }
}
