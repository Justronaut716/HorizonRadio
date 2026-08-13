package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class GuiLayoutTest {

    private final RecordingTransport transport = new RecordingTransport();

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.setTransport(transport);
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.loadClientConfig(null);
        HorizonRadioClient.setVolume(1.0f);
        HorizonRadioClient.setTransport(new HorizonRadioClient.NoopClientTransport());
    }

    @Test
    public void playlistAndNowPlayingCacheSurviveScreenClosureAndAreCopied() {
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("abc", "Song", "3:21", "Alice"));

        HorizonRadioClient.updatePlaylist(entries);
        HorizonRadioClient.updateNowPlaying("Song", 0.5f);

        List<HorizonRadioScreen.PlaylistEntry> cached = HorizonRadioClient.getCachedPlaylist();
        cached.clear();

        assertEquals(entries, HorizonRadioClient.getCachedPlaylist());
        assertEquals("Song", HorizonRadioClient.getCachedNowPlaying());
        assertEquals(0.5f, HorizonRadioClient.getCachedProgress(), 0.0001f);
    }

    @Test
    public void cacheUpdatesRefreshTheRegisteredOpenScreen() {
        HorizonRadioScreen screen = new HorizonRadioScreen();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
            entries.add(new HorizonRadioScreen.PlaylistEntry("live", "Live song", "2:00", "Alice"));

            HorizonRadioClient.updatePlaylist(entries);
            HorizonRadioClient.updateNowPlaying("Live song", 0.75f);

            assertEquals(entries, screen.getPlaylistSnapshot());
            assertEquals("Live song", screen.getNowPlayingSnapshot());
            assertEquals(0.75f, screen.getPlaybackProgressSnapshot(), 0.0001f);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void volumeSliderUpdatesWhileTheButtonIsDragged() {
        HorizonRadioClient.setVolume(0.0f);
        HorizonRadioVolumeSlider slider = new HorizonRadioVolumeSlider(3, 10, 10, 100, 20, 0.0f);

        assertTrue(slider.mousePressed(null, 10, 15));
        slider.mouseDragged(null, 60, 15);
        slider.mouseReleased(60, 15);

        assertEquals(0.5f, slider.getValue(), 0.01f);
        assertEquals(0.5f, HorizonRadioClient.getVolume(), 0.01f);
    }

    @Test
    public void chartRefreshButtonIsDisabledWhileLoadingOrRequesting() {
        assertFalse(HorizonRadioScreen.shouldEnableChartRefreshButton(true, false));
        assertFalse(HorizonRadioScreen.shouldEnableChartRefreshButton(false, true));
        assertTrue(HorizonRadioScreen.shouldEnableChartRefreshButton(false, false));
    }

    @Test
    public void chartsStartEmptyWithoutRequestingGlobalCharts() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();

            assertEquals(0, transport.chartRequestCount);
            assertEquals("", screen.getChartRegionCode());
            assertFalse(screen.refreshButton().enabled);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void emptyChartsHideTheGenericHeaderButKeepCountryHeaders() {
        assertEquals("", invokeChartHeaderLabel(false, ""));
        assertEquals("Top 50 Charts Germany (Weekly)", invokeChartHeaderLabel(true, "Germany"));
    }

    @Test
    public void topButtonsMatchTheHeightsOfTheirNeighboringControls() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();

            assertEquals(screenConstant("TAB_BUTTON_HEIGHT"), screen.refreshButton().height);
            assertEquals(screen.searchField().height + 2, screen.searchButton().height);
            assertEquals(screen.searchField().yPosition - 1, screen.searchButton().yPosition);
            assertEquals(screenConstant("CONTROL_BUTTON_WIDTH"), screen.searchButton().width);
            assertTrue(screen.searchField().width > 220);
            assertEquals(screenConstant("SEARCH_SIDE_MARGIN"), screen.searchField().xPosition - 1);
            assertEquals(screen.searchField().xPosition + screen.searchField().width, screen.searchButton().xPosition);
            assertEquals(
                screenConstant("PANEL_WIDTH") - screenConstant("SEARCH_SIDE_MARGIN"),
                screen.searchButton().xPosition + screen.searchButton().width);
            assertEquals(
                "ControlButton",
                screen.searchButton()
                    .getClass()
                    .getSimpleName());
            assertEquals("", screen.searchButton().displayString);
            assertEquals(0xFFA0A0A0, screen.searchButtonBorderColor());
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void songSearchProgressBarIsVisibleOnlyWhileLoading() {
        assertTrue(HorizonRadioScreen.shouldDrawSearchProgressBar(true));
        assertFalse(HorizonRadioScreen.shouldDrawSearchProgressBar(false));
    }

    @Test
    public void songResultsMoveUpWhenLoadingBarIsHidden() {
        assertEquals(70, HorizonRadioScreen.searchListTopOffset(true));
        assertEquals(55, HorizonRadioScreen.searchListTopOffset(false));
    }

    @Test
    public void chartAndRadioProgressBarsAreVisibleOnlyWhileLoading() {
        assertTrue(HorizonRadioScreen.shouldDrawProgressBar(true));
        assertFalse(HorizonRadioScreen.shouldDrawProgressBar(false));
    }

    @Test
    public void progressEstimatesMatchTheExpectedRequestDurations() {
        assertEquals(1500L, HorizonRadioScreen.progressEstimateMillis(1));
        assertEquals(1000L, HorizonRadioScreen.progressEstimateMillis(0));
        assertEquals(400L, HorizonRadioScreen.progressEstimateMillis(3));
    }

    @Test
    public void completedSearchStartsAShortRevealWindowBeforeResultsAppear() {
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.selectSearchTab();
        screen.setSearchText("jazz");
        screen.invokeSearchAction();

        screen.updateSearchResults(singleResult());

        assertTrue(screen.hasSearchResultsRevealPending());
        assertFalse(HorizonRadioScreen.shouldRevealResults(1000L, 1000L + 150L - 1L));
        assertTrue(HorizonRadioScreen.shouldRevealResults(1000L + 150L, 1000L + 150L));
    }

    @Test
    public void resultsRevealKeepsTheShortDelayWithoutAReadySound() {
        assertEquals(150L, HorizonRadioScreen.resultRevealDelayMillis());
    }

    @Test
    public void radioListUsesProgressSpaceOnlyWhileLoading() {
        assertEquals(70, HorizonRadioScreen.radioListTopOffset(true));
        assertEquals(55, HorizonRadioScreen.radioListTopOffset(false));
    }

    @Test
    public void chartLayoutKeepsSearchControlsAndContentRowsSeparate() {
        int searchControlsBottom = 30 + 20;
        int headerTop = screenConstant("CONTENT_HEADER_Y_OFFSET");
        int listBottom = screenConstant("CONTENT_LIST_TOP_OFFSET")
            + screenConstant("MAX_VISIBLE_ROWS") * screenConstant("ROW_HEIGHT")
            - 2;
        int nowPlayingTop = screenConstant("PANEL_HEIGHT") - screenConstant("CONTROL_CENTER_HEIGHT")
            - screenConstant("NOW_PLAYING_HEIGHT")
            - 5;

        assertTrue("Chart heading is hidden behind the search field", searchControlsBottom <= headerTop);
        assertTrue("Chart rows overlap the now-playing controls", listBottom <= nowPlayingTop);
    }

    @Test
    public void chartsBulkButtonLeavesSpaceBelowSearchControls() {
        int searchButtonBottom = screenConstant("SEARCH_BUTTON_Y_OFFSET") + screenConstant("SEARCH_BUTTON_HEIGHT");
        int bulkButtonTop = screenConstant("CHARTS_BULK_BUTTON_Y_OFFSET");

        assertTrue("Charts bulk button overlaps the search controls", bulkButtonTop > searchButtonBottom);
    }

    @Test
    public void pendingChartAddUsesMinusAndBlocksDuplicateRequestsUntilCompletion() {
        TestScreen screen = new TestScreen();
        List<HorizonRadioScreen.SearchResult> results = singleResult();

        assertEquals(results, screen.beginChartAdd(results));
        assertTrue(screen.isChartAddPending("video"));
        assertEquals("-", HorizonRadioScreen.chartQueueButtonLabel(false, true));
        assertTrue(
            screen.beginChartAdd(results)
                .isEmpty());

        screen.completeChartAdds(Arrays.asList("video"));

        assertFalse(screen.isChartAddPending("video"));
        assertEquals("+", HorizonRadioScreen.chartQueueButtonLabel(false, false));
    }

    @Test
    public void chartBulkAddSkipsQueuedAndPendingEntries() {
        TestScreen screen = new TestScreen();
        List<HorizonRadioScreen.SearchResult> results = Arrays.asList(
            new HorizonRadioScreen.SearchResult("queued", "Queued", "", "2:00", ""),
            new HorizonRadioScreen.SearchResult("pending", "Pending", "", "2:00", ""),
            new HorizonRadioScreen.SearchResult("new", "New", "", "2:00", ""));
        screen.updatePlaylist(Arrays.asList(new HorizonRadioScreen.PlaylistEntry("queued", "Queued", "2:00", "Alice")));
        screen.beginChartAdd(Arrays.asList(new HorizonRadioScreen.SearchResult("pending", "Pending", "", "2:00", "")));

        List<HorizonRadioScreen.SearchResult> request = screen.beginChartAdd(results);

        assertEquals(1, request.size());
        assertEquals("new", request.get(0).videoId);
    }

    @Test
    public void playlistUsesCompactContentMarginsWithoutSearchField() {
        assertEquals(25, screenConstant("PLAYLIST_HEADER_Y_OFFSET"));
        assertEquals(45, screenConstant("PLAYLIST_LIST_TOP_OFFSET"));
    }

    @Test
    public void playlistTitleUsesBalancedVerticalMargins() {
        int titleTop = screenConstant("PLAYLIST_TITLE_Y_OFFSET");
        int tabBottom = screenConstant("TAB_BUTTON_Y") + screenConstant("TAB_BUTTON_HEIGHT");
        int listTop = screenConstant("PLAYLIST_LIST_TOP_OFFSET");
        int titleHeight = 8;

        assertEquals(31, titleTop);
        assertEquals(titleTop - tabBottom, listTop - titleTop - titleHeight);
    }

    @Test
    public void chartAndRadioLabelsUseBalancedSearchMargins() {
        int labelTop = screenConstant("CONTENT_LABEL_Y_OFFSET");
        int searchBottom = 50;
        int resultListTop = screenConstant("CONTENT_LIST_TOP_OFFSET");
        int labelHeight = 8;

        assertEquals(56, labelTop);
        assertEquals(labelTop - searchBottom, resultListTop - labelTop - labelHeight);
    }

    @Test
    public void chartsSearchUsesCanonicalRegionWithoutDiscoveryTransport() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();
            HorizonRadioClient.updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>(), "GLOBAL");
            screen.setSearchText("Germany");

            screen.invokeSearchAction();

            assertEquals("DE", screen.getChartRegionCode());
            assertFalse(HorizonRadioClient.isChartRequestPending());
            assertEquals(0, transport.chartRequestCount);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void emptyChartsSearchKeepsTheLastSelectedCountry() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();
            screen.updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>(), "US");
            int requestCount = transport.chartRequestCount;
            screen.setSearchText("");

            screen.invokeSearchAction();

            assertEquals(requestCount, transport.chartRequestCount);
            assertEquals("US", screen.getChartRegionCode());
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void globalChartsSearchIsRejected() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();
            screen.setSearchText("Global");

            screen.invokeSearchAction();

            assertEquals(0, transport.chartRequestCount);
            assertTrue(
                screen.chartSearchMessage()
                    .contains("not available"));
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void ambiguousChartsSearchKeepsCurrentResultsAndShowsMessage() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();
            screen.updateChartResults(singleResult(), "US");
            int requestCount = transport.chartRequestCount;
            screen.setSearchText("Congo");

            screen.invokeSearchAction();

            assertEquals(requestCount, transport.chartRequestCount);
            assertEquals(singleResult(), screen.chartResultsSnapshot());
            assertTrue(
                screen.chartSearchMessage()
                    .contains("ambiguous"));
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void unavailableChartRefreshDoesNotUseDiscoveryTransport() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();

            assertEquals(0, transport.chartRequestCount);
            assertEquals(0, transport.forceChartsRequestCount);
            screen.setSearchText("Germany");
            screen.invokeSearchAction();
            assertFalse(HorizonRadioClient.isChartRequestPending());
            assertTrue(screen.refreshButton().enabled);

            screen.invokeRefreshAction();
            screen.invokeRefreshAction();

            assertEquals(0, transport.chartRequestCount);
            assertEquals(0, transport.forceChartsRequestCount);
            assertFalse(HorizonRadioClient.isChartRequestPending());
            assertTrue(screen.refreshButton().enabled);

            HorizonRadioClient.updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>());
            assertFalse(HorizonRadioClient.isChartRequestPending());
            assertTrue(screen.refreshButton().enabled);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void volumeSliderPersistsOnlyAfterDraggingEnds() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-volume-slider")
            .toFile();
        try {
            HorizonRadioClient.loadClientConfig(directory);
            HorizonRadioClient.setVolume(0.0f);
            HorizonRadioVolumeSlider slider = new HorizonRadioVolumeSlider(3, 10, 10, 100, 20, 0.0f);

            assertTrue(slider.mousePressed(null, 10, 15));
            slider.mouseDragged(null, 60, 15);

            assertEquals(0.5f, HorizonRadioClient.getVolume(), 0.01f);
            assertEquals(
                0.0f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);

            slider.mouseReleased(60, 15);

            assertEquals(
                0.5f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.01f);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void unavailableChartRequestCompletesWithoutTransport() {
        HorizonRadioClient.sendChartsRequest(true);

        assertFalse(HorizonRadioClient.isChartRequestPending());
        assertTrue(
            HorizonRadioScreen.shouldEnableChartRefreshButton(false, HorizonRadioClient.isChartRequestPending()));
        assertEquals(0, transport.chartRequestCount);
    }

    @Test
    public void initGuiDisablesRefreshButtonWhenChartRequestWasAlreadyPending() {
        HorizonRadioClient.sendChartsRequest(false);
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();

            assertFalse(screen.refreshButton().enabled);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void loadingClientConfigRestoresVolumeAtStartup() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-volume-startup")
            .toFile();
        try {
            HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
            config.save(0.65f);

            HorizonRadioClient.loadClientConfig(directory);

            assertEquals(0.65f, HorizonRadioClient.getVolume(), 0.0001f);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void clientVolumeChangesPersistToClientConfig() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-volume-api")
            .toFile();
        try {
            HorizonRadioClient.loadClientConfig(directory);
            HorizonRadioClient.setVolume(0.4f);

            assertEquals(
                0.4f,
                HorizonRadioClientConfig.load(directory)
                    .getVolume(),
                0.0001f);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void clearingServerCachePreservesClientVolume() {
        HorizonRadioClient.loadClientConfig(null);
        HorizonRadioClient.setVolume(0.4f);

        HorizonRadioClient.clearCache();

        assertEquals(0.4f, HorizonRadioClient.getVolume(), 0.0001f);
    }

    @Test
    public void clientTransportExposesOnlyServerBoundOperations() {
        HorizonRadioClient.sendAddChartsToPlaylist(new ArrayList<HorizonRadioScreen.SearchResult>());
        HorizonRadioClient.sendAdd("abc", "Song", "3:21");
        HorizonRadioClient.sendPlayNow("abc", "Song", "3:21");
        HorizonRadioClient.sendRemove("abc");
        HorizonRadioClient.sendClearPlaylist();
        HorizonRadioClient.sendReorder(2, 1);
        HorizonRadioClient.sendSeek(0.75f);
        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();
        HorizonRadioClient.sendToggleLoop();
        HorizonRadioClient.sendToggleShuffle();

        assertNull(transport.searchQuery);
        assertFalse(transport.chartsRequest);
        assertFalse(transport.forceChartsRequest);
        assertNull(transport.importPlaylistUrl);
        assertNull(transport.importVideoUrl);
        assertEquals("abc|Song|3:21", transport.addRequest);
        assertEquals("abc|Song|3:21", transport.playNowRequest);
        assertTrue(transport.addChartsRequest);
        assertEquals("abc", transport.removedVideoId);
        assertTrue(transport.clearPlaylist);
        assertEquals("2|1", transport.reorderRequest);
        assertEquals(0.75f, transport.seekProgress, 0.0001f);
        assertTrue(transport.togglePlayback);
        assertTrue(transport.skipTrack);
        assertTrue(transport.previousTrack);
        assertTrue(transport.toggleLoop);
        assertTrue(transport.toggleShuffle);
    }

    @Test
    public void chartSelectionsSkipUnknownAndZeroDurationsBeforeConstructingPackets() {
        List<HorizonRadioClient.PlaylistSelection> selections = HorizonRadioScreen.toPlaylistSelections(
            Arrays.asList(
                new HorizonRadioScreen.SearchResult("unknown", "Unknown", "", "", ""),
                new HorizonRadioScreen.SearchResult("zero", "Zero", "", "0:00", ""),
                new HorizonRadioScreen.SearchResult("valid", "Valid", "", "2:00", "")));

        assertEquals(1, selections.size());
        assertEquals("valid", selections.get(0).videoId);
        assertEquals(120_000L, selections.get(0).durationMs);
    }

    @Test
    public void searchQueueButtonsTrackPlaylistAndIconsArePackaged() throws IOException {
        HorizonRadioScreen screen = new HorizonRadioScreen();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("queued", "Queued", "2:00", "Alice"));
        screen.updatePlaylist(entries);

        assertTrue(screen.isInQueue("queued"));
        assertFalse(screen.isInQueue("missing"));

        String[] iconNames = { "Shuffle.png", "Previous.png", "Play.png", "Next.png", "Repeat.png", "Pause.png",
            "Search.png" };
        for (String iconName : iconNames) {
            InputStream stream = GuiLayoutTest.class
                .getResourceAsStream("/assets/horizonradio/textures/gui/" + iconName);
            assertNotNull("Missing GUI icon " + iconName, stream);
            try {
                BufferedImage image = ImageIO.read(stream);
                assertNotNull("Invalid GUI icon " + iconName, image);
                assertEquals("Unexpected width for " + iconName, 128, image.getWidth());
                assertEquals("Unexpected height for " + iconName, 128, image.getHeight());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    public void searchQueueButtonAddsAndRemovesWithoutChangingTab() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        HorizonRadioScreen.SearchResult result = new HorizonRadioScreen.SearchResult(
            "video",
            "Song",
            "Channel",
            "2:00",
            "");
        List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
        results.add(result);
        screen.updateChartResults(results);

        screen.click(280, 75);

        assertTrue(transport.addChartsRequest);
        assertNull(transport.addRequest);
        assertNull(transport.removedVideoId);
        assertFalse(screen.isPlaylistTab());

        List<HorizonRadioScreen.PlaylistEntry> playlist = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        playlist.add(new HorizonRadioScreen.PlaylistEntry("video", "Song", "2:00", "Alice"));
        screen.updatePlaylist(playlist);
        transport.addRequest = null;

        screen.click(280, 75);

        assertNull(transport.addRequest);
        assertEquals("video", transport.removedVideoId);
        assertFalse(screen.isPlaylistTab());
    }

    @Test
    public void directChartClickPlaysNowAndSwitchesToPlaylist() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = resultScreen();

        screen.click(50, 77);

        assertEquals("video|120000", transport.playNowRequest);
        assertTrue(screen.isPlaylistTab());
        assertNull(transport.addRequest);
        assertFalse(transport.addChartsRequest);
        assertFalse(transport.stopRadio);
    }

    @Test
    public void directSearchClickPlaysNowAndSwitchesToPlaylist() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectSearchTab();
        screen.updateSearchResults(singleResult());

        screen.click(50, 75);

        assertEquals("video|120000", transport.playNowRequest);
        assertTrue(screen.isPlaylistTab());
        assertFalse(transport.stopRadio);
    }

    @Test
    public void queueRowClickSendsPlayNowOnlyOnRelease() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("video", "Song", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("other", "Other", "3:00", "Bob"));
        screen.updatePlaylist(entries);

        screen.click(50, 52);
        assertNull(transport.playNowRequest);

        screen.release(50, 52);

        assertEquals("video|120000", transport.playNowRequest);
        assertNull(transport.removedVideoId);
        assertNull(transport.reorderRequest);
        assertFalse(transport.stopRadio);
    }

    @Test
    public void queueRowDragStillReordersInsteadOfPlaying() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("first", "First", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("second", "Second", "3:00", "Bob"));
        screen.updatePlaylist(entries);

        screen.click(50, 52);
        screen.moveHeldMouse(50, 77);
        screen.release(50, 77);

        assertEquals("0|1", transport.reorderRequest);
        assertNull(transport.playNowRequest);
    }

    @Test
    public void currentQueueRowCanBeClickedButRemainsNonDraggable() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("current", "Current", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("next", "Next", "3:00", "Bob"));
        screen.updatePlaylist(entries);
        screen.updateNowPlaying("Current", 0.5f);

        screen.click(50, 52);
        screen.release(50, 52);

        assertEquals("current|120000", transport.playNowRequest);
        assertNull(transport.reorderRequest);

        transport.playNowRequest = null;
        screen.click(50, 52);
        screen.moveHeldMouse(50, 77);
        screen.release(50, 77);

        assertNull(transport.playNowRequest);
        assertNull(transport.reorderRequest);
    }

    @Test
    public void radioTabLoadsPopularStationsAndSearchesWithTheSharedField() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);

        screen.selectRadioTab();

        assertTrue(screen.isRadioTab());
        assertNull(transport.radioSearchQuery);

        screen.initialize();
        screen.selectRadioTab();
        screen.setSearchText("jazz");
        screen.invokeSearchAction();

        assertNull(transport.radioSearchQuery);
        assertFalse(screen.isRadioLoading());
    }

    @Test
    public void radioRowSelectionSendsUuidOnly() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectRadioTab();
        screen.updateRadioResults(singleRadioStation());

        screen.click(280, 75);

        assertEquals("radio-uuid", transport.selectedRadioUuid);
        assertNull(transport.playNowRequest);
        assertNull(transport.addRequest);
        assertNull(transport.removedVideoId);
    }

    @Test
    public void radioActiveStationUsesRadioStateForRowAndNowPlaying() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.updateRadioResults(singleRadioStation());

        assertTrue(screen.isActiveRadioStation("radio-uuid"));
        assertEquals("Station", screen.getNowPlayingSnapshot());
        assertEquals("LIVE", screen.getRadioStatusSnapshot());
    }

    @Test
    public void activeRadioNowPlayingLabelUsesAnOnAirPrefix() {
        assertEquals("BigFM", HorizonRadioScreen.activeRadioNowPlayingLabel("BigFM"));
        assertFalse(
            HorizonRadioScreen.activeRadioNowPlayingLabel("BigFM")
                .contains("Playing"));
        assertEquals("ON AIR\u00B7 BigFM", HorizonRadioScreen.radioNowPlayingDisplayLabel("BigFM"));
        assertEquals("BigFM", invokeRadioNowPlayingDisplayLabel("BigFM", false));
        assertEquals("ON AIR\u00B7 BigFM", invokeRadioNowPlayingDisplayLabel("BigFM", true));
    }

    @Test
    public void activeRadioDoesNotHighlightTheFirstPlaylistRow() {
        assertFalse(HorizonRadioScreen.isPlaylistRowPlaying(0, true, true));
        assertTrue(HorizonRadioScreen.isPlaylistRowPlaying(0, true, false));
        assertFalse(HorizonRadioScreen.isPlaylistRowPlaying(1, true, false));
    }

    @Test
    public void inactiveRadioFailureIsShownWithoutResurrectingStoppedMusic() {
        HorizonRadioClient.updateNowPlaying("Old song", 0.5f);
        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "Playing Station"));
        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.stopped(1L, "Radio stream stopped producing PCM data"));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);

        screen.initialize();

        assertNull(screen.getNowPlayingSnapshot());
        assertEquals("Radio stream stopped producing PCM data", screen.getRadioStatusSnapshot());
        assertTrue(screen.musicControlsVisible());
        assertFalse(screen.radioControlsVisible());
    }

    @Test
    public void activeRadioNameLeavesPaddingBeforeLiveMarker() {
        assertEquals(242, HorizonRadioScreen.radioStationNameMaxWidth(0));
        assertEquals(242, HorizonRadioScreen.radioStationNameMaxWidth(47));
    }

    @Test
    public void radioResultsUseSixRowScrollbarForStationSelection() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectRadioTab();
        screen.updateRadioResults(radioStations(12));

        screen.click(295, 71);
        screen.moveHeldMouse(295, 217);
        screen.release(295, 217);
        screen.click(50, 77);

        assertEquals("radio-uuid-6", transport.selectedRadioUuid);
    }

    @Test
    public void emptyRadioResultsFinishLoadingAndExposeEmptyState() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectRadioTab();

        screen.updateRadioResults(new ArrayList<HorizonRadioScreen.RadioStationResult>());

        assertFalse(screen.isRadioLoading());
        assertTrue(screen.isRadioEmpty());
    }

    @Test
    public void radioUsesMusicControlCenterAndMiddleButtonStopsRadio() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();

        assertTrue(screen.musicControlsVisible());
        assertTrue(screen.radioControlsVisible());
        assertTrue(screen.controlButton(4).visible);
        assertTrue(screen.controlButton(5).visible);
        assertTrue(screen.controlButton(6).visible);
        assertTrue(screen.controlButton(7).visible);
        assertTrue(screen.controlButton(8).visible);
        assertFalse(screen.controlButton(4).enabled);
        assertFalse(screen.controlButton(5).enabled);
        assertTrue(screen.controlButton(6).enabled);
        assertFalse(screen.controlButton(7).enabled);
        assertFalse(screen.controlButton(8).enabled);

        screen.invokePlaybackAction();

        assertTrue(transport.stopRadio);
        assertFalse(transport.togglePlayback);
    }

    @Test
    public void radioPlayButtonResumesTheLastStationAfterStopping() {
        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.inactive(1L, "radio-uuid", "Station", "", false));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.selectRadioTab();
        screen.updateRadioPresentation(ClientRadioPresentation.inactive(1L, "radio-uuid", "Station", "", false));

        assertFalse(screen.controlButton(4).enabled);
        assertFalse(screen.controlButton(5).enabled);
        assertTrue(screen.controlButton(6).enabled);
        assertFalse(screen.controlButton(7).enabled);
        assertFalse(screen.controlButton(8).enabled);
        assertEquals("Station", screen.getNowPlayingSnapshot());

        screen.invokePlaybackAction();

        assertEquals("radio-uuid", transport.selectedRadioUuid);
        assertFalse(transport.stopRadio);
        assertFalse(transport.togglePlayback);
    }

    @Test
    public void pausedRadioDoesNotReplaceCurrentlyPlayingMusic() {
        HorizonRadioClient.updateNowPlaying("Song", 0.5f);
        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.inactive(1L, "radio-uuid", "Station", "", false));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);

        screen.initialize();

        assertEquals("Song", screen.getNowPlayingSnapshot());
    }

    @Test
    public void musicModeDoesNotDisplayTheRememberedRadioStation() {
        HorizonRadioClient
            .updateRadioPresentation(ClientRadioPresentation.inactive(1L, "radio-uuid", "Station", "", true));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);

        screen.initialize();

        assertNull(screen.getNowPlayingSnapshot());
    }

    @Test
    public void pausedRadioDoesNotUseMusicProgressBar() {
        assertFalse(invokeShouldDrawPlaybackProgress(false, true));
        assertTrue(invokeShouldDrawPlaybackProgress(false, false));
        assertFalse(invokeShouldDrawPlaybackProgress(true, false));
    }

    @Test
    public void radioPlaybackUsesSourceAwareTrackSynchronization() throws IOException {
        String packet = readSource("src/main/java/com/horizonradio/network/packets/TrackSyncPacket.java");
        String manager = readSource("src/main/java/com/horizonradio/server/PlaylistManager.java");

        assertTrue(packet.contains("MediaSourceType.RADIO"));
        assertTrue(packet.contains("radio track synchronization cannot carry finite timing"));
        assertTrue(manager.contains("TrackSyncPacket.radio"));
        assertFalse(manager.contains("RadioPlaybackState"));
    }

    @Test
    public void forgeGuiContractPreservesActiveGeometryAndInputBoundaries() throws IOException {
        String screen = readSource("src/main/java/com/horizonradio/client/HorizonRadioScreen.java");
        String keybinds = readSource("src/main/java/com/horizonradio/client/HorizonRadioKeybinds.java");
        String proxy = readSource("src/main/java/com/horizonradio/client/ClientProxy.java");

        assertTrue(screen.contains("extends GuiScreen"));
        assertTrue(screen.contains("PANEL_WIDTH = 300"));
        assertTrue(screen.contains("PANEL_HEIGHT = 285"));
        assertTrue(screen.contains("MAX_VISIBLE_ROWS = 6"));
        assertTrue(screen.contains("GuiTextField"));
        assertTrue(screen.contains("setMaxStringLength(100)"));
        assertTrue(screen.contains("actionPerformed(GuiButton"));
        assertTrue(screen.contains("keyTyped(char"));
        assertTrue(screen.contains("keyTyped(String"));
        assertTrue(screen.contains("mouseClicked(int"));
        assertTrue(screen.contains("handleMouseInput()"));
        assertTrue(screen.contains("doesGuiPauseGame()"));
        assertFalse(screen.contains("throws IOException"));
        assertTrue(screen.contains("drawRect"));
        assertTrue(screen.contains("drawString"));
        assertTrue(screen.contains("drawCenteredString"));
        assertTrue(screen.contains("drawProgressBar"));
        assertTrue(screen.contains("drawChartsTab"));
        assertTrue(screen.contains("searchLoading"));
        assertTrue(screen.contains("SEARCH_PROGRESS_HEIGHT"));
        assertTrue(screen.contains("drawProgressBar(left, top, chartProgress)"));
        assertTrue(screen.contains("drawProgressBar(left, top, radioProgress)"));
        assertFalse(screen.contains("drawString(fontRendererObj, \"Radio stations\""));
        assertTrue(screen.contains("HorizonRadioVolumeSlider"));
        assertTrue(screen.contains("boolean canRemove = true"));
        assertFalse(screen.contains("net." + "fabricmc"));

        assertTrue(keybinds.contains("Keyboard.KEY_N"));
        assertTrue(keybinds.contains("key.horizonradio.open_gui"));
        assertTrue(keybinds.contains("hasLoadedClientWorld"));
        assertTrue(keybinds.contains("minecraft.theWorld != null && minecraft.thePlayer != null"));
        assertTrue(proxy.contains("FMLCommonHandler.instance()"));
        assertTrue(proxy.contains(".bus()"));
        assertTrue(proxy.contains(".register(new ClientEvents(clientTaskScheduler))"));
        assertTrue(proxy.contains("ClientDisconnectionFromServerEvent"));
        assertTrue(screen.contains("setActiveScreen(this)"));
        assertTrue(screen.contains("clearActiveScreen(this)"));
        assertTrue(screen.contains("mouseDragged(Minecraft"));
        assertTrue(screen.contains("mouseClickMove(int"));
        assertTrue(screen.contains("mouseMovedOrUp(int"));
        assertTrue(screen.contains("draggedPlaylistIndex"));
        assertTrue(screen.contains("sendReorder"));
        assertTrue(screen.contains("sendSeek"));
        assertTrue(screen.contains("isTimeBarAt"));
        assertTrue(screen.contains("sendTogglePlayback"));
        assertTrue(screen.contains("updatePlaybackPaused"));
        assertTrue(screen.contains("sendSkipTrack"));
        assertTrue(screen.contains("sendToggleLoop"));
        assertTrue(screen.contains("updateLooping"));
        assertTrue(screen.contains("sendToggleShuffle"));
        assertTrue(screen.contains("updateShuffling"));
        assertTrue(screen.contains("sendImportPlaylist"));
        assertTrue(screen.contains("looksLikePlaylistUrl"));
        assertTrue(screen.contains("looksLikeVideoUrl"));
        assertTrue(screen.contains("openCharts"));
        assertFalse(proxy.contains("HorizonRadioClient.sendChartsRequest(false)"));
        assertTrue(screen.contains("sendAddChartsToPlaylist"));
        assertTrue(screen.contains("beginChartLoading"));
        assertTrue(screen.contains("drawQueueButtonAt"));
        assertTrue(screen.contains("areAllChartsInQueue"));
        assertTrue(screen.contains("isChartsBulkButtonAt"));
        assertTrue(screen.contains("shouldDrawProgressBar"));
        assertTrue(screen.contains("formatTime"));
        assertTrue(screen.contains("currentDuration"));
        assertTrue(screen.contains("addControlButtons"));
        assertTrue(screen.contains("CONTROL_BUTTON_COUNT = 5"));
        assertTrue(screen.contains("textures/gui/Shuffle.png"));
        assertTrue(screen.contains("textures/gui/Previous.png"));
        assertTrue(screen.contains("textures/gui/Play.png"));
        assertTrue(screen.contains("textures/gui/Next.png"));
        assertTrue(screen.contains("textures/gui/Repeat.png"));
        assertTrue(screen.contains("textures/gui/Pause.png"));
        assertTrue(screen.contains("CONTROL_ICON_TEXTURE_SIZE = 128"));
        assertTrue(screen.contains("class ControlButton extends GuiButton"));
        assertTrue(screen.contains("func_152125_a"));
        assertTrue(screen.contains("GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F)"));
        assertFalse(screen.contains("0xFFAAA7FF"));
        assertTrue(screen.contains("QUEUE_BUTTON_WIDTH"));
        assertTrue(screen.contains("QUEUE_BUTTON_COLUMN_WIDTH"));
        assertTrue(screen.contains("queueButtonTextTop"));
        assertTrue(screen.contains("isInQueue"));
        assertTrue(screen.contains("sendRemove(result.videoId)"));
        assertTrue(screen.contains("drawActiveTabBorder"));
        assertTrue(screen.contains("SEARCH_TAB_X"));
        assertTrue(screen.contains("PLAYLIST_TAB_X"));
    }

    private static String readSource(String path) throws IOException {
        File file = new File(path);
        StringBuilder source = new StringBuilder();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                source.append(line)
                    .append('\n');
            }
        } finally {
            reader.close();
        }
        return source.toString();
    }

    private static List<HorizonRadioScreen.SearchResult> singleResult() {
        List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
        results.add(new HorizonRadioScreen.SearchResult("video", "Song", "Channel", "2:00", ""));
        return results;
    }

    private static List<HorizonRadioScreen.RadioStationResult> singleRadioStation() {
        List<HorizonRadioScreen.RadioStationResult> results = new ArrayList<HorizonRadioScreen.RadioStationResult>();
        results.add(new HorizonRadioScreen.RadioStationResult("radio-uuid", "Station"));
        return results;
    }

    private static List<HorizonRadioScreen.RadioStationResult> radioStations(int count) {
        List<HorizonRadioScreen.RadioStationResult> results = new ArrayList<HorizonRadioScreen.RadioStationResult>();
        for (int index = 0; index < count; index++) {
            results.add(new HorizonRadioScreen.RadioStationResult("radio-uuid-" + index, "Station " + index));
        }
        return results;
    }

    private static TestScreen resultScreen() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.updateChartResults(singleResult());
        return screen;
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private static int screenConstant(String name) {
        try {
            java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Missing screen layout constant " + name, exception);
        }
    }

    private static boolean invokeShouldDrawPlaybackProgress(boolean radioActive, boolean pausedRadio) {
        try {
            java.lang.reflect.Method method = HorizonRadioScreen.class
                .getDeclaredMethod("shouldDrawPlaybackProgress", boolean.class, boolean.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(null, radioActive, pausedRadio);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Playback progress policy was not available", exception);
        }
    }

    private static String invokeChartHeaderLabel(boolean hasRegion, String regionDisplayName) {
        try {
            java.lang.reflect.Method method = HorizonRadioScreen.class
                .getDeclaredMethod("chartHeaderLabel", boolean.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, hasRegion, regionDisplayName);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Chart header label policy was not available", exception);
        }
    }

    private static String invokeRadioNowPlayingDisplayLabel(String stationName, boolean showOnAir) {
        try {
            java.lang.reflect.Method method = HorizonRadioScreen.class
                .getDeclaredMethod("radioNowPlayingDisplayLabel", String.class, boolean.class);
            method.setAccessible(true);
            return (String) method.invoke(null, stationName, showOnAir);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Radio now-playing label policy was not available", exception);
        }
    }

    private static final class TestScreen extends HorizonRadioScreen {

        private void selectSearchTab() {
            actionPerformed(new GuiButton(9, 0, 0, "Search"));
        }

        private void selectPlaylistTab() {
            actionPerformed(new GuiButton(2, 0, 0, "Playlist"));
        }

        private void selectRadioTab() {
            actionPerformed(new GuiButton(11, 0, 0, "Radio"));
        }

        private void setScreenSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private void click(int mouseX, int mouseY) {
            mouseClicked(mouseX, mouseY, 0);
        }

        private void moveHeldMouse(int mouseX, int mouseY) {
            mouseClickMove(mouseX, mouseY, 0, 1L);
        }

        private void release(int mouseX, int mouseY) {
            mouseMovedOrUp(mouseX, mouseY, 0);
        }

        private void initialize() {
            initGui();
        }

        private void setSearchText(String value) {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("searchField");
                field.setAccessible(true);
                ((GuiTextField) field.get(this)).setText(value);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Search field was not initialized", exception);
            }
        }

        private List<SearchResult> chartResultsSnapshot() {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("chartResults");
                field.setAccessible(true);
                return new ArrayList<SearchResult>((List<SearchResult>) field.get(this));
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Chart results were not available", exception);
            }
        }

        private String chartSearchMessage() {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("chartSearchMessage");
                field.setAccessible(true);
                return (String) field.get(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Chart search message was not available", exception);
            }
        }

        private void invokeSearchAction() {
            actionPerformed(new GuiButton(0, 0, 0, "Search"));
        }

        private void invokePlaybackAction() {
            actionPerformed(new GuiButton(6, 0, 0, "Playback"));
        }

        private void invokeRefreshAction() {
            actionPerformed(refreshButton());
        }

        private GuiButton controlButton(int id) {
            for (Object button : buttonList) {
                GuiButton guiButton = (GuiButton) button;
                if (guiButton.id == id) {
                    return guiButton;
                }
            }
            throw new AssertionError("Control button was not initialized: " + id);
        }

        private GuiButton refreshButton() {
            for (Object button : buttonList) {
                GuiButton guiButton = (GuiButton) button;
                if (guiButton.id == 10) {
                    return guiButton;
                }
            }
            throw new AssertionError("Refresh button was not initialized");
        }

        private GuiButton searchButton() {
            for (Object button : buttonList) {
                GuiButton guiButton = (GuiButton) button;
                if (guiButton.id == 0) {
                    return guiButton;
                }
            }
            throw new AssertionError("Search button was not initialized");
        }

        private GuiTextField searchField() {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("searchField");
                field.setAccessible(true);
                return (GuiTextField) field.get(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Search field was not initialized", exception);
            }
        }

        private int searchButtonBorderColor() {
            try {
                java.lang.reflect.Field field = searchButton().getClass()
                    .getDeclaredField("borderColor");
                field.setAccessible(true);
                return field.getInt(searchButton());
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Search button border color was not initialized", exception);
            }
        }
    }

    private static final class RecordingTransport implements HorizonRadioClient.ClientTransport {

        private String searchQuery;
        private boolean chartsRequest;
        private boolean forceChartsRequest;
        private int chartRequestCount;
        private int forceChartsRequestCount;
        private String chartRegionCode;
        private String importPlaylistUrl;
        private String importVideoUrl;
        private String addRequest;
        private String playNowRequest;
        private boolean addChartsRequest;
        private String removedVideoId;
        private boolean clearPlaylist;
        private String readyVideoId;
        private String reorderRequest;
        private float seekProgress;
        private boolean togglePlayback;
        private boolean skipTrack;
        private boolean previousTrack;
        private boolean toggleLoop;
        private boolean toggleShuffle;
        private String radioSearchQuery;
        private String selectedRadioUuid;
        private boolean stopRadio;

        @Override
        public void sendAdd(String videoId, String title, String duration) {
            addRequest = videoId + "|" + title + "|" + duration;
        }

        @Override
        public void sendAdd(String videoId, long durationMs) {
            addRequest = videoId + "|" + durationMs;
        }

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {
            playNowRequest = videoId + "|" + title + "|" + duration;
        }

        @Override
        public void sendPlayNow(String videoId, long durationMs) {
            playNowRequest = videoId + "|" + durationMs;
        }

        @Override
        public void sendAddChartsToPlaylist(List<HorizonRadioScreen.SearchResult> results) {
            addChartsRequest = true;
        }

        @Override
        public void sendRemove(String videoId) {
            removedVideoId = videoId;
        }

        @Override
        public void sendClearPlaylist() {
            clearPlaylist = true;
        }

        @Override
        public void sendReorder(int fromIndex, int targetIndex) {
            reorderRequest = fromIndex + "|" + targetIndex;
        }

        @Override
        public void sendSeek(float progress) {
            seekProgress = progress;
        }

        @Override
        public void sendTogglePlayback() {
            togglePlayback = true;
        }

        @Override
        public void sendSkipTrack() {
            skipTrack = true;
        }

        @Override
        public void sendPreviousTrack() {
            previousTrack = true;
        }

        @Override
        public void sendToggleLoop() {
            toggleLoop = true;
        }

        @Override
        public void sendToggleShuffle() {
            toggleShuffle = true;
        }

        @Override
        public void sendSelectRadio(String stationUuid) {
            selectedRadioUuid = stationUuid;
        }

        @Override
        public void sendStopRadio() {
            stopRadio = true;
        }
    }
}
