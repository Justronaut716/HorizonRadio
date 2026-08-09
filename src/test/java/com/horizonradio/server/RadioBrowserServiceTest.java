package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;

public class RadioBrowserServiceTest {

    @Test
    public void parseStationsKeepsUniqueWorkingStationsWithResolvedUrls() throws IOException {
        List<RadioStation> stations = RadioBrowserService.parseStations(readFixture());
        assertEquals(50, stations.size());
        assertEquals(
            "station-1",
            stations.get(0)
                .getStationUuid());
        assertEquals(
            "Example Radio",
            stations.get(0)
                .getName());
        assertEquals(
            "https://stream.example/radio",
            stations.get(0)
                .getStreamUrl());
    }

    @Test
    public void parseStationsRejectsBrokenOrIncompleteEntries() throws IOException {
        List<RadioStation> stations = RadioBrowserService.parseStations(readFixture());
        assertFalse(containsUuid(stations, "broken"));
        assertFalse(containsUuid(stations, "missing-name"));
        assertFalse(containsUuid(stations, "missing-url"));
    }

    @Test
    public void parseStationsRejectsNonHttpStreamUrls() {
        List<RadioStation> stations = RadioBrowserService.parseStations(
            "[{\"stationuuid\":\"ftp\",\"name\":\"FTP station\",\"url_resolved\":\"ftp://stream.example/radio\","
                + "\"lastcheckok\":1,\"hls\":0}]");

        assertTrue(stations.isEmpty());
    }

    @Test
    public void buildSearchUriEncodesQueryAndAppliesSearchBounds() {
        URI uri = RadioBrowserService
            .buildSearchUri(URI.create("https://de1.api.radio-browser.info/"), "rock & roll", false);

        assertEquals("/json/stations/search", uri.getPath());
        assertEquals("hidebroken=true&limit=50&name=rock+%26+roll", uri.getRawQuery());
    }

    @Test
    public void buildSearchUriRequestsPopularStationsWhenQueryIsEmpty() {
        URI uri = RadioBrowserService.buildSearchUri(URI.create("https://de1.api.radio-browser.info"), "", true);

        assertEquals("hidebroken=true&limit=50&order=votes&reverse=true", uri.getRawQuery());
        assertTrue(
            uri.toString()
                .startsWith("https://de1.api.radio-browser.info/json/stations/search?"));
    }

    @Test
    public void buildSearchUriTrimsBeforeApplyingTheQueryLimit() {
        URI uri = RadioBrowserService.buildSearchUri(
            URI.create("https://de1.api.radio-browser.info/"),
            "  " + repeat("a", 100) + "ignored  ",
            false);

        assertEquals("hidebroken=true&limit=50&name=" + repeat("a", 100), uri.getRawQuery());
    }

    @Test
    public void parseStationsUtf8BoundsMultibyteNamesForRadioStatePublication() {
        List<RadioStation> stations = RadioBrowserService.parseStations(
            "[{\"stationuuid\":\"bounded-name\",\"name\":\"" + repeat("界", 100)
                + "\",\"url_resolved\":\"https://stream.example/radio\",\"lastcheckok\":1,\"hls\":0}]");

        assertEquals(1, stations.size());
        assertEquals(
            repeat("界", 50),
            stations.get(0)
                .getName());
        assertTrue(
            ("Playing " + stations.get(0)
                .getName()).getBytes(StandardCharsets.UTF_8).length <= 160);
    }

    @Test
    public void parseStationsRejectsUuidThatCannotBePublished() {
        List<RadioStation> stations = RadioBrowserService.parseStations(
            "[{\"stationuuid\":\"" + repeat("u", 65)
                + "\",\"name\":\"Station\",\"url_resolved\":\"https://stream.example/radio\","
                + "\"lastcheckok\":1,\"hls\":0}]");

        assertTrue(stations.isEmpty());
    }

    @Test
    public void parseStationsCollapsesDuplicateVisibleNamesIgnoringCaseAndWhitespace() {
        List<RadioStation> stations = RadioBrowserService.parseStations(
            "[" + "{\"stationuuid\":\"bigfm-1\",\"name\":\"BigFM Deutscher Hip-Hop\","
                + "\"url_resolved\":\"https://stream.example/bigfm-1\",\"lastcheckok\":1,\"hls\":0},"
                + "{\"stationuuid\":\"bigfm-2\",\"name\":\" bigfm   deutscher hip-hop \","
                + "\"url_resolved\":\"https://stream.example/bigfm-2\",\"lastcheckok\":1,\"hls\":0},"
                + "{\"stationuuid\":\"other\",\"name\":\"Other Station\","
                + "\"url_resolved\":\"https://stream.example/other\",\"lastcheckok\":1,\"hls\":0}"
                + "]");

        assertEquals(2, stations.size());
        assertEquals(
            "bigfm-1",
            stations.get(0)
                .getStationUuid());
        assertEquals(
            "other",
            stations.get(1)
                .getStationUuid());
    }

    private boolean containsUuid(List<RadioStation> stations, String stationUuid) {
        for (RadioStation station : stations) {
            if (stationUuid.equals(station.getStationUuid())) {
                return true;
            }
        }
        return false;
    }

    private String readFixture() throws IOException {
        InputStream stream = getClass()
            .getResourceAsStream("/com/horizonradio/server/radio-browser-search-response.json");
        if (stream == null) {
            throw new AssertionError("Radio Browser fixture is missing");
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

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
