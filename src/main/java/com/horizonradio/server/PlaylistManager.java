package com.horizonradio.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.horizonradio.HorizonRadio;
import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.model.DurationParser;
import com.horizonradio.core.model.PlaylistEntry;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartCache;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.core.server.MusicSearchFilter;
import com.horizonradio.core.server.PlaylistImportService;
import com.horizonradio.core.server.PlaylistState;
import com.horizonradio.core.server.RadioPlaybackState;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.network.PacketBufferUtil;
import com.horizonradio.network.packets.AddChartsToPlaylistPacket;
import com.horizonradio.network.packets.AudioChunkPacket;
import com.horizonradio.network.packets.ChartAddCompletionPacket;
import com.horizonradio.network.packets.LoopStatePacket;
import com.horizonradio.network.packets.NowPlayingPacket;
import com.horizonradio.network.packets.PausePacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
import com.horizonradio.network.packets.RadioAudioChunkPacket;
import com.horizonradio.network.packets.RadioAudioStartPacket;
import com.horizonradio.network.packets.RadioSearchResultsPacket;
import com.horizonradio.network.packets.RadioStatePacket;
import com.horizonradio.network.packets.ResumePacket;
import com.horizonradio.network.packets.SearchResultsPacket;
import com.horizonradio.network.packets.ShuffleStatePacket;

