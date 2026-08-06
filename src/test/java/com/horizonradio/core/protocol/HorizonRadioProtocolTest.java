package com.horizonradio.core.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HorizonRadioProtocolTest {

    @Test
    public void onePointZeroUsesVersionedProtocol() {
        assertEquals("1.0.0", HorizonRadioProtocol.VERSION);
        assertEquals("horizonradio_1_0", HorizonRadioProtocol.CHANNEL_NAME);
    }
}
