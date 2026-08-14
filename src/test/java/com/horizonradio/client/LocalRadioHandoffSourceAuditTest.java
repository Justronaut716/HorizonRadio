package com.horizonradio.client;

import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the production local-radio path from falling back to retired packet adapters. */
public class LocalRadioHandoffSourceAuditTest {

    @Test
    public void localRadioLifecycleUsesInternalPresentationAndPcmApis() throws IOException {
        assertNoLegacyPacketConstruction("src/main/java/com/horizonradio/client/HorizonRadioClient.java");
        assertNoLegacyPacketConstruction("src/main/java/com/horizonradio/client/audio/AudioPlayer.java");

        String clientProxy = readSource("src/main/java/com/horizonradio/client/ClientProxy.java");
        String playback = readSource("src/main/java/com/horizonradio/client/audio/ClientRadioPlayback.java");
        assertFalse(clientProxy.contains("startLocalRadio("));
        assertFalse(clientProxy.contains("receiveLocalRadioPcm("));
        assertFalse(playback.contains("startLocalRadio("));
        assertFalse(playback.contains("receiveLocalRadioPcm("));
    }

    private static void assertNoLegacyPacketConstruction(String path) throws IOException {
        String source = readSource(path);
        assertFalse(source.contains("new RadioStatePacket("));
        assertFalse(source.contains("new RadioAudioStartPacket("));
        assertFalse(source.contains("new RadioAudioChunkPacket("));
    }

    private static String readSource(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
