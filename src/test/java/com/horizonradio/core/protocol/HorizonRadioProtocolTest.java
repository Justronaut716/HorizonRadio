package com.horizonradio.core.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.horizonradio.Tags;

public class HorizonRadioProtocolTest {

    @Test
    public void modVersionComesFromGeneratedBuildTag() {
        assertEquals(Tags.VERSION, HorizonRadioProtocol.VERSION);
        assertEquals("horizonradio_1_0", HorizonRadioProtocol.CHANNEL_NAME);
    }
}
