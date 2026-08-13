package com.horizonradio.client.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;

public class ClientMetadataCacheTest {

    @Test
    public void metadataCacheSharesConcurrentVideoLookup() throws Exception {
        FakeMediaService service = new FakeMediaService();
        ClientMetadataCache cache = new ClientMetadataCache(service);

        CompletableFuture<SearchResult> first = cache.video("video-id");
        CompletableFuture<SearchResult> second = cache.video("video-id");

        assertSame(first, second);
        assertEquals(1, service.videoLookupCalls);
    }

    @Test
    public void metadataCacheTracksLocalLoadingSuccessAndFailure() throws Exception {
        FakeMediaService service = new FakeMediaService();
        ClientMetadataCache cache = new ClientMetadataCache(service);

        CompletableFuture<SearchResult> pending = cache.video("video-id");
        assertTrue(cache.isVideoLoading("video-id"));
        assertNull(cache.getVideoError("video-id"));
        SearchResult expected = new SearchResult("video-id", "Title", "Channel", "1:00", "thumb");
        pending.complete(expected);
        pending.get();
        assertFalse(cache.isVideoLoading("video-id"));
        assertEquals(expected, cache.getVideo("video-id"));

        CompletableFuture<RadioStation> failed = cache.station("station-id");
        service.stationFutures.get("station-id")
            .completeExceptionally(new IllegalStateException("offline"));
        try {
            failed.get();
        } catch (Exception expectedFailure) {
            // The failure remains local and is exposed through the cache state.
        }
        assertFalse(cache.isStationLoading("station-id"));
        assertEquals(
            "offline",
            cache.getStationError("station-id")
                .getMessage());
    }

    private static final class FakeMediaService implements ClientMetadataCache.MetadataProvider {

        private int videoLookupCalls;
        private final Map<String, CompletableFuture<SearchResult>> videoFutures = new HashMap<String, CompletableFuture<SearchResult>>();
        private final Map<String, CompletableFuture<RadioStation>> stationFutures = new HashMap<String, CompletableFuture<RadioStation>>();

        @Override
        public CompletableFuture<SearchResult> resolveVideo(String videoId) {
            videoLookupCalls++;
            CompletableFuture<SearchResult> future = new CompletableFuture<SearchResult>();
            videoFutures.put(videoId, future);
            return future;
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            CompletableFuture<RadioStation> future = new CompletableFuture<RadioStation>();
            stationFutures.put(stationUuid, future);
            return future;
        }
    }
}
