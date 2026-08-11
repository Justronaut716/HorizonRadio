package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class YouTubeUrlParserTest {

    @Test
    public void extractsVideoIdsFromSupportedYouTubeUrls() throws Exception {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeUrlParser.parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123"));
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.parseVideoId("https://youtu.be/dQw4w9WgXcQ?t=12"));
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeUrlParser.parseVideoId("https://m.youtube.com/shorts/dQw4w9WgXcQ?feature=share"));
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.parseVideoId("https://www.youtube.com/live/dQw4w9WgXcQ"));
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeUrlParser.parseVideoId("https://youtube.com/watch?list=PL123&v=dQw4w9WgXcQ"));
    }

    @Test
    public void rejectsUnsafeOrInvalidVideoUrls() throws Exception {
        assertRejected("https://example.com/watch?v=dQw4w9WgXcQ");
        assertRejected("https://www.youtube.com/watch?v=too-short");
        assertRejected("https://www.youtube.com/watch?v=dQw4w9WgXcQ%2Fetc");
        assertRejected("https://www.youtube.com/watch?redirect=https://evil.example/&v=dQw4w9WgXcQ");
        StringBuilder oversized = new StringBuilder("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        while (oversized.length() <= 2048) {
            oversized.append('x');
        }
        assertRejected(oversized.toString());
    }

    private static void assertRejected(String url) throws Exception {
        try {
            YouTubeUrlParser.parseVideoId(url);
            fail("Expected unsafe YouTube URL to be rejected");
        } catch (MediaException expected) {
            // The parser must reject before any request can be made.
        }
    }
}
