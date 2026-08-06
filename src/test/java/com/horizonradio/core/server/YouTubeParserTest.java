package com.horizonradio.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import com.horizonradio.core.model.SearchResult;
import com.horizonradio.server.YouTubeService;

public class YouTubeParserTest {

    @Test
    public void parsesNestedVideoMetadataAndReturnsAvailableResults() throws Exception {
        List<SearchResult> results = YouTubeService.parseResults(readFixture());

        assertEquals(11, results.size());
        assertEquals(
            new SearchResult(
                "complete01",
                "Complete title",
                "Complete channel",
                "4:05",
                "https://img.example/large.jpg"),
            results.get(0));
        assertEquals(new SearchResult("missing02", "Missing optional fields", "", "", ""), results.get(1));
        assertEquals(
            "video11",
            results.get(10)
                .getVideoId());
    }

    @Test
    public void malformedResponseProducesEmptyResults() {
        assertTrue(
            YouTubeService.parseResults("{not valid json")
                .isEmpty());
    }

    @Test
    public void parsesPlaylistEntriesAndRecognizesPlaylistUrls() {
        List<SearchResult> results = PlaylistImportService.parse(
            "{\"entries\":[" + "{\"id\":\"one\",\"title\":\"One\",\"duration\":65},"
                + "{\"id\":\"two\",\"title\":\"Two\",\"duration_string\":\"2:03\"}"
                + "]}");

        assertTrue(PlaylistImportService.isPlaylistUrl("https://www.youtube.com/watch?v=one&list=PLtest"));
        assertTrue(PlaylistImportService.isPlaylistUrl("https://youtu.be/one?list=RDtest"));
        assertTrue(!PlaylistImportService.isPlaylistUrl("https://example.com/?list=PLtest"));
        assertEquals(2, results.size());
        assertEquals(
            "1:05",
            results.get(0)
                .getDuration());
        assertEquals(
            "2:03",
            results.get(1)
                .getDuration());
        SearchResult video = PlaylistImportService
            .parseVideo("{\"id\":\"single\",\"title\":\"Single\",\"duration\":95}");
        assertEquals("single", video.getVideoId());
        assertEquals("1:35", video.getDuration());
        assertTrue(PlaylistImportService.isVideoUrl("https://youtu.be/single"));
        assertTrue(PlaylistImportService.isVideoUrl("https://www.youtube.com/watch?v=single"));
        assertTrue(!PlaylistImportService.isVideoUrl("https://www.youtube.com/watch?v=single&list=PLtest"));

        List<SearchResult> charts = YouTubeService.parseGermanTopCharts(
            "{\"contents\":{\"sectionListRenderer\":{\"contents\":[{"
                + "\"musicAnalyticsSectionRenderer\":{\"content\":{\"trackTypes\":[{"
                + "\"listType\":\"TOP_VIEWS_CHART\","
                + "\"chartPeriodType\":\"CHART_PERIOD_TYPE_WEEKLY\","
                + "\"trackViews\":[{\"encryptedVideoId\":\"chart01\","
                + "\"name\":\"Chart Song\",\"artists\":[{\"name\":\"Artist\"}],"
                + "\"chartEntryMetadata\":{\"currentPosition\":1}}]"
                + "}]}}}]}}}");
        assertEquals(1, charts.size());
        assertEquals(
            "chart01",
            charts.get(0)
                .getVideoId());
        assertEquals(
            "Artist",
            charts.get(0)
                .getChannel());
        assertEquals(
            "2:03",
            PlaylistImportService.parseDurationOutput("chart01\\t2:03\nchart02\\tNA")
                .get("chart01"));
    }

    private String readFixture() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/com/horizonradio/server/youtube-search-response.json");
        if (stream == null) {
            throw new AssertionError("YouTube fixture is missing");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder contents = new StringBuilder();
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                contents.append(buffer, 0, count);
            }
            return contents.toString();
        }
    }
}
