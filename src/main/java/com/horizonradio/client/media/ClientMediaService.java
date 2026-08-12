package com.horizonradio.client.media;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.PlaylistImportService;
import com.horizonradio.server.AudioDownloadService;
import com.horizonradio.server.RadioBrowserService;
import com.horizonradio.server.YouTubeService;
import com.horizonradio.server.media.MediaException;
import com.horizonradio.server.media.YouTubeUrlParser;

/**
 * Client-side discovery facade for public YouTube and Radio Browser metadata.
 * It performs no Minecraft packet or server-manager operations.
 */
public final class ClientMediaService implements ClientMetadataCache.MetadataProvider {

    private final RemoteProvider remoteProvider;
    private final AudioDownloadService metadataService;

    public ClientMediaService(RemoteProvider remoteProvider) {
        if (remoteProvider == null) {
            throw new IllegalArgumentException("remote provider is required");
        }
        this.remoteProvider = remoteProvider;
        this.metadataService = null;
    }

    public ClientMediaService(YouTubeService youTubeService, AudioDownloadService audioDownloadService,
        RadioBrowserService radioBrowserService) {
        if (audioDownloadService == null) {
            throw new IllegalArgumentException("audio download service is required");
        }
        this.remoteProvider = productionProvider(youTubeService, audioDownloadService, radioBrowserService);
        this.metadataService = audioDownloadService;
    }

    public static RemoteProvider productionProvider(YouTubeService youTubeService,
        AudioDownloadService audioDownloadService, RadioBrowserService radioBrowserService) {
        if (youTubeService == null || audioDownloadService == null || radioBrowserService == null) {
            throw new IllegalArgumentException("client media services are required");
        }
        return new ProductionRemoteProvider(youTubeService, audioDownloadService, radioBrowserService);
    }

    public CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs) {
        return remoteProvider.search(query, maxDurationMs);
    }

    public CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region) {
        return remoteProvider.fetchCharts(region);
    }

    public CompletableFuture<List<SearchResult>> importPlaylist(String playlistUrl) {
        return remoteProvider.extractPlaylistJson(playlistUrl)
            .thenApply(new Function<String, List<SearchResult>>() {

                @Override
                public List<SearchResult> apply(String json) {
                    return PlaylistImportService.parse(json);
                }
            });
    }

    public CompletableFuture<SearchResult> importVideo(String videoUrl) {
        return remoteProvider.extractVideoJson(videoUrl)
            .thenApply(new Function<String, SearchResult>() {

                @Override
                public SearchResult apply(String json) {
                    return PlaylistImportService.parseVideo(json);
                }
            });
    }

    public CompletableFuture<List<RadioStation>> searchRadio(String query) {
        return remoteProvider.searchRadio(query)
            .thenApply(new Function<List<RadioStation>, List<RadioStation>>() {

                @Override
                public List<RadioStation> apply(List<RadioStation> stations) {
                    return sanitizeStations(stations);
                }
            });
    }

    @Override
    public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
        return remoteProvider.lookupRadio(stationUuid)
            .thenApply(new Function<RadioStation, RadioStation>() {

                @Override
                public RadioStation apply(RadioStation station) {
                    return RadioBrowserService.sanitizeForPublication(station);
                }
            });
    }

    @Override
    public CompletableFuture<SearchResult> resolveVideo(String videoId) {
        if (metadataService != null) {
            return metadataService.resolveVideoMetadata(videoId);
        }
        try {
            String safeVideoId = YouTubeUrlParser.requireVideoId(videoId);
            return importVideo("https://www.youtube.com/watch?v=" + safeVideoId);
        } catch (MediaException exception) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<RadioStation> sanitizeStations(List<RadioStation> stations) {
        List<RadioStation> sanitized = new ArrayList<RadioStation>();
        if (stations == null) {
            return sanitized;
        }
        for (RadioStation station : stations) {
            RadioStation safeStation = RadioBrowserService.sanitizeForPublication(station);
            if (safeStation != null) {
                sanitized.add(safeStation);
            }
        }
        return sanitized;
    }

    public interface RemoteProvider {

        CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs);

        CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region);

        CompletableFuture<String> extractPlaylistJson(String playlistUrl);

        CompletableFuture<String> extractVideoJson(String videoUrl);

        CompletableFuture<List<RadioStation>> searchRadio(String query);

        CompletableFuture<RadioStation> lookupRadio(String stationUuid);
    }

    private static final class ProductionRemoteProvider implements RemoteProvider {

        private final YouTubeService youTubeService;
        private final AudioDownloadService audioDownloadService;
        private final RadioBrowserService radioBrowserService;

        private ProductionRemoteProvider(YouTubeService youTubeService, AudioDownloadService audioDownloadService,
            RadioBrowserService radioBrowserService) {
            this.youTubeService = youTubeService;
            this.audioDownloadService = audioDownloadService;
            this.radioBrowserService = radioBrowserService;
        }

        @Override
        public CompletableFuture<List<SearchResult>> search(String query, long maxDurationMs) {
            return youTubeService.search(query, maxDurationMs);
        }

        @Override
        public CompletableFuture<List<SearchResult>> fetchCharts(ChartRegion region) {
            return youTubeService.fetchTopCharts(region);
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            return audioDownloadService.extractPlaylistJson(playlistUrl);
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            return audioDownloadService.extractVideoJson(videoUrl);
        }

        @Override
        public CompletableFuture<List<RadioStation>> searchRadio(String query) {
            return radioBrowserService.search(query);
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            return radioBrowserService.lookup(stationUuid);
        }
    }
}