/** Server-authoritative playlist, playback, and late-join synchronization. */
public final class PlaylistManager {

    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class.getName());
    private static final long DEFAULT_TRACK_DURATION_MS = 3L * 60L * 1000L;
    private static final long NEXT_TRACK_DELAY_MS = 2000L;
    private static final long LATE_JOIN_TIMEOUT_MS = 3000L;
    private static final long PLAYBACK_SYNC_START_LEAD_MS = 2500L;
    private static final int MAX_SEARCH_RESULTS = 10;

    private final MinecraftServer server;
    private final YouTubeService youTubeService;
    private final AudioDownloadService audioDownloadService;
    private final PlaylistState state;
    private final ScheduledExecutorService scheduler;
    private final ChartCache chartCache;
    private final RadioBrowserService radioBrowserService;
    private final RadioStreamService radioStreamService;
    private final Consumer<RadioStatePacket> radioStateBroadcastObserver;
    private final ConcurrentMap<UUID, Long> searchRequestGenerations = new ConcurrentHashMap<UUID, Long>();
    private final AtomicLong searchRequestGeneration = new AtomicLong();
    private final RadioPlaybackState radioState = new RadioPlaybackState();
    private final Map<String, List<EntityPlayerMP>> chartRefreshWaiters = new HashMap<String, List<EntityPlayerMP>>();
    private final ConcurrentMap<String, String> chartDurations = new ConcurrentHashMap<String, String>();
    private final ConcurrentMap<String, CompletableFuture<String>> chartDurationRequests = new ConcurrentHashMap<String, CompletableFuture<String>>();
    private final Set<String> preloadingAudio = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile boolean shuttingDown;
    private CompletableFuture<Path> activeAudioDownload;
    private String activeAudioVideoId;
    private final Set<String> chartRefreshInProgress = new HashSet<String>();
    private ScheduledFuture<?> advanceFuture;
    private ScheduledFuture<?> progressFuture;
    private ScheduledFuture<?> syncTimeoutFuture;
    private long radioSelectionRequest;
    private long radioLastSequence = -1L;
    private long musicGeneration;
    private long pendingChartDurationGeneration = -1L;
    private String pendingChartDurationVideoId;

    public PlaylistManager(MinecraftServer server, YouTubeService youTubeService,
        AudioDownloadService audioDownloadService) {
        this(server, youTubeService, audioDownloadService, null, new RadioBrowserService(), new RadioStreamService());
    }

    public PlaylistManager(MinecraftServer server, YouTubeService youTubeService,
        AudioDownloadService audioDownloadService, File configDirectory) {
        this(
            server,
            youTubeService,
            audioDownloadService,
            configDirectory,
            new RadioBrowserService(),
            new RadioStreamService());
    }

    public PlaylistManager(MinecraftServer server, YouTubeService youTubeService,
        AudioDownloadService audioDownloadService, File configDirectory, RadioBrowserService radioBrowserService,
        RadioStreamService radioStreamService) {
        this(
            server,
            youTubeService,
            audioDownloadService,
            configDirectory,
            radioBrowserService,
            radioStreamService,
            null);
    }

    private PlaylistManager(MinecraftServer server, YouTubeService youTubeService,
        AudioDownloadService audioDownloadService, File configDirectory, RadioBrowserService radioBrowserService,
        RadioStreamService radioStreamService, Consumer<RadioStatePacket> radioStateBroadcastObserver) {
        if ((server != null && (youTubeService == null || audioDownloadService == null)) || radioBrowserService == null
            || radioStreamService == null) {
            throw new IllegalArgumentException("server and services must not be null");
        }
        this.server = server;
        this.youTubeService = youTubeService;
        this.audioDownloadService = audioDownloadService;
        this.radioBrowserService = radioBrowserService;
        this.radioStreamService = radioStreamService;
        this.radioStateBroadcastObserver = radioStateBroadcastObserver;
        this.state = new PlaylistState(configuredMaxPlaylistSize());
        this.chartCache = new ChartCache(configDirectory);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "HorizonRadio-Scheduler");
                thread.setDaemon(true);
                return thread;
            }
        });

        progressFuture = scheduler.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        broadcastProgress();
                    }
                });
            }
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    public void handleSearch(final EntityPlayerMP player, String query) {
        if (player == null) {
            return;
        }
        LOGGER.info("Player " + player.getCommandSenderName() + " searching for: " + query);
        final long maxTrackDurationMs = configuredMaxTrackDurationMs();
        final UUID playerUuid = player.getUniqueID();
        final long requestGeneration = searchRequestGeneration.incrementAndGet();
        searchRequestGenerations.put(playerUuid, Long.valueOf(requestGeneration));
        CompletableFuture<List<SearchResult>> searchFuture = youTubeService.search(query, maxTrackDurationMs);
        searchFuture.thenAccept(new Consumer<List<SearchResult>>() {

            @Override
            public void accept(List<SearchResult> results) {
                if (!isLatestSearchRequest(searchRequestGenerations.get(playerUuid), requestGeneration)) {
                    return;
                }
                final List<SearchResultsPacket.Entry> entries = buildSearchEntries(results, maxTrackDurationMs);
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        if (!isLatestSearchRequest(searchRequestGenerations.get(playerUuid), requestGeneration)) {
                            return;
                        }
                        HorizonRadioNetwork.CHANNEL.sendTo(new SearchResultsPacket(entries), player);
                    }
                });
            }
        });
    }

    static List<SearchResultsPacket.Entry> buildSearchEntries(List<SearchResult> results, long maxTrackDurationMs) {
        List<SearchResultsPacket.Entry> entries = new ArrayList<SearchResultsPacket.Entry>();
        if (results == null) {
            return entries;
        }
        for (SearchResult result : results) {
            if (result == null || !MusicSearchFilter.isLikelyMusic(result)
                || !isSearchDurationAllowed(result.getDuration(), maxTrackDurationMs)) {
                continue;
            }
            entries.add(
                new SearchResultsPacket.Entry(
                    safe(result.getVideoId()),
                    safe(result.getTitle()),
                    safe(result.getChannel()),
                    safe(result.getDuration()),
                    safe(result.getThumbnail())));
            if (entries.size() >= MAX_SEARCH_RESULTS) {
                break;
            }
        }
        return entries;
    }

    PlaylistManager(RadioBrowserService radioBrowserService, RadioStreamService radioStreamService) {
        this(null, null, null, null, radioBrowserService, radioStreamService);
    }

    PlaylistManager(YouTubeService youTubeService, AudioDownloadService audioDownloadService,
        RadioBrowserService radioBrowserService, RadioStreamService radioStreamService) {
        this(null, youTubeService, audioDownloadService, null, radioBrowserService, radioStreamService);
    }

    PlaylistManager(YouTubeService youTubeService, AudioDownloadService audioDownloadService,
        RadioBrowserService radioBrowserService, RadioStreamService radioStreamService,
        Consumer<RadioStatePacket> radioStateBroadcastObserver) {
        this(
            null,
            youTubeService,
            audioDownloadService,
            null,
            radioBrowserService,
            radioStreamService,
            radioStateBroadcastObserver);
    }

    public void handleRadioSearch(final EntityPlayerMP player, String query) {
        if (player == null) {
            return;
        }
        radioBrowserService.search(query)
            .whenComplete(new BiConsumer<List<RadioStation>, Throwable>() {

                @Override
                public void accept(final List<RadioStation> stations, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            List<RadioSearchResultsPacket.Entry> entries = new ArrayList<RadioSearchResultsPacket.Entry>();
                            if (failure == null && stations != null) {
                                for (RadioStation station : stations) {
                                    RadioStation publicationStation = RadioBrowserService
                                        .sanitizeForPublication(station);
                                    if (publicationStation != null) {
                                        entries.add(
                                            new RadioSearchResultsPacket.Entry(
                                                publicationStation.getStationUuid(),
                                                publicationStation.getName()));
                                    }
                                }
                            }
                            HorizonRadioNetwork.CHANNEL.sendTo(new RadioSearchResultsPacket(entries), player);
                        }
                    });
                }
            });
    }

    public void handleSelectRadio(final EntityPlayerMP player, final String stationUuid) {
        if (player == null || isEmpty(stationUuid)) {
            return;
        }
        selectRadioStation(player, stationUuid);
    }

    void selectRadioStation(final EntityPlayerMP player, final String stationUuid) {
        final long request = ++radioSelectionRequest;
        long supersededCandidate = radioState.cancelCandidate();
        if (supersededCandidate != 0L) {
            radioStreamService.stopGeneration(supersededCandidate);
        }
        radioBrowserService.lookup(stationUuid)
            .whenComplete(new BiConsumer<RadioStation, Throwable>() {

                @Override
                public void accept(final RadioStation station, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            if (request != radioSelectionRequest) {
                                return;
                            }
                            RadioStation publicationStation = RadioBrowserService.sanitizeForPublication(station);
                            if (failure != null || publicationStation == null) {
                                if (player != null) {
                                    sendChat(player, EnumChatFormatting.RED, "Unable to start that radio station.");
                                }
                                return;
                            }
                            startRadioCandidate(publicationStation, request);
                        }
                    });
                }
            });
    }

    private void startRadioCandidate(final RadioStation station, final long request) {
        final long generation = radioState.beginCandidate();
        radioStreamService.startCandidate(station, generation, new RadioStreamService.RadioStreamListener() {

            @Override
            public void onReady(final long callbackGeneration, final RadioStation readyStation,
                final long firstSequence, final byte[] data) {
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        promoteReadyRadio(request, callbackGeneration, readyStation, firstSequence, data);
                    }
                });
            }

            @Override
            public void onChunk(final long callbackGeneration, final long sequence, final byte[] data) {
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        relayRadioChunk(callbackGeneration, sequence, data);
                    }
                });
            }

            @Override
            public void onFailure(final long callbackGeneration, final String message) {
                enqueueServerTask(new Runnable() {

                    @Override
                    public void run() {
                        handleRadioFailure(request, callbackGeneration, message);
                    }
                });
            }
        });
    }

    private void promoteReadyRadio(long request, long generation, RadioStation station, long firstSequence,
        byte[] data) {
        if (request != radioSelectionRequest || !radioState.isCandidateGeneration(generation)) {
            return;
        }
        RadioStation publicationStation = RadioBrowserService.sanitizeForPublication(station);
        RadioStatePacket statePacket;
        RadioAudioStartPacket startPacket;
        RadioAudioChunkPacket firstChunkPacket;
        try {
            if (publicationStation == null) {
                throw new IllegalArgumentException("radio station metadata cannot be published");
            }
            statePacket = new RadioStatePacket(
                true,
                generation,
                publicationStation.getStationUuid(),
                publicationStation.getName(),
                "Playing " + publicationStation.getName());
            startPacket = new RadioAudioStartPacket(generation, firstSequence, 44100, 2, 16, false);
            firstChunkPacket = new RadioAudioChunkPacket(generation, firstSequence, data);
        } catch (IllegalArgumentException exception) {
            radioState.failCandidate(generation, "Radio station metadata is unavailable");
            radioStreamService.stopGeneration(generation);
            LOGGER.log(Level.WARNING, "Rejected radio candidate before publication", exception);
            return;
        }

        radioBrowserService.countClick(publicationStation.getStationUuid());
        radioStreamService.promoteCandidate(generation);
        if (!radioState.promoteCandidate(generation, publicationStation)) {
            radioStreamService.stopGeneration(generation);
            return;
        }
        invalidateMusicWork();
        radioLastSequence = firstSequence;
        broadcastRadioState(statePacket);
        broadcastRadioStart(startPacket);
        broadcastRadioChunk(firstChunkPacket);
    }

    private void relayRadioChunk(long generation, long sequence, byte[] data) {
        if (!radioState.isRadioActive() || generation != radioState.getGeneration()
            || data == null
            || data.length == 0) {
            return;
        }
        radioLastSequence = sequence;
        broadcastRadioChunk(generation, sequence, data);
    }

    private void handleRadioFailure(long request, long generation, String message) {
        if (radioState.isCandidateGeneration(generation)) {
            if (request == radioSelectionRequest) {
                if (radioState.failCandidate(generation, message) && !radioState.isRadioActive()) {
                    broadcastRadioState("");
                }
            }
            return;
        }
        if (!radioState.isRadioActive() || generation != radioState.getGeneration()) {
            return;
        }
        long candidateGeneration = radioState.cancelCandidate();
        String inactiveStatus = PacketBufferUtil.truncateUtf8(safe(message), RadioStatePacket.MAX_STATUS_BYTES);
        radioState.stop(inactiveStatus);
        radioLastSequence = -1L;
        radioStreamService.stopGeneration(generation);
        if (candidateGeneration != 0L && candidateGeneration != generation) {
            radioStreamService.stopGeneration(candidateGeneration);
        }
        broadcastRadioState("");
    }

    public void handleStopRadio(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        stopRadio();
    }

    void stopRadio() {
        ++radioSelectionRequest;
        radioStreamService.stopAll();
        radioState.stop();
        radioLastSequence = -1L;
        broadcastRadioState("");
    }

    void stopRadioForMusic() {
        ++radioSelectionRequest;
        radioStreamService.stopAll();
        radioState.startMusic();
        radioLastSequence = -1L;
        broadcastRadioState("");
    }

    private void invalidateMusicWork() {
        ++musicGeneration;
        pendingChartDurationGeneration = -1L;
        pendingChartDurationVideoId = null;
        cancelFuture(advanceFuture);
        cancelFuture(syncTimeoutFuture);
        advanceFuture = null;
        syncTimeoutFuture = null;
        cancelActiveAudioDownload();
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            audioDownloadService.cancelDownload(videoId);
        }
        preloadingAudio.clear();
        state.stopPlayback();
    }

    public boolean isRadioActive() {
        return radioState.isRadioActive();
    }

    public void syncRadioToPlayer(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        HorizonRadioNetwork.CHANNEL.sendTo(radioStatePacket(""), player);
        if (radioState.isRadioActive()) {
            HorizonRadioNetwork.CHANNEL.sendTo(
                new RadioAudioStartPacket(radioState.getGeneration(), radioLastSequence + 1L, 44100, 2, 16, false),
                player);
        }
    }

    public void handleRequestCharts(final EntityPlayerMP player, boolean forceRefresh) {
        handleRequestCharts(player, ChartRegionCatalog.GLOBAL_CODE, forceRefresh);
    }

    public void handleRequestCharts(final EntityPlayerMP player, String regionCode, boolean forceRefresh) {
        if (player == null) {
            return;
        }

        final ChartRegion region = ChartRegionCatalog.byCode(regionCode);
        if (region == null) {
            sendChat(player, EnumChatFormatting.RED, "Unknown chart region: " + safe(regionCode) + ".");
            return;
        }
        final String canonicalRegionCode = region.getCode();
        final List<SearchResult> cached = chartCache.getResults(canonicalRegionCode);
        boolean operator = server.getConfigurationManager()
            .func_152596_g(player.getGameProfile());
        processChartRequest(
            region,
            forceRefresh,
            operator,
            !cached.isEmpty(),
            !cached.isEmpty() && chartCache.isFresh(),
            new ChartRequestActions() {

                @Override
                public void sendChartResults() {
                    PlaylistManager.this.sendChartResults(player, cached, region, false);
                }

                @Override
                public void sendChat(EnumChatFormatting color, String message) {
                    PlaylistManager.this.sendChat(player, color, message);
                }

                @Override
                public void registerWaiter() {
                    List<EntityPlayerMP> waiters = chartRefreshWaiters.get(canonicalRegionCode);
                    if (waiters == null) {
                        waiters = new ArrayList<EntityPlayerMP>();
                        chartRefreshWaiters.put(canonicalRegionCode, waiters);
                    }
                    if (!waiters.contains(player)) {
                        waiters.add(player);
                    }
                }

                @Override
                public void refresh() {
                    refreshChartsIfNeeded(region);
                }
            });
    }

    interface ChartRequestActions {

        void sendChartResults();

        void sendChat(EnumChatFormatting color, String message);

        void registerWaiter();

        void refresh();
    }

    static void processChartRequest(boolean forceRefresh, boolean operator, boolean hasCachedCharts, boolean cacheFresh,
        ChartRequestActions actions) {
        processChartRequest(ChartRegionCatalog.global(), forceRefresh, operator, hasCachedCharts, cacheFresh, actions);
    }

    static void processChartRequest(ChartRegion region, boolean forceRefresh, boolean operator, boolean hasCachedCharts,
        boolean cacheFresh, ChartRequestActions actions) {
        if (region == null) {
            throw new IllegalArgumentException("chart region must not be null");
        }
        if (!canRefreshCharts(forceRefresh, operator)) {
            actions.sendChartResults();
            actions.sendChat(EnumChatFormatting.RED, "Only server operators can refresh the charts.");
            return;
        }
        if (shouldServeCachedCharts(hasCachedCharts, forceRefresh, cacheFresh)) {
            actions.sendChartResults();
            return;
        }
        actions.sendChat(
            EnumChatFormatting.YELLOW,
            forceRefresh ? "Refreshing " + region.getDisplayName() + " YouTube Music Top 50..."
                : "Loading " + region.getDisplayName() + " YouTube Music Top 50...");
        actions.registerWaiter();
        actions.refresh();
    }

    static boolean canRefreshCharts(boolean forceRefresh, boolean operator) {
        return !forceRefresh || operator;
    }

    static boolean shouldServeCachedCharts(boolean hasCachedCharts, boolean forceRefresh, boolean cacheFresh) {
        return hasCachedCharts && !forceRefresh && cacheFresh;
    }

    private void refreshChartsIfNeeded(final ChartRegion region) {
        final String regionCode = region.getCode();
        if (!chartRefreshInProgress.add(regionCode)) {
            return;
        }
        CompletableFuture<List<SearchResult>> chartFuture;
        try {
            chartFuture = youTubeService.fetchTopCharts(region);
        } catch (RuntimeException exception) {
            finishChartRefreshAsync(region, new ArrayList<SearchResult>(), exception);
            return;
        }
        chartFuture.whenComplete(new BiConsumer<List<SearchResult>, Throwable>() {

            @Override
            public void accept(final List<SearchResult> charts, final Throwable failure) {
                if (failure != null || charts == null || charts.isEmpty()) {
                    finishChartRefreshAsync(region, new ArrayList<SearchResult>(), failure);
                    return;
                }
                finishChartRefreshAsync(region, charts, null);
            }
        });
    }

    private void finishChartRefreshAsync(final ChartRegion region, final List<SearchResult> refreshed,
        final Throwable failure) {
        enqueueServerTask(new Runnable() {

            @Override
            public void run() {
                finishChartRefresh(region, refreshed, failure);
            }
        });
    }

    private void finishChartRefresh(ChartRegion region, List<SearchResult> refreshed, Throwable failure) {
        String regionCode = region.getCode();
        if (refreshed != null && !refreshed.isEmpty()) {
            chartCache.store(regionCode, refreshed);
        }
        List<SearchResult> results = chartCache.getResults(regionCode);
        List<EntityPlayerMP> waiters = chartRefreshWaiters.remove(regionCode);
        chartRefreshInProgress.remove(regionCode);
        if (waiters == null) {
            waiters = new ArrayList<EntityPlayerMP>();
        }
        for (EntityPlayerMP player : waiters) {
            if (failure != null) {
                sendChartResults(player, results, region, false);
                sendChat(
                    player,
                    EnumChatFormatting.YELLOW,
                    "Could not refresh " + region.getDisplayName() + " charts; showing cached results.");
            } else {
                sendChartResults(player, results, region, true);
            }
        }
    }

    private void sendChartResults(EntityPlayerMP player, List<SearchResult> charts, ChartRegion region,
        boolean announce) {
        List<SearchResultsPacket.Entry> entries = buildChartEntries(charts, configuredMaxTrackDurationMs());
        HorizonRadioNetwork.CHANNEL.sendTo(new SearchResultsPacket(entries, true, region.getCode()), player);
        if (announce) {
            sendChat(
                player,
                entries.isEmpty() ? EnumChatFormatting.YELLOW : EnumChatFormatting.GREEN,
                "Loaded " + entries.size() + " " + region.getDisplayName() + " chart songs.");
        }
    }

    private CompletableFuture<Map<String, String>> resolveChartDurations(List<String> videoIds) {
        final Map<String, CompletableFuture<String>> durationFutures = new HashMap<String, CompletableFuture<String>>();
        List<String> lookupIds = new ArrayList<String>();
        Set<String> seenVideoIds = new HashSet<String>();
        if (videoIds != null) {
            for (String videoId : videoIds) {
                if (isEmpty(videoId) || !seenVideoIds.add(videoId)) {
                    continue;
                }
                String cachedDuration = chartDurations.get(videoId);
                if (isKnownDuration(cachedDuration)) {
                    durationFutures.put(videoId, CompletableFuture.completedFuture(cachedDuration));
                    continue;
                }
                CompletableFuture<String> pending = new CompletableFuture<String>();
                CompletableFuture<String> existing = chartDurationRequests.putIfAbsent(videoId, pending);
                if (existing == null) {
                    durationFutures.put(videoId, pending);
                    lookupIds.add(videoId);
                } else {
                    durationFutures.put(videoId, existing);
                }
            }
        }
        if (!lookupIds.isEmpty()) {
            startChartDurationLookup(lookupIds);
        }

        final CompletableFuture<Map<String, String>> resolved = new CompletableFuture<Map<String, String>>();
        if (durationFutures.isEmpty()) {
            resolved.complete(new HashMap<String, String>());
            return resolved;
        }
        CompletableFuture<?>[] pendingFutures = durationFutures.values()
            .toArray(new CompletableFuture<?>[durationFutures.size()]);
        CompletableFuture.allOf(pendingFutures)
            .whenComplete(new BiConsumer<Void, Throwable>() {

                @Override
                public void accept(Void ignored, Throwable failure) {
                    if (failure != null) {
                        resolved.completeExceptionally(failure);
                        return;
                    }
                    Map<String, String> durations = new HashMap<String, String>();
                    for (Map.Entry<String, CompletableFuture<String>> entry : durationFutures.entrySet()) {
                        String duration = entry.getValue()
                            .getNow("");
                        if (!isEmpty(duration)) {
                            durations.put(entry.getKey(), duration);
                        }
                    }
                    resolved.complete(durations);
                }
            });
        return resolved;
    }

    private void resolveChartDurationForCurrentTrack(final long generation, final PlaylistEntry entry,
        final int index) {
        pendingChartDurationGeneration = generation;
        pendingChartDurationVideoId = entry.getVideoId();
        resolveChartDurations(Collections.singletonList(entry.getVideoId()))
            .whenComplete(new BiConsumer<Map<String, String>, Throwable>() {

                @Override
                public void accept(final Map<String, String> durations, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            if (!isCurrentMusicEntry(generation, entry, index)) {
                                return;
                            }
                            String duration = failure == null && durations != null ? durations.get(entry.getVideoId())
                                : "";
                            long durationMs = isKnownDuration(duration) ? parseDuration(duration)
                                : DEFAULT_TRACK_DURATION_MS;
                            pendingChartDurationGeneration = -1L;
                            pendingChartDurationVideoId = null;
                            state.updateCurrentTrackDuration(durationMs);
                            if (!isEmpty(state.getCurrentVideoId()) && !state.isPaused() && !state.isSyncing()) {
                                scheduleNextTrack(
                                    currentPositionMs(System.currentTimeMillis(), durationMs),
                                    durationMs);
                            }
                        }
                    });
                }
            });
    }

    private void startChartDurationLookup(final List<String> videoIds) {
        CompletableFuture<String> lookup;
        try {
            lookup = audioDownloadService.extractVideoDurationOutput(videoIds);
        } catch (RuntimeException exception) {
            completeChartDurationLookup(videoIds, null);
            return;
        }
        if (lookup == null) {
            completeChartDurationLookup(videoIds, null);
            return;
        }
        lookup.whenComplete(new BiConsumer<String, Throwable>() {

            @Override
            public void accept(String durationOutput, Throwable failure) {
                completeChartDurationLookup(videoIds, failure == null ? durationOutput : null);
            }
        });
    }

    private void completeChartDurationLookup(List<String> videoIds, String durationOutput) {
        Map<String, String> durations = PlaylistImportService.parseDurationOutput(durationOutput);
        for (String videoId : videoIds) {
            String duration = durations.get(videoId);
            if (isKnownDuration(duration)) {
                chartDurations.put(videoId, duration);
            }
            CompletableFuture<String> pending = chartDurationRequests.remove(videoId);
            if (pending != null) {
                pending.complete(safe(duration));
            }
        }
    }

    static List<SearchResultsPacket.Entry> buildChartEntries(List<SearchResult> charts, long maxDurationMs) {
        List<SearchResultsPacket.Entry> entries = new ArrayList<SearchResultsPacket.Entry>();
        if (charts == null) {
            return entries;
        }
        for (SearchResult chartEntry : charts) {
            if (chartEntry == null) {
                continue;
            }
            String duration = safe(chartEntry.getDuration());
            entries.add(
                new SearchResultsPacket.Entry(
                    safe(chartEntry.getVideoId()),
                    safe(chartEntry.getTitle()),
                    safe(chartEntry.getChannel()),
                    duration,
                    safe(chartEntry.getThumbnail())));
        }
        return entries;
    }

    public void handleImportPlaylist(final EntityPlayerMP player, String playlistUrl) {
        if (!acceptsPlayer(player) || !PlaylistImportService.isPlaylistUrl(playlistUrl)) {
            if (player != null) {
                clearSearchResults(player);
                sendChat(player, EnumChatFormatting.RED, "Invalid YouTube playlist URL.");
            }
            return;
        }
        sendChat(player, EnumChatFormatting.YELLOW, "Importing YouTube playlist...");
        audioDownloadService.extractPlaylistJson(playlistUrl)
            .whenComplete(new BiConsumer<String, Throwable>() {

                @Override
                public void accept(final String json, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            finishPlaylistImport(player, json);
                        }
                    });
                }
            });
    }

    public void handleImportVideo(final EntityPlayerMP player, String videoUrl) {
        if (!acceptsPlayer(player) || !PlaylistImportService.isVideoUrl(videoUrl)) {
            if (player != null) {
                clearSearchResults(player);
                sendChat(player, EnumChatFormatting.RED, "Invalid YouTube video URL.");
            }
            return;
        }
        sendChat(player, EnumChatFormatting.YELLOW, "Reading YouTube video metadata...");
        audioDownloadService.extractVideoJson(videoUrl)
            .whenComplete(new BiConsumer<String, Throwable>() {

                @Override
                public void accept(final String json, Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            finishVideoImport(player, json);
                        }
                    });
                }
            });
    }

    private void finishVideoImport(EntityPlayerMP player, String json) {
        SearchResult result = PlaylistImportService.parseVideo(json);
        if (result == null) {
            clearSearchResults(player);
            sendChat(player, EnumChatFormatting.RED, "Could not read that YouTube video.");
            return;
        }
        if (!isSearchDurationAllowed(result.getDuration(), configuredMaxTrackDurationMs())) {
            clearSearchResults(player);
            sendChat(player, EnumChatFormatting.YELLOW, "This video is too long for the server search limit.");
            return;
        }
        for (PlaylistEntry entry : state.snapshot()) {
            if (result.getVideoId()
                .equals(entry.getVideoId())) {
                clearSearchResults(player);
                sendChat(player, EnumChatFormatting.YELLOW, "This video is already in the queue.");
                return;
            }
        }

        state.add(new PlaylistEntry(result.getVideoId(), result.getTitle(), result.getDuration(), playerName(player)));
        if (state.isShuffling()) {
            state.shuffleQueued();
        }
        syncToAll();
        if (shouldStartPlaylistPlayback(isRadioActive(), state.isPlaying())) {
            playNext();
        }
        clearSearchResults(player);
        sendChat(player, EnumChatFormatting.GREEN, "Added video to the playlist.");
    }

    private void finishPlaylistImport(EntityPlayerMP player, String json) {
        if (json == null) {
            clearSearchResults(player);
            sendChat(player, EnumChatFormatting.RED, "Could not read the YouTube playlist.");
            return;
        }

        List<SearchResult> imported = PlaylistImportService.parse(json);
        Set<String> knownVideoIds = new HashSet<String>();
        for (PlaylistEntry entry : state.snapshot()) {
            knownVideoIds.add(entry.getVideoId());
        }

        int added = 0;
        for (SearchResult result : imported) {
            if (!isSearchDurationAllowed(result.getDuration(), configuredMaxTrackDurationMs())
                || !knownVideoIds.add(result.getVideoId())) {
                continue;
            }
            state.add(
                new PlaylistEntry(result.getVideoId(), result.getTitle(), result.getDuration(), playerName(player)));
            added++;
        }
        if (state.isShuffling()) {
            state.shuffleQueued();
        }
        if (added > 0) {
            syncToAll();
            if (shouldStartPlaylistPlayback(isRadioActive(), state.isPlaying())) {
                playNext();
            }
        }
        clearSearchResults(player);
        sendChat(
            player,
            added > 0 ? EnumChatFormatting.GREEN : EnumChatFormatting.YELLOW,
            "Imported " + added + " playlist entr" + (added == 1 ? "y" : "ies") + ".");
    }

    private void clearSearchResults(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        HorizonRadioNetwork.CHANNEL.sendTo(new SearchResultsPacket(new ArrayList<SearchResultsPacket.Entry>()), player);
    }

    public void handleAddToPlaylist(EntityPlayerMP player, String videoId, String title, String duration) {
        if (!acceptsPlayer(player)) {
            return;
        }
        if (isEmpty(videoId) || isEmpty(title)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            return;
        }
        if (!isSearchDurationAllowed(duration, configuredMaxTrackDurationMs())) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "This song is too long for the server search limit and cannot be added.");
            return;
        }
        if (!state.add(new PlaylistEntry(videoId, title, safe(duration), playerName(player)))) {
            return;
        }
        if (state.isShuffling()) {
            state.shuffleQueued();
        }

        if (!audioDownloadService.isDependenciesAvailable()) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.");
        }
        LOGGER.info(playerName(player) + " added " + title + " to the playlist");
        syncToAll();
        if (shouldStartPlaylistPlayback(isRadioActive(), state.isPlaying())) {
            playNext();
        }
    }

    public void handlePlayNow(EntityPlayerMP player, String videoId, String title, String duration) {
        if (!acceptsPlayer(player)) {
            return;
        }
        if (isEmpty(videoId) || isEmpty(title)) {
            sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            return;
        }
        if (isEmpty(duration)) {
            resolveDurationBeforePlayNow(player, videoId, title);
            return;
        }
        if (!isSearchDurationAllowed(duration, configuredMaxTrackDurationMs())) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "This song is too long for the server search limit and cannot be played now.");
            return;
        }

        playNowWithValidatedDuration(player, videoId, title, duration);
    }

    private void resolveDurationBeforePlayNow(final EntityPlayerMP player, final String videoId, final String title) {
        audioDownloadService.extractVideoDurationOutput(Collections.singletonList(videoId))
            .whenComplete(new BiConsumer<String, Throwable>() {

                @Override
                public void accept(final String durationOutput, final Throwable failure) {
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            String duration = PlaylistImportService.parseDurationOutput(durationOutput)
                                .get(videoId);
                            if (failure != null || isEmpty(duration)) {
                                sendChat(
                                    player,
                                    EnumChatFormatting.YELLOW,
                                    "Could not determine the song duration; it cannot be played now.");
                                return;
                            }
                            handlePlayNow(player, videoId, title, duration);
                        }
                    });
                }
            });
    }

    private void playNowWithValidatedDuration(EntityPlayerMP player, String videoId, String title, String duration) {
        stopRadioForMusic();
        invalidateMusicWork();

        int existingIndex = state.findIndex(videoId);
        PlaylistEntry requested = existingIndex >= 0 ? state.get(existingIndex)
            : new PlaylistEntry(videoId, title, safe(duration), playerName(player));
        if (state.isSyncing()) {
            resumePausedClientsBeforeCurrentRemoval();
        }
        cancelFuture(advanceFuture);
        advanceFuture = null;
        state.prepareImmediatePlayback(requested);
        broadcastNowPlaying("", 0.0f);
        syncToAll();
        if (!audioDownloadService.isDependenciesAvailable()) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.");
        }
        LOGGER.info(playerName(player) + " selected " + requested.getTitle() + " for immediate playback");
        playNext(false, false);
    }

    public void handleAddChartsToPlaylist(final EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
        boolean remove) {
        if (player == null) {
            return;
        }
        if (entries == null || entries.isEmpty()) {
            sendChat(player, EnumChatFormatting.YELLOW, "No chart songs are available.");
            return;
        }
        if (remove) {
            removeChartEntries(player, entries);
            return;
        }
        final List<AddChartsToPlaylistPacket.Entry> requested = new ArrayList<AddChartsToPlaylistPacket.Entry>(entries);
        if (!audioDownloadService.isDependenciesAvailable()) {
            sendChat(
                player,
                EnumChatFormatting.YELLOW,
                "Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.");
        }
        enqueueServerTask(new Runnable() {

            @Override
            public void run() {
                int added = finishAddChartsToPlaylist(player, requested);
                publishChartAddRequest(player, requested, added);
            }
        });
    }

    private int finishAddChartsToPlaylist(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries) {
        Set<String> knownVideoIds = new HashSet<String>();
        for (PlaylistEntry entry : state.snapshot()) {
            knownVideoIds.add(entry.getVideoId());
        }

        int added = 0;
        for (AddChartsToPlaylistPacket.Entry chart : entries) {
            if (chart == null) {
                continue;
            }
            if (isEmpty(chart.getVideoId()) || isEmpty(chart.getTitle()) || !knownVideoIds.add(chart.getVideoId())) {
                continue;
            }
            String duration = safe(chart.getDuration());
            if (isKnownDuration(duration)) {
                chartDurations.put(chart.getVideoId(), duration);
            }
            if (state.add(
                new PlaylistEntry(chart.getVideoId(), chart.getTitle(), duration, player.getCommandSenderName()))) {
                added++;
            }
        }
        return added;
    }

    private void publishChartAddRequest(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries,
        int added) {
        if (added > 0) {
            if (state.isShuffling()) {
                state.shuffleQueued();
            }
            syncToAll();
            if (shouldStartPlaylistPlayback(isRadioActive(), state.isPlaying())) {
                playNext();
            }
        }
        sendChartAddCompletion(player, entries);
    }

    private void sendChartAddCompletion(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries) {
        List<String> completedVideoIds = new ArrayList<String>();
        if (entries != null) {
            for (AddChartsToPlaylistPacket.Entry entry : entries) {
                if (entry != null && !isEmpty(entry.getVideoId())) {
                    completedVideoIds.add(entry.getVideoId());
                }
            }
        }
        if (server == null) {
            return;
        }
        HorizonRadioNetwork.CHANNEL.sendTo(new ChartAddCompletionPacket(completedVideoIds), player);
    }

    public void handleRemoveFromPlaylist(EntityPlayerMP player, String videoId) {
        if (!acceptsPlayer(player) || isEmpty(videoId)) {
            if (player != null) {
                sendChat(player, EnumChatFormatting.RED, "Invalid playlist entry.");
            }
            return;
        }

        String playerName = playerName(player);
        int oldCurrentIndex = state.getCurrentIndex();
        int entryIndex = state.findIndex(videoId);
        if (entryIndex < 0) {
            LOGGER.warning(playerName + " tried to remove absent " + videoId);
            sendChat(player, EnumChatFormatting.YELLOW, "That song is no longer in the queue.");
            return;
        }
        if (!isRadioActive() && entryIndex == oldCurrentIndex && state.isSyncing()) {
            resumePausedClientsBeforeCurrentRemoval();
        }
        int removeIndex = state.remove(videoId);
        if (removeIndex < 0) {
            LOGGER.warning(playerName + " tried to remove absent " + videoId);
            sendChat(player, EnumChatFormatting.YELLOW, "That song is no longer in the queue.");
            return;
        }

        audioDownloadService.delete(videoId);
        LOGGER.info(playerName + " removed " + videoId + " from the playlist");
        if (!isRadioActive() && removeIndex == oldCurrentIndex) {
            cancelFuture(advanceFuture);
            broadcastNowPlaying("", 0.0f);
            playNext(false);
        }
        syncToAll();
    }

    public void handleClearPlaylist(EntityPlayerMP player) {
        if (!acceptsPlayer(player)) {
            return;
        }
        List<PlaylistEntry> entries = state.snapshot();
        if (entries.isEmpty()) {
            sendChat(player, EnumChatFormatting.YELLOW, "The queue is already empty.");
            return;
        }
        if (!isRadioActive() && state.isSyncing()) {
            resumePausedClientsBeforeCurrentRemoval();
        }
        cancelFuture(advanceFuture);
        cancelFuture(syncTimeoutFuture);
        advanceFuture = null;
        syncTimeoutFuture = null;
        cancelActiveAudioDownload();
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            audioDownloadService.cancelDownload(videoId);
        }
        preloadingAudio.clear();
        state.clear();
        for (PlaylistEntry entry : entries) {
            audioDownloadService.delete(entry.getVideoId());
        }
        if (!isRadioActive()) {
            broadcastNowPlaying("", 0.0f);
        }
        broadcastLoopState(false);
        broadcastShuffleState(false);
        syncToAll();
        sendChat(player, EnumChatFormatting.GREEN, "Cleared " + entries.size() + " songs from the queue.");
    }

    public void handleReorder(EntityPlayerMP player, int fromIndex, int targetIndex) {
        if (!acceptsPlayer(player) || !state.moveQueued(fromIndex, targetIndex)) {
            return;
        }
        LOGGER.info(playerName(player) + " moved playlist entry from " + fromIndex + " to " + targetIndex);
        syncToAll();
    }

    public void handleSeek(EntityPlayerMP player, float progress) {
        if (player == null || Float.isNaN(progress)
            || Float.isInfinite(progress)
            || state.isSyncing()
            || isRadioActive()
            || !state.isPlaying()) {
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= state.size()) {
            return;
        }

        float safeProgress = Math.max(0.0f, Math.min(1.0f, progress));
        long durationMs = state.getCurrentTrackDurationMs();
        long requestedPositionMs = (long) (durationMs * safeProgress);
        long now = System.currentTimeMillis();
        boolean wasPaused = state.isPaused();
        long resumeAtMs = wasPaused ? 0L : now + PLAYBACK_SYNC_START_LEAD_MS;
        long positionMs = state.seek(requestedPositionMs, wasPaused ? now : resumeAtMs);
        if (positionMs < 0L) {
            return;
        }

        cancelFuture(advanceFuture);
        PausePacket pausePacket = new PausePacket(positionMs);
        for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(pausePacket, onlinePlayer);
        }
        if (!wasPaused) {
            ResumePacket resumePacket = new ResumePacket(positionMs, resumeAtMs);
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, onlinePlayer);
            }
        }

        PlaylistEntry entry = state.get(currentIndex);
        broadcastNowPlaying(entry.getTitle(), progressFor(positionMs, durationMs));
        if (!wasPaused) {
            scheduleNextTrack(positionMs, durationMs, resumeAtMs);
        }
    }

    public void handleTogglePlayback(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || isRadioActive() || !state.isPlaying()) {
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= state.size()) {
            return;
        }

        long now = System.currentTimeMillis();
        long durationMs = state.getCurrentTrackDurationMs();
        long positionMs;
        if (state.isPaused()) {
            long resumeAtMs = now + PLAYBACK_SYNC_START_LEAD_MS;
            positionMs = state.resumePlayback(resumeAtMs);
            if (positionMs < 0L) {
                return;
            }
            ResumePacket resumePacket = new ResumePacket(positionMs, resumeAtMs);
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, onlinePlayer);
            }
            scheduleNextTrack(positionMs, durationMs, resumeAtMs);
        } else {
            positionMs = currentPositionMs(now, durationMs);
            positionMs = state.pausePlayback(positionMs, now);
            if (positionMs < 0L) {
                return;
            }
            cancelFuture(advanceFuture);
            PausePacket pausePacket = new PausePacket(positionMs);
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(pausePacket, onlinePlayer);
            }
        }

        PlaylistEntry entry = state.get(currentIndex);
        broadcastNowPlaying(entry.getTitle(), progressFor(positionMs, durationMs));
    }

    public void handleSkipTrack(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || isRadioActive() || !state.isPlaying()) {
            return;
        }
        cancelFuture(advanceFuture);
        advanceFuture = null;
        LOGGER.info(player.getCommandSenderName() + " skipped the current track");
        playNext(true);
    }

    public void handleToggleLoop(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || isRadioActive()) {
            return;
        }
        boolean looping = state.toggleLooping();
        broadcastLoopState(looping);
        LOGGER.info(player.getCommandSenderName() + " " + (looping ? "enabled" : "disabled") + " repeat-one mode");
    }

    public void handleToggleShuffle(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || isRadioActive()) {
            return;
        }
        boolean shuffling = state.toggleShuffling();
        if (shuffling) {
            state.shuffleQueued();
            preloadAdjacentTracks();
        }
        broadcastShuffleState(shuffling);
        syncToAll();
        LOGGER.info(player.getCommandSenderName() + " " + (shuffling ? "enabled" : "disabled") + " shuffle mode");
    }

    public void handlePreviousTrack(EntityPlayerMP player) {
        if (player == null || state.isSyncing() || isRadioActive() || !state.isPlaying()) {
            return;
        }

        long now = System.currentTimeMillis();
        long durationMs = state.getCurrentTrackDurationMs();
        long positionMs = state.isPaused() ? state.getPausedPositionMs() : currentPositionMs(now, durationMs);
        if (!state.wasPreviousRestarted() || positionMs > 10000L) {
            state.markPreviousRestarted();
            handleSeek(player, 0.0f);
            return;
        }

        PlaylistEntry previous = state.takeLastTrack();
        if (previous == null) {
            handleSeek(player, 0.0f);
            return;
        }

        cancelFuture(advanceFuture);
        advanceFuture = null;
        broadcastNowPlaying("", 0.0f);
        state.addAtFront(previous);
        state.resetPlayback();
        syncToAll();
        LOGGER.info(player.getCommandSenderName() + " returned to the previous track");
        playNext(false, false);
    }

    public void syncToPlayer(final EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        HorizonRadioNetwork.CHANNEL.sendTo(new ShuffleStatePacket(state.isShuffling()), player);
        HorizonRadioNetwork.CHANNEL.sendTo(new LoopStatePacket(state.isLooping()), player);
        HorizonRadioNetwork.CHANNEL.sendTo(new PlaylistSyncPacket(toPacketEntries(state.snapshot())), player);
        syncRadioToPlayer(player);
        if (isRadioActive()) {
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (!state.isPlaying() || currentIndex < 0 || currentIndex >= state.size()) {
            return;
        }

        final PlaylistEntry entry = state.get(currentIndex);
        long now = System.currentTimeMillis();
        long elapsed = state.isSyncing() || state.isPaused() ? state.getPausedPositionMs()
            : Math.max(0L, now - state.getPlaybackStartTime());
        float progress = progressFor(elapsed, state.getCurrentTrackDurationMs());
        HorizonRadioNetwork.CHANNEL.sendTo(new NowPlayingPacket(entry.getTitle(), progress), player);

        if (state.isPaused()) {
            HorizonRadioNetwork.CHANNEL.sendTo(new PausePacket(state.getPausedPositionMs()), player);
            return;
        }

        final String videoId = state.getCurrentVideoId();
        if (isEmpty(videoId)) {
            return;
        }

        final UUID playerUuid = player.getUniqueID();
        boolean firstJoiner = state.beginLateJoin(playerUuid, elapsed, now);
        if (firstJoiner) {
            cancelFuture(advanceFuture);
            PausePacket pausePacket = new PausePacket(state.getPausedPositionMs());
            for (EntityPlayerMP onlinePlayer : onlinePlayersSnapshot()) {
                HorizonRadioNetwork.CHANNEL.sendTo(pausePacket, onlinePlayer);
            }
            LOGGER.info("Paused all clients at " + state.getPausedPositionMs() + "ms for late-join synchronization");
        } else if (state.containsPending(playerUuid)) {
            HorizonRadioNetwork.CHANNEL.sendTo(new PausePacket(state.getPausedPositionMs()), player);
        }

        scheduleSyncTimeout();
        requestLateJoinAudio(player, playerUuid, entry, videoId);
    }

    public void onPlayerReady(EntityPlayerMP player, String videoId) {
        if (player == null || isEmpty(videoId)) {
            return;
        }
        if (state.ready(player.getUniqueID(), videoId)) {
            LOGGER.info("Player " + player.getCommandSenderName() + " is ready for " + videoId);
            doResume();
        }
    }

    public void handleDisconnect(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        searchRequestGenerations.remove(player.getUniqueID());
        if (!state.isSyncing()) {
            return;
        }
        if (state.disconnect(player.getUniqueID())) {
            LOGGER.info(
                "Syncing player " + player.getCommandSenderName()
                    + " disconnected; "
                    + state.getPendingPlayerCount()
                    + " pending");
            if (!state.hasPendingPlayers()) {
                doResume();
            }
        }
    }

    private void resumePausedClientsBeforeCurrentRemoval() {
        if (!state.isSyncing()) {
            return;
        }
        long pausedPositionMs = state.getPausedPositionMs();
        state.forceResume();
        cancelFuture(syncTimeoutFuture);
        syncTimeoutFuture = null;

        ResumePacket resumePacket = new ResumePacket(pausedPositionMs);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, player);
        }
        LOGGER.info("Resumed all clients at " + pausedPositionMs + "ms before removing the current track");
    }

    private void playNext() {
        if (isRadioActive()) {
            return;
        }
        if (state.isLooping()) {
            replayCurrent();
            return;
        }
        playNext(true);
    }

    private void replayCurrent() {
        if (isRadioActive()) {
            return;
        }
        int currentIndex = state.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= state.size()) {
            playNext(true);
            return;
        }
        cancelFuture(advanceFuture);
        advanceFuture = null;
        broadcastNowPlaying("", 0.0f);
        state.resetPlayback();
        syncToAll();
        playNext(false, false);
    }

    private void playNext(boolean removeCurrentFromQueue) {
        playNext(removeCurrentFromQueue, true);
    }

    private void playNext(boolean removeCurrentFromQueue, boolean shuffleQueue) {
        if (isRadioActive()) {
            return;
        }
        if (radioState.getMode() != RadioPlaybackState.Mode.MUSIC) {
            radioState.startMusic();
            broadcastRadioState("");
        }
        final long generation = ++musicGeneration;
        pendingChartDurationGeneration = -1L;
        pendingChartDurationVideoId = null;
        cancelActiveAudioDownload();
        PlaylistEntry previousLastTrack = state.peekLastTrack();
        boolean queueChanged = false;
        if (removeCurrentFromQueue) {
            PlaylistEntry removed = state.removeCurrent();
            if (removed != null) {
                // Keep the just-finished file available for the Previous button.
                // The older last-track file is cleaned up below on the next transition.
                if (previousLastTrack != null && !previousLastTrack.getVideoId()
                    .equals(removed.getVideoId())) {
                    audioDownloadService.delete(previousLastTrack.getVideoId());
                }
                broadcastNowPlaying("", 0.0f);
                queueChanged = true;
            }
        }

        if (shuffleQueue && state.isShuffling()) {
            state.shuffleQueued();
            queueChanged = true;
        }
        if (queueChanged) {
            syncToAll();
        }

        int nextIndex = state.getCurrentIndex() + 1;
        PlaylistEntry entry = null;
        if (nextIndex >= 0 && nextIndex < state.size()) {
            PlaylistEntry nextCandidate = state.get(nextIndex);
            cancelPreloadsExcept(
                nextCandidate.getVideoId(),
                state.peekLastTrack() == null ? null
                    : state.peekLastTrack()
                        .getVideoId());
            entry = state.advanceToNext(
                parseDuration(
                    state.get(nextIndex)
                        .getDuration()));
        } else {
            state.advanceToNext(DEFAULT_TRACK_DURATION_MS);
        }

        if (entry == null) {
            state.resetPlayback();
            broadcastNowPlaying("", 0.0f);
            return;
        }

        final PlaylistEntry selectedEntry = entry;
        final int selectedIndex = state.getCurrentIndex();
        if (!isKnownDuration(selectedEntry.getDuration())) {
            resolveChartDurationForCurrentTrack(generation, selectedEntry, selectedIndex);
        }
        preloadAdjacentTracks();
        LOGGER.info("Requesting download for: " + selectedEntry.getTitle() + " (" + selectedEntry.getVideoId() + ")");
        final CompletableFuture<Path> downloadFuture = audioDownloadService.download(selectedEntry.getVideoId());
        activeAudioDownload = downloadFuture;
        activeAudioVideoId = selectedEntry.getVideoId();
        downloadFuture.whenComplete(new BiConsumer<Path, Throwable>() {

            @Override
            public void accept(final Path filePath, Throwable downloadFailure) {
                if (activeAudioDownload == downloadFuture) {
                    activeAudioDownload = null;
                    activeAudioVideoId = null;
                }
                if (downloadFailure != null) {
                    LOGGER
                        .log(Level.WARNING, "Audio download failed for " + selectedEntry.getVideoId(), downloadFailure);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadFailed(generation, selectedEntry, selectedIndex);
                        }
                    });
                    return;
                }

                try {
                    if (filePath == null || !Files.isRegularFile(filePath)) {
                        enqueueServerTask(new Runnable() {

                            @Override
                            public void run() {
                                downloadFailed(generation, selectedEntry, selectedIndex);
                            }
                        });
                        return;
                    }

                    final long audioFileSize = Files.size(filePath);
                    if (!supportsAudioLength(audioFileSize)) {
                        enqueueServerTask(new Runnable() {

                            @Override
                            public void run() {
                                audioTooLarge(generation, selectedEntry, selectedIndex, audioFileSize);
                            }
                        });
                        return;
                    }
                    final byte[] audioBytes = readAudioBytes(filePath);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadedAudioReady(generation, selectedEntry, selectedIndex, audioBytes);
                        }
                    });
                } catch (IOException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to read downloaded audio for " + selectedEntry.getVideoId(),
                        exception);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadFailed(generation, selectedEntry, selectedIndex);
                        }
                    });
                    return;
                } catch (RuntimeException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to validate or read downloaded audio for " + selectedEntry.getVideoId(),
                        exception);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            downloadFailed(generation, selectedEntry, selectedIndex);
                        }
                    });
                    return;
                }
            }
        });
    }

    private void preloadAdjacentTracks() {
        int nextIndex = state.getCurrentIndex() + 1;
        if (nextIndex >= 0 && nextIndex < state.size()) {
            preloadAudio(state.get(nextIndex));
        }
        preloadAudio(state.peekLastTrack());
    }

    private void preloadAudio(PlaylistEntry entry) {
        if (entry == null || isEmpty(entry.getVideoId()) || !preloadingAudio.add(entry.getVideoId())) {
            return;
        }
        audioDownloadService.download(entry.getVideoId())
            .whenComplete(new BiConsumer<Path, Throwable>() {

                @Override
                public void accept(Path path, Throwable failure) {
                    preloadingAudio.remove(entry.getVideoId());
                    if (failure != null) {
                        LOGGER.log(Level.FINE, "Preloading failed for " + entry.getVideoId(), failure);
                    }
                }
            });
    }

    private void cancelActiveAudioDownload() {
        if (activeAudioVideoId != null) {
            audioDownloadService.cancelDownload(activeAudioVideoId);
        }
        activeAudioDownload = null;
        activeAudioVideoId = null;
    }

    private void cancelPreloadsExcept(String keepFirst, String keepSecond) {
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            if (!videoId.equals(keepFirst) && !videoId.equals(keepSecond)) {
                audioDownloadService.cancelDownload(videoId);
                preloadingAudio.remove(videoId);
            }
        }
    }

    private void removeChartEntries(EntityPlayerMP player, List<AddChartsToPlaylistPacket.Entry> entries) {
        String currentVideoId = state.getCurrentVideoId();
        boolean removingCurrent = false;
        List<String> removedIds = new ArrayList<String>();
        Set<String> requestedIds = new HashSet<String>();
        for (AddChartsToPlaylistPacket.Entry chart : entries) {
            if (isEmpty(chart.getVideoId()) || !requestedIds.add(chart.getVideoId())) {
                continue;
            }
            if (state.findIndex(chart.getVideoId()) < 0) {
                continue;
            }
            if (chart.getVideoId()
                .equals(currentVideoId)) {
                removingCurrent = true;
                if (!isRadioActive() && state.isSyncing()) {
                    resumePausedClientsBeforeCurrentRemoval();
                }
            }
            if (state.remove(chart.getVideoId()) >= 0) {
                removedIds.add(chart.getVideoId());
            }
        }
        for (String videoId : removedIds) {
            audioDownloadService.delete(videoId);
        }
        if (!isRadioActive() && removingCurrent) {
            cancelFuture(advanceFuture);
            broadcastNowPlaying("", 0.0f);
            playNext(false);
        }
        if (!removedIds.isEmpty()) {
            syncToAll();
        }
        sendChat(
            player,
            removedIds.isEmpty() ? EnumChatFormatting.YELLOW : EnumChatFormatting.GREEN,
            "Removed " + removedIds.size() + " chart songs from the playlist.");
    }

    private void downloadFailed(long generation, PlaylistEntry entry, int index) {
        if (!isCurrentMusicEntry(generation, entry, index)) {
            return;
        }
        LOGGER.warning("Download failed for: " + entry.getTitle());
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            sendChat(
                player,
                EnumChatFormatting.RED,
                "Failed to download: " + entry.getTitle() + " - Check server logs for details");
        }
        playNext(true);
    }

    private void audioTooLarge(long generation, PlaylistEntry entry, int index, long audioLength) {
        if (!isCurrentMusicEntry(generation, entry, index)) {
            return;
        }
        LOGGER.warning(
            "Audio file is too large for packet transfer for " + entry
                .getVideoId() + " (" + audioLength + " bytes; maximum " + maxAudioBytes() + ")");
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            sendChat(player, EnumChatFormatting.RED, "Audio is too large to play: " + entry.getTitle() + " - skipped.");
        }
        playNext(true);
    }

    private void downloadedAudioReady(final long generation, PlaylistEntry entry, int index, byte[] audioBytes) {
        if (!isCurrentMusicEntry(generation, entry, index)) {
            return;
        }
        if (audioBytes == null || audioBytes.length == 0) {
            LOGGER.warning("Downloaded audio was empty for: " + entry.getVideoId());
            playNext(true);
            return;
        }

        state.markLoaded(entry.getVideoId(), System.currentTimeMillis());
        List<EntityPlayerMP> players = onlinePlayersSnapshot();
        beginInitialPlaybackSync(players);
        for (EntityPlayerMP player : players) {
            if (!sendAudioChunks(player, entry.getVideoId(), entry.getTitle(), audioBytes, -1L)) {
                audioTooLarge(generation, entry, index, audioBytes.length);
                return;
            }
        }
        broadcastNowPlaying(entry.getTitle(), 0.0f);

        if (state.isSyncing()) {
            return;
        }

        if (isPendingChartDuration(generation, entry.getVideoId())) {
            return;
        }

        cancelFuture(advanceFuture);
        long delay = state.getCurrentTrackDurationMs() + NEXT_TRACK_DELAY_MS;
        advanceFuture = scheduleServerTask(new Runnable() {

            @Override
            public void run() {
                if (generation == musicGeneration) {
                    playNext();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void beginInitialPlaybackSync(List<EntityPlayerMP> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (EntityPlayerMP player : players) {
            state.beginLateJoin(player.getUniqueID(), 0L, now);
        }
        scheduleSyncTimeout();
        LOGGER.info("Waiting for " + players.size() + " players before starting the current track");
    }

    private void requestLateJoinAudio(final EntityPlayerMP player, final UUID playerUuid, final PlaylistEntry entry,
        final String videoId) {
        final long generation = musicGeneration;
        scheduler.submit(new Runnable() {

            @Override
            public void run() {
                try {
                    Path filePath = audioDownloadService.getFilePath(videoId);
                    if (filePath == null || !Files.isRegularFile(filePath)) {
                        enqueueLateJoinFailure(playerUuid, videoId);
                        return;
                    }
                    final byte[] audioBytes = readAudioBytes(filePath);
                    enqueueServerTask(new Runnable() {

                        @Override
                        public void run() {
                            if (generation != musicGeneration || !state.isSyncing()
                                || !videoId.equals(state.getCurrentVideoId())
                                || !state.containsPending(playerUuid)) {
                                return;
                            }
                            if (audioBytes.length == 0) {
                                removeLateJoinPending(playerUuid, videoId);
                                return;
                            }
                            if (!sendAudioChunks(player, videoId, entry.getTitle(), audioBytes, -1L)) {
                                LOGGER.warning("Audio file is too large for late-join transfer: " + videoId);
                                removeLateJoinPending(playerUuid, videoId);
                            }
                        }
                    });
                } catch (IOException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to read audio file for late-join resend (" + videoId + ")",
                        exception);
                    enqueueLateJoinFailure(playerUuid, videoId);
                } catch (RuntimeException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to locate or read audio file for late-join resend (" + videoId + ")",
                        exception);
                    enqueueLateJoinFailure(playerUuid, videoId);
                }
            }
        });
    }

    private void enqueueLateJoinFailure(final UUID playerUuid, final String videoId) {
        enqueueServerTask(new Runnable() {

            @Override
            public void run() {
                removeLateJoinPending(playerUuid, videoId);
            }
        });
    }

    private void removeLateJoinPending(UUID playerUuid, String videoId) {
        if (!state.isSyncing() || !videoId.equals(state.getCurrentVideoId())) {
            return;
        }
        if (state.removePending(playerUuid) && !state.hasPendingPlayers()) {
            doResume();
        }
    }

    private void scheduleSyncTimeout() {
        if (syncTimeoutFuture != null) {
            return;
        }
        final long generation = musicGeneration;
        syncTimeoutFuture = scheduleServerTask(new Runnable() {

            @Override
            public void run() {
                if (generation == musicGeneration && state.isSyncing()) {
                    LOGGER.warning(
                        "Late-join synchronization timed out with " + state.getPendingPlayerCount()
                            + " pending players");
                    doResume(generation);
                }
            }
        }, LATE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void doResume() {
        doResume(musicGeneration);
    }

    private void doResume(final long generation) {
        if (generation != musicGeneration || !state.isSyncing()) {
            return;
        }
        long pausedPositionMs = state.getPausedPositionMs();
        long now = System.currentTimeMillis();
        long pauseDuration = Math.max(0L, now - state.getPauseStartTime());
        long resumeAtMs = now + PLAYBACK_SYNC_START_LEAD_MS;
        state.resume(resumeAtMs);
        cancelFuture(syncTimeoutFuture);
        syncTimeoutFuture = null;

        ResumePacket resumePacket = new ResumePacket(pausedPositionMs, resumeAtMs);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(resumePacket, player);
        }
        LOGGER
            .info("Resuming all clients at " + pausedPositionMs + "ms after a " + pauseDuration + "ms late-join pause");

        cancelFuture(advanceFuture);
        long remaining = state.getCurrentTrackDurationMs() - pausedPositionMs + NEXT_TRACK_DELAY_MS;
        long startDelay = Math.max(0L, resumeAtMs - System.currentTimeMillis());
        if (remaining > 0L && state.isPlaying()) {
            advanceFuture = scheduleServerTask(new Runnable() {

                @Override
                public void run() {
                    if (generation == musicGeneration) {
                        playNext();
                    }
                }
            }, startDelay + remaining, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleNextTrack(long positionMs, long durationMs) {
        scheduleNextTrack(positionMs, durationMs, 0L);
    }

    private void scheduleNextTrack(long positionMs, long durationMs, long startAtMs) {
        cancelFuture(advanceFuture);
        long remaining = durationMs - positionMs + NEXT_TRACK_DELAY_MS;
        if (startAtMs > 0L) {
            remaining += Math.max(0L, startAtMs - System.currentTimeMillis());
        }
        final long generation = musicGeneration;
        if (remaining > 0L && state.isPlaying() && !state.isPaused()) {
            advanceFuture = scheduleServerTask(new Runnable() {

                @Override
                public void run() {
                    if (generation == musicGeneration) {
                        playNext();
                    }
                }
            }, remaining, TimeUnit.MILLISECONDS);
        }
    }

    private long currentPositionMs(long nowMs, long durationMs) {
        long elapsed = Math.max(0L, nowMs - state.getPlaybackStartTime());
        return Math.min(Math.max(0L, durationMs - 1L), elapsed);
    }

    private void broadcastProgress() {
        int currentIndex = state.getCurrentIndex();
        if (isRadioActive() || !state.isPlaying()
            || state.isPaused()
            || state.isSyncing()
            || currentIndex < 0
            || currentIndex >= state.size()
            || isEmpty(state.getCurrentVideoId())) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - state.getPlaybackStartTime());
        PlaylistEntry entry = state.get(currentIndex);
        broadcastNowPlaying(entry.getTitle(), progressFor(elapsed, state.getCurrentTrackDurationMs()));
    }

    private boolean isCurrentMusicEntry(long generation, PlaylistEntry entry, int index) {
        return generation == musicGeneration && !isRadioActive()
            && entry != null
            && state.isPlaying()
            && state.getCurrentIndex() == index
            && index >= 0
            && index < state.size()
            && entry.getVideoId()
                .equals(
                    state.get(index)
                        .getVideoId());
    }

    private boolean isPendingChartDuration(long generation, String videoId) {
        return generation == pendingChartDurationGeneration && videoId != null
            && videoId.equals(pendingChartDurationVideoId);
    }

    private boolean sendAudioChunks(EntityPlayerMP player, String videoId, String title, byte[] audioBytes,
        long startOffsetMs) {
        if (player == null || audioBytes == null || audioBytes.length == 0) {
            return false;
        }
        int chunkSize = AudioChunkPacket.CHUNK_SIZE;
        long totalChunksLong = ((long) audioBytes.length - 1L) / chunkSize + 1L;
        if (totalChunksLong > AudioChunkPacket.MAX_CHUNKS) {
            return false;
        }
        int totalChunks = (int) totalChunksLong;
        for (int index = 0; index < totalChunks; index++) {
            int start = index * chunkSize;
            int end = Math.min(start + chunkSize, audioBytes.length);
            byte[] chunk = Arrays.copyOfRange(audioBytes, start, end);
            HorizonRadioNetwork.CHANNEL
                .sendTo(new AudioChunkPacket(videoId, title, index, totalChunks, startOffsetMs, chunk), player);
        }
        return true;
    }

    private void syncToAll() {
        PlaylistSyncPacket packet = new PlaylistSyncPacket(toPacketEntries(state.snapshot()));
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastNowPlaying(String title, float progress) {
        NowPlayingPacket packet = new NowPlayingPacket(title, progress);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastLoopState(boolean looping) {
        LoopStatePacket packet = new LoopStatePacket(looping);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastShuffleState(boolean shuffling) {
        ShuffleStatePacket packet = new ShuffleStatePacket(shuffling);
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private RadioStatePacket radioStatePacket(String inactiveStatus) {
        String status = radioState.isRadioActive() ? radioState.getStatus()
            : (isEmpty(inactiveStatus) ? radioState.getStatus() : inactiveStatus);
        return new RadioStatePacket(
            radioState.isRadioActive(),
            radioState.getGeneration(),
            radioState.getStationUuid(),
            radioState.getStationName(),
            PacketBufferUtil.truncateUtf8(safe(status), RadioStatePacket.MAX_STATUS_BYTES),
            radioState.getMode() == RadioPlaybackState.Mode.MUSIC);
    }

    private void broadcastRadioState(String inactiveStatus) {
        broadcastRadioState(radioStatePacket(inactiveStatus));
    }

    private void broadcastRadioState(RadioStatePacket packet) {
        if (radioStateBroadcastObserver != null) {
            radioStateBroadcastObserver.accept(packet);
        }
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastRadioStart(long generation, long firstSequence) {
        broadcastRadioStart(new RadioAudioStartPacket(generation, firstSequence, 44100, 2, 16, false));
    }

    private void broadcastRadioStart(RadioAudioStartPacket packet) {
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private void broadcastRadioChunk(long generation, long sequence, byte[] data) {
        broadcastRadioChunk(new RadioAudioChunkPacket(generation, sequence, data));
    }

    private void broadcastRadioChunk(RadioAudioChunkPacket packet) {
        for (EntityPlayerMP player : onlinePlayersSnapshot()) {
            HorizonRadioNetwork.CHANNEL.sendTo(packet, player);
        }
    }

    private List<PlaylistSyncPacket.Entry> toPacketEntries(List<PlaylistEntry> entries) {
        List<PlaylistSyncPacket.Entry> packetEntries = new ArrayList<PlaylistSyncPacket.Entry>();
        for (PlaylistEntry entry : entries) {
            packetEntries.add(
                new PlaylistSyncPacket.Entry(
                    entry.getVideoId(),
                    entry.getTitle(),
                    entry.getDuration(),
                    entry.getAddedBy()));
        }
        return packetEntries;
    }

    private List<EntityPlayerMP> onlinePlayersSnapshot() {
        List<EntityPlayerMP> players = new ArrayList<EntityPlayerMP>();
        if (server == null) {
            return players;
        }
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            if (candidate instanceof EntityPlayerMP) {
                players.add((EntityPlayerMP) candidate);
            }
        }
        return players;
    }

    private ScheduledFuture<?> scheduleServerTask(final Runnable task, long delay, TimeUnit unit) {
        return scheduler.schedule(new Runnable() {

            @Override
            public void run() {
                enqueueServerTask(task);
            }
        }, delay, unit);
    }

    private void enqueueServerTask(final Runnable task) {
        if (server == null) {
            if (!shuttingDown) {
                task.run();
            }
            return;
        }
        ServerThreadExecutor.execute(server, new Runnable() {

            @Override
            public void run() {
                if (!shuttingDown) {
                    task.run();
                }
            }
        });
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void sendChat(EntityPlayerMP player, EnumChatFormatting color, String message) {
        if (player == null) {
            return;
        }
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[HorizonRadio] " + color + message));
    }

    private boolean acceptsPlayer(EntityPlayerMP player) {
        return player != null || server == null;
    }

    private static String playerName(EntityPlayerMP player) {
        return player == null ? "test" : player.getCommandSenderName();
    }

    private static int configuredMaxPlaylistSize() {
        HorizonRadioConfig config = HorizonRadio.getConfig();
        return config == null ? HorizonRadioConfig.DEFAULT_MAX_PLAYLIST_SIZE : config.getMaxPlaylistSize();
    }

    private static long configuredMaxTrackDurationMs() {
        HorizonRadioConfig config = HorizonRadio.getConfig();
        int minutes = config == null ? HorizonRadioConfig.DEFAULT_MAX_TRACK_DURATION_MINUTES
            : config.getMaxTrackDurationMinutes();
        return minutes * 60L * 1000L;
    }

    private static long maxAudioBytes() {
        return (long) AudioChunkPacket.CHUNK_SIZE * AudioChunkPacket.MAX_CHUNKS;
    }

    public static boolean supportsAudioLength(long audioLength) {
        return audioLength > 0L
            && ((audioLength - 1L) / AudioChunkPacket.CHUNK_SIZE + 1L) <= AudioChunkPacket.MAX_CHUNKS;
    }

    public static boolean isSearchDurationAllowed(String duration, long maxDurationMs) {
        long durationMs = DurationParser.parseMillisStrict(duration);
        return durationMs >= 0L && durationMs < maxDurationMs;
    }

    private static boolean isKnownDuration(String duration) {
        return DurationParser.parseMillisStrict(duration) >= 0L;
    }

    static boolean shouldStartPlaylistPlayback(boolean radioActive, boolean musicPlaying) {
        return !radioActive && !musicPlaying;
    }

    /** Java 8 replacement for the newer read-all-bytes convenience APIs. */
    private static byte[] readAudioBytes(Path filePath) throws IOException {
        InputStream input = Files.newInputStream(filePath);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static float progressFor(long elapsedMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0.0f;
        }
        return Math.min(1.0f, Math.max(0.0f, (float) elapsedMs / (float) durationMs));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim()
            .length() == 0;
    }

    static boolean isLatestSearchRequest(Long currentRequest, long requestGeneration) {
        return currentRequest != null && currentRequest.longValue() == requestGeneration;
    }

    /** Parses values such as {@code 3:45} and {@code 1:02:30}. */
    static long parseDuration(String duration) {
        return PlaylistState.parseDuration(duration);
    }

    public void shutdown() {
        shuttingDown = true;
        searchRequestGenerations.clear();
        ++radioSelectionRequest;
        radioState.stop();
        radioLastSequence = -1L;
        radioStreamService.shutdown();
        cancelActiveAudioDownload();
        for (String videoId : new HashSet<String>(preloadingAudio)) {
            audioDownloadService.cancelDownload(videoId);
        }
        preloadingAudio.clear();
        cancelFuture(advanceFuture);
        cancelFuture(progressFuture);
        cancelFuture(syncTimeoutFuture);
        scheduler.shutdownNow();
        state.clear();
        LOGGER.info("HorizonRadio: Playlist manager shut down");
    }
}
