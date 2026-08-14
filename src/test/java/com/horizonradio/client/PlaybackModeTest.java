package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PlaybackModeTest {

    @Test
    public void persistedNamesRoundTripAndGroupIsNotSelectable() {
        assertEquals(PlaybackMode.PRIVATE, PlaybackMode.fromPersistedName("private"));
        assertEquals("server", PlaybackMode.SERVER.getPersistedName());
        assertFalse(PlaybackMode.GROUP.isSelectable());
    }
}
