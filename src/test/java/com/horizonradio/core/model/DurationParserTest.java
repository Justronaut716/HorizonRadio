package com.horizonradio.core.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DurationParserTest {

    @Test
    public void parsesMinutesAndSeconds() {
        assertEquals(225000L, DurationParser.parseMillis("3:45"));
    }

    @Test
    public void parsesHoursMinutesAndSeconds() {
        assertEquals(3750000L, DurationParser.parseMillis("1:02:30"));
    }

    @Test
    public void strictlyRejectsMissingOrMalformedDurations() {
        assertEquals(-1L, DurationParser.parseMillisStrict(null));
        assertEquals(-1L, DurationParser.parseMillisStrict(""));
        assertEquals(-1L, DurationParser.parseMillisStrict("not-a-duration"));
        assertEquals(900000L, DurationParser.parseMillisStrict("15:00"));
    }

    @Test
    public void usesFallbackForEmptyDuration() {
        assertEquals(180000L, DurationParser.parseMillis(""));
    }

    @Test
    public void usesFallbackForNullDuration() {
        assertEquals(180000L, DurationParser.parseMillis(null));
    }

    @Test
    public void usesFallbackForMalformedDuration() {
        assertEquals(180000L, DurationParser.parseMillis("not-a-duration"));
    }
}
