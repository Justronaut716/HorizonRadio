package com.horizonradio.client.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;

public class ClientMediaServiceTest {

    @Test
    public void searchUsesInjectedClientProviderAndReturnsMetadataLocally() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.searchResults = Arrays.asList(new SearchResult("video-id", "Title", "Channel", "1:00", "thumb"));

        ClientMediaService service = new ClientMediaService(provider);

        assertEquals(provider.searchResults, service.search("query", 900_000L).get());
        assertEquals("query", provider.lastQuery);
        assertEquals(900_000L, provider.lastMaxDurationMs);
    }

    @Test
    public void importsProviderJsonWithSharedPlaylistParser() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.playlistJson = "{\"entries\":[{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Imported\",\"duration\":60}]}";
        provider.videoJson = "{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Video\",\"duration\":90}";
        ClientMediaService service = new ClientMediaService(provider);

        assertEquals(
            Arrays.asList(new SearchResult("dQw4w9WgXcQ", "Imported", "", "1:00", "")),
            service.importPlaylist("https://www.youtube.com/playlist?list=PL1").get());
        assertEquals(new SearchResult("dQw4w9WgXcQ", "Video", "", "1:30", ""), service.importVideo("https://youtu.be/dQw4w9WgXcQ").get());
    }

    @Test
    public void lookupRadioRejectsUnsuitableStationBeforeLocalUse() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.station = new RadioStation("station-id", "Broken", "ftp://example.invalid/stream", true, false);

        assertNull(new ClientMediaService(provider).lookupRadio("station-id").get());
    }

    private static final class FakeProvider implements ClientMediaService.RemoteProvider {

        private List<SearchResult> searchResults = Collections.emptyList();
        private String playlistJson;
        private String videoJson;
        private RadioStation station;
        private String lastQuery;
        private long lastMaxDurationMs;

        @Override
        public CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs) {
            lastQuery = query;
            lastMaxDurationMs = maxDurationMs;
            return CompletableFuture.completedFuture(searchResults);
        }

        @Override
        public CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region) {
            return CompletableFuture.completedFuture(new ArrayList<SearchResult>());
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            return CompletableFuture.completedFuture(playlistJson);
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            return CompletableFuture.completedFuture(videoJson);
        }

        @Override
        public CompletableFuture<List<RadioStation>> searchRadio(String query) {
            return CompletableFuture.completedFuture(new ArrayList<RadioStation>());
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            return CompletableFuture.completedFuture(station);
        }
    }
}
