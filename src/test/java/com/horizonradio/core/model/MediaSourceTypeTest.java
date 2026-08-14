package com.horizonradio.core.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaSourceTypeTest {

    @Test
    public void usesStableWireValuesAndRejectsUnknownValues() {
        assertEquals(1, MediaSourceType.YOUTUBE.getWireValue());
        assertEquals(2, MediaSourceType.RADIO.getWireValue());
        assertEquals(MediaSourceType.YOUTUBE, MediaSourceType.fromWireValue((byte) 1));
        assertEquals(MediaSourceType.RADIO, MediaSourceType.fromWireValue((byte) 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownWireValues() {
        MediaSourceType.fromWireValue((byte) 99);
    }
}
