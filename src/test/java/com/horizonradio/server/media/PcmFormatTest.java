package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class PcmFormatTest {

    @Test
    public void normalizedFormatIsTheServerPcmContract() {
        PcmFormat format = PcmFormat.normalized();

        assertEquals(44100, format.getSampleRate());
        assertEquals(2, format.getChannels());
        assertEquals(16, format.getSampleSizeBits());
        assertTrue(format.isSigned());
        assertTrue(format.isLittleEndian());
        assertEquals(4, format.getFrameSize());
    }

    @Test
    public void rejectsNonPositiveSampleRates() {
        assertInvalidFormat(0, 2);
        assertInvalidFormat(-1, 2);
    }

    @Test
    public void rejectsChannelsOutsideMonoAndStereo() {
        assertInvalidFormat(44100, 0);
        assertInvalidFormat(44100, 3);
    }

    private static void assertInvalidFormat(int sampleRate, int channels) {
        try {
            new PcmFormat(sampleRate, channels, 16, true, true);
            fail("Expected invalid PCM format to be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(
                expected.getMessage()
                    .isEmpty());
        }
    }
}
