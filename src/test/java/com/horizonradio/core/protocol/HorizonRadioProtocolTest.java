package com.horizonradio.core.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

import com.horizonradio.Tags;

public class HorizonRadioProtocolTest {

    @Test
    public void modVersionComesFromGeneratedBuildTag() {
        assertEquals(Tags.VERSION, HorizonRadioProtocol.VERSION);
        assertEquals("horizonradio_1_0", HorizonRadioProtocol.CHANNEL_NAME);
    }

    @Test
    public void productionProtocolRegistersTwentyFourMessagesIncludingRevisionedQueuePackets() throws IOException {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/horizonradio/network/HorizonRadioNetwork.java")),
            Charset.forName("UTF-8"));

        assertEquals(24, countOccurrences(source, "registerMessage("));
        assertTrue(source.contains("PlaylistDeltaPacket.class,\n            36,\n            Side.CLIENT"));
        assertTrue(source.contains("PlaylistResyncRequestPacket.class,\n            37,\n            Side.SERVER"));
        assertFalse(source.contains("AudioChunkPacket.class"));
        assertFalse(source.contains("ChartAddCompletionPacket.class"));
        assertFalse(source.contains("NowPlayingPacket.class"));
        assertFalse(source.contains("RadioAudioStartPacket.class"));
        assertFalse(source.contains("RadioAudioChunkPacket.class"));
        assertFalse(source.contains("RadioStatePacket.class"));
        assertFalse(source.contains("SearchResultsPacket.class"));
        assertFalse(source.contains("RadioSearchResultsPacket.class"));
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
