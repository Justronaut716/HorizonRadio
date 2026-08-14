package com.horizonradio.client.media;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;

/** Local, future-backed metadata cache for compact server queue identifiers. */
public final class ClientMetadataCache {

    private final MetadataProvider provider;
    private final ConcurrentMap<String, CompletableFuture<SearchResult>> videoLookups = new ConcurrentHashMap<String, CompletableFuture<SearchResult>>();
    private final ConcurrentMap<String, CompletableFuture<RadioStation>> stationLookups = new ConcurrentHashMap<String, CompletableFuture<RadioStation>>();
    private final ConcurrentMap<String, SearchResult> videos = new ConcurrentHashMap<String, SearchResult>();
    private final ConcurrentMap<String, RadioStation> stations = new ConcurrentHashMap<String, RadioStation>();
    private final ConcurrentMap<String, Throwable> videoErrors = new ConcurrentHashMap<String, Throwable>();
    private final ConcurrentMap<String, Throwable> stationErrors = new ConcurrentHashMap<String, Throwable>();

    public ClientMetadataCache(MetadataProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("metadata provider is required");
        }
        this.provider = provider;
    }

    public CompletableFuture<SearchResult> video(final String videoId) {
        return videoLookups.computeIfAbsent(videoId, new Function<String, CompletableFuture<SearchResult>>() {

            @Override
            public CompletableFuture<SearchResult> apply(final String id) {
                CompletableFuture<SearchResult> lookup = provider.resolveVideo(id);
                if (lookup == null) {
                    lookup = CompletableFuture.completedFuture(null);
                }
                lookup.whenComplete(new java.util.function.BiConsumer<SearchResult, Throwable>() {

                    @Override
                    public void accept(SearchResult result, Throwable failure) {
                        if (failure != null) {
                            videoErrors.put(id, unwrap(failure));
                        } else if (result != null) {
                            videos.put(id, result);
                        }
                    }
                });
                return lookup;
            }
        });
    }

    public CompletableFuture<RadioStation> station(final String stationUuid) {
        return stationLookups.computeIfAbsent(stationUuid, new Function<String, CompletableFuture<RadioStation>>() {

            @Override
            public CompletableFuture<RadioStation> apply(final String id) {
                CompletableFuture<RadioStation> lookup = provider.lookupRadio(id);
                if (lookup == null) {
                    lookup = CompletableFuture.completedFuture(null);
                }
                lookup.whenComplete(new java.util.function.BiConsumer<RadioStation, Throwable>() {

                    @Override
                    public void accept(RadioStation result, Throwable failure) {
                        if (failure != null) {
                            stationErrors.put(id, unwrap(failure));
                        } else if (result != null) {
                            stations.put(id, result);
                        }
                    }
                });
                return lookup;
            }
        });
    }

    public SearchResult getVideo(String videoId) {
        return videos.get(videoId);
    }

    public RadioStation getStation(String stationUuid) {
        return stations.get(stationUuid);
    }

    public boolean isVideoLoading(String videoId) {
        CompletableFuture<SearchResult> lookup = videoLookups.get(videoId);
        return lookup != null && !lookup.isDone();
    }

    public boolean isStationLoading(String stationUuid) {
        CompletableFuture<RadioStation> lookup = stationLookups.get(stationUuid);
        return lookup != null && !lookup.isDone();
    }

    public Throwable getVideoError(String videoId) {
        return videoErrors.get(videoId);
    }

    public Throwable getStationError(String stationUuid) {
        return stationErrors.get(stationUuid);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    public interface MetadataProvider {

        CompletableFuture<SearchResult> resolveVideo(String videoId);

        CompletableFuture<RadioStation> lookupRadio(String stationUuid);
    }
}
