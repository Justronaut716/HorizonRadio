package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import com.horizonradio.client.media.ClientMediaService;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.network.packets.TrackSyncPacket;

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
        HorizonRadioClient.setClientMediaService(null);
        HorizonRadioClient.loadClientConfig(null);
        HorizonRadioClient.setVolume(1.0f);
        HorizonRadioClient.setTransport(new HorizonRadioClient.NoopClientTransport());
    }

    @Test
    public void radioQueueNotificationUsesKnownStationNameBeforeQueueMetadataArrives() {
        String id = "12345678-abcd-1234-abcd-123456789abc";
        HorizonRadioClient.updateRadioSearchResults(
            Collections.singletonList(new RadioStation(id, "Example FM", "https://example.com/stream", true, true)));
        HorizonRadioClient.updatePlaylist(
            Collections
                .singletonList(new HorizonRadioScreen.PlaylistEntry(MediaSourceType.RADIO, id, "Alice", null, null)));
        assertEquals("Example FM", HorizonRadioClient.notificationSnapshot().queue.get("RADIO:" + id));
        HorizonRadioClient.updateRadioSearchResults(Collections.<RadioStation>emptyList());
        assertEquals("Radio station", HorizonRadioClient.notificationSnapshot().queue.get("RADIO:" + id));
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
    public void failedPlaylistImportShowsRateLimitInTheSharedStatusLine() {
        CompletableFuture<String> response = new CompletableFuture<String>();
        HorizonRadioClient.setClientMediaService(new ClientMediaService(new PendingPlaylistImportProvider(response)));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();
        HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLtest");
        response.completeExceptionally(new java.io.IOException("HTTP 429"));
        assertEquals("YouTube rate limit - please try again later.", HorizonRadioClient.mediaStatusMessage());
        screen.selectSearchTab();
        assertEquals("YouTube rate limit - please try again later.", HorizonRadioClient.mediaStatusMessage());
        HorizonRadioClient.dismissMediaError();
        assertEquals("", HorizonRadioClient.mediaStatusMessage());
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
    public void playlistDiscoveryResultsCachePopulateANewScreenWithoutChangingQueue() {
        HorizonRadioClient.setClientMediaService(
            new ClientMediaService(
                new ImmediatePlaylistImportProvider(
                    "{\"title\":\"My YouTube Playlist\",\"entries\":[{\"id\":\"cached-playlist-song\",\"title\":\"Cached Playlist Song\",\"duration\":60}]}")));
        TestScreen original = new TestScreen();
        TestScreen reopened = new TestScreen();
        original.setScreenSize(300, 285);
        reopened.setScreenSize(300, 285);
        try {
            original.initialize();
            original.selectPlaylistDiscoveryTab();

            HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLcache");

            reopened.initialize();
            reopened.selectPlaylistDiscoveryTab();

            assertEquals(Collections.singletonList("cached-playlist-song"), reopened.playlistResultVideoIds());
            assertEquals("My YouTube Playlist", original.getPlaylistTitle());
            assertEquals("My YouTube Playlist", reopened.getPlaylistTitle());
            assertTrue(
                reopened.getPlaylistSnapshot()
                    .isEmpty());
            assertFalse(reopened.isPlaylistTab());
        } finally {
            HorizonRadioScreen.clearActiveScreen(original);
            HorizonRadioScreen.clearActiveScreen(reopened);
        }
    }

    @Test
    public void closingAnActivePlaylistImportScreenInvalidatesPlaylistImportsOnce() {
        CompletableFuture<String> pendingImport = new CompletableFuture<String>();
        HorizonRadioClient
            .setClientMediaService(new ClientMediaService(new PendingPlaylistImportProvider(pendingImport)));
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();

        HorizonRadioClient.sendPlaylistImport("https://www.youtube.com/playlist?list=PLclose");
        long generationAfterRequest = playlistImportGeneration();

        screen.onGuiClosed();
        pendingImport
            .complete("{\"entries\":[{\"id\":\"closed-after-gui\",\"title\":\"Closed After GUI\",\"duration\":60}]}");

        assertEquals(generationAfterRequest + 1L, playlistImportGeneration());
        assertTrue(
            HorizonRadioClient.getCachedPlaylistResults()
                .isEmpty());
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
            screen.selectChartsTab();

            assertEquals(0, transport.chartRequestCount);
            assertEquals("", screen.getChartRegionCode());
            assertFalse(screen.refreshButton().enabled);
            assertFalse(screen.refreshButton().visible);
            assertFalse(screen.controlButton(18).visible);

            screen.updateChartResults(singleResult(), "DE");

            assertTrue(screen.refreshButton().visible);
            assertTrue(screen.controlButton(18).visible);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void playlistDiscoveryControlsStayHiddenUntilPlaylistResultsArrive() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();
            screen.selectChartsTab();
            screen.updateChartResults(singleResult(), "DE");
            assertTrue(screen.controlButton(18).visible);

            screen.selectPlaylistDiscoveryTab();
            assertFalse(screen.controlButton(18).visible);

            screen.updatePlaylistResults(singleResult());
            assertTrue(screen.controlButton(18).visible);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void emptyChartsHideTheGenericHeaderButKeepCountryHeaders() {
        assertEquals("", invokeChartHeaderLabel(false, ""));
        assertEquals("Top 50 Charts \u00B7 Germany", invokeChartHeaderLabel(true, "Germany"));
    }

    @Test
    public void topButtonsMatchTheHeightsOfTheirNeighboringControls() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();

            assertEquals(screenConstant("TAB_BUTTON_HEIGHT"), screen.refreshButton().height);
            assertEquals(screen.searchField().height + 2, screen.searchButton().height);
            assertEquals(screen.searchField().yPosition - 1, screen.searchButton().yPosition);
            assertEquals(screenConstant("SEARCH_BUTTON_WIDTH"), screen.searchButton().width);
            assertTrue(screen.searchField().width > 180);
            assertEquals(
                screenConstant("SEARCH_SIDE_MARGIN"),
                screen.searchField().xPosition - screen.prototypePanelLeft());
            assertEquals(
                screen.searchField().xPosition + screen.searchField().width + screenConstant("SEARCH_CONTROL_GAP"),
                screen.searchButton().xPosition);
            assertEquals(
                screen.searchButton().xPosition + screen.searchButton().width,
                screen.prototypePanelLeft() + screenConstant("SEARCH_FIELD_X_OFFSET")
                    + screenConstant("SEARCH_FIELD_WIDTH")
                    + screenConstant("SEARCH_CONTROL_GAP")
                    + screenConstant("SEARCH_BUTTON_WIDTH"));
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
    public void chartActionsShareTheSongQueueButtonColumn() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();

            GuiButton bulkAdd = screen.controlButton(18);
            GuiButton refresh = screen.refreshButton();
            int songButtonLeft = screen.resultQueueButtonCenterX() - screenConstant("QUEUE_BUTTON_WIDTH") / 2;

            assertEquals(songButtonLeft, bulkAdd.xPosition);
            assertEquals(bulkAdd.xPosition - bulkAdd.width - screenConstant("SEARCH_CONTROL_GAP"), refresh.xPosition);
            assertEquals(bulkAdd.yPosition, refresh.yPosition);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void queueClearPolicyHidesDeleteAllUntilQueueHasEntries() {
        assertFalse(invokeShouldShowQueueClearButton(0, false));
        assertTrue(invokeShouldShowQueueClearButton(1, false));
        assertTrue(invokeShouldShowQueueClearButton(0, true));
    }

    @Test
    public void volumeSliderUsesTheSharedFooterAlignmentLine() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();

            assertEquals(
                screen.prototypePanelTop() + screenConstant("VOLUME_TOP_OFFSET") + 2,
                screen.controlButton(3).yPosition);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void modeTabsReserveCompactNativeTextWithoutChangingPrototypeGeometry() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();

            assertEquals(0.80F, HorizonRadioScreen.MODE_BUTTON_TEXT_SCALE, 0.0001F);
            assertEquals(34, screen.controlButton(9).width);
            assertEquals(34, screen.controlButton(1).width);
            assertEquals(34, screen.controlButton(13).width);
            assertTrue(screen.controlButton(9).xPosition < screen.controlButton(1).xPosition);
            assertTrue(screen.controlButton(1).xPosition < screen.controlButton(13).xPosition);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void playlistDiscoveryTabUsesTheSingularLabel() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();

            assertEquals("Playlist", screen.buttonLabel(13));
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void aNewScreenStartsOnSearchTab() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        try {
            screen.initialize();

            assertEquals(screenConstant("SEARCH_TAB"), screen.currentTab());
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void favoritedSearchResultsUseAHeartGlyph() {
        assertEquals("\u2661", invokeSearchResultGlyph(true));
        assertEquals("\u266B", invokeSearchResultGlyph(false));
    }

    @Test
    public void searchResultsUseSlimTextGlyphsAndQueueStatusUsesMinus() throws IOException {
        assertEquals("-", HorizonRadioScreen.chartQueueButtonLabel(true, false));
        assertEquals("\u2661", invokeSearchResultGlyph(true));
        assertEquals("\u266B", invokeSearchResultGlyph(false));
        String screen = readSource("src/main/java/com/horizonradio/client/HorizonRadioScreen.java");
        assertTrue(screen.contains("textures/gui/Favorite.png"));
        assertTrue(screen.contains("drawResultGlyph"));
        assertTrue(screen.contains("drawUiCenteredString(searchResultGlyph"));
    }

    @Test
    public void volumeTrackReportsTheFilledPrototypePortion() {
        assertEquals(0, HorizonRadioVolumeSlider.volumeTrackFillWidth(100, 0.0F));
        assertEquals(48, HorizonRadioVolumeSlider.volumeTrackFillWidth(100, 0.5F));
        assertEquals(96, HorizonRadioVolumeSlider.volumeTrackFillWidth(100, 1.0F));
    }

    @Test
    public void songSearchProgressBarIsVisibleOnlyWhileLoading() {
        assertTrue(HorizonRadioScreen.shouldDrawSearchProgressBar(true));
        assertFalse(HorizonRadioScreen.shouldDrawSearchProgressBar(false));
    }

    @Test
    public void songResultsMoveUpWhenLoadingBarIsHidden() {
        int expectedTop = screenConstant("SEARCH_CONTROL_Y_OFFSET") + screenConstant("SEARCH_CONTROL_HEIGHT")
            + screenConstant("SEARCH_RESULT_VERTICAL_MARGIN");

        assertEquals(expectedTop, HorizonRadioScreen.searchListTopOffset(true));
        assertEquals(expectedTop, HorizonRadioScreen.searchListTopOffset(false));
    }

    @Test
    public void searchResultsUseTheFreedTitleSpaceForASeventhRow() {
        int listBottom = HorizonRadioScreen.searchListTopOffset(false)
            + screenConstant("SEARCH_MAX_VISIBLE_ROWS") * screenConstant("ROW_HEIGHT")
            - 2;

        assertEquals(7, screenConstant("SEARCH_MAX_VISIBLE_ROWS"));
        assertTrue("Search rows overlap the body footer", listBottom <= screenConstant("BODY_BOTTOM_OFFSET"));
    }

    @Test
    public void songResultsHaveBalancedVerticalMargins() {
        int searchControlBottom = screenConstant("SEARCH_CONTROL_Y_OFFSET") + screenConstant("SEARCH_CONTROL_HEIGHT");
        int listTop = HorizonRadioScreen.searchListTopOffset(false);
        int listBottom = listTop + screenConstant("SEARCH_MAX_VISIBLE_ROWS") * screenConstant("ROW_HEIGHT");
        int topMargin = listTop - searchControlBottom;
        int bottomMargin = screenConstant("BODY_BOTTOM_OFFSET") - listBottom;

        assertEquals(topMargin, bottomMargin);
        assertEquals(6, topMargin);
    }

    @Test
    public void resultScrollbarStartsAtTheResultBoxEdge() {
        int contentLeft = screenConstant("CONTENT_LEFT_INSET");
        int contentRight = contentLeft + screenConstant("CONTENT_WIDTH") - 7;
        int scrollbarLeft = contentRight - screenConstant("RESULT_SCROLLBAR_LEFT_OFFSET");

        assertEquals(contentRight, scrollbarLeft);
    }

    @Test
    public void seventhSearchResultRemainsClickable() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectSearchTab();
        screen.setSearchText("songs");
        screen.updateSearchResults(searchResults(7));

        screen.click(screen.resultRowCenterX(), screen.searchRowCenterY(6));

        assertEquals("video-6|120000", transport.playNowRequest);
    }

    @Test
    public void timeBarUsesThePrototypeTrackAndClampedFill() {
        assertEquals(40, screenConstant("TIME_BAR_SIDE_SPACE"));
        assertEquals(13, screenConstant("TIME_BAR_LABEL_LEFT_OFFSET"));
        assertEquals(5, screenConstant("TIME_BAR_HEIGHT"));
        assertEquals(0xFF3C3C3C, screenConstant("TIME_BAR_TRACK_COLOR"));
        assertEquals(0xFF79D38A, screenConstant("TIME_BAR_PROGRESS_COLOR"));
        assertEquals(0, HorizonRadioScreen.timeBarFillWidth(100, -0.2F));
        assertEquals(50, HorizonRadioScreen.timeBarFillWidth(100, 0.5F));
        assertEquals(100, HorizonRadioScreen.timeBarFillWidth(100, 1.2F));
    }

    @Test
    public void allUiTextUsesTheModeButtonScale() {
        assertEquals(0.80F, HorizonRadioScreen.uiTextScale(), 0.0001F);
        assertEquals(80, HorizonRadioScreen.uiTextWidth(100));
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
        assertEquals(1500L, HorizonRadioScreen.progressEstimateMillis(3));
        assertEquals(400L, HorizonRadioScreen.progressEstimateMillis(4));
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
    public void radioListKeepsItsPositionWhileLoading() {
        assertEquals(HorizonRadioScreen.radioListTopOffset(false), HorizonRadioScreen.radioListTopOffset(true));
        int progressTop = HorizonRadioScreen.searchProgressTopOffset(true);
        assertEquals(
            screenConstant("RADIO_SEARCH_CONTROL_Y_OFFSET") - screenConstant("SEARCH_CONTROL_Y_OFFSET"),
            progressTop - HorizonRadioScreen.searchProgressTopOffset(false));
        assertTrue(
            progressTop + screenConstant("SEARCH_PROGRESS_HEIGHT") <= HorizonRadioScreen.radioListTopOffset(true));
        assertEquals(
            screenConstant("RADIO_LIST_TOP_WITH_PROGRESS_OFFSET"),
            HorizonRadioScreen.radioListTopOffset(true));
        assertEquals(screenConstant("RADIO_LIST_TOP_OFFSET"), HorizonRadioScreen.radioListTopOffset(false));
        assertEquals(screenConstant("RADIO_SEARCH_CONTROL_Y_OFFSET"), HorizonRadioScreen.radioSearchControlYOffset());
        assertFalse(HorizonRadioScreen.shouldShowSongModeButtons(4));
        assertTrue(HorizonRadioScreen.shouldShowSongModeButtons(0));
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
    public void nowPlayingPanelUsesOneInnerMarginOnAllSides() throws IOException {
        String screen = readSource("src/main/java/com/horizonradio/client/HorizonRadioScreen.java");
        assertTrue(screen.contains("NOW_PLAYING_CONTENT_MARGIN = 5"));
        assertTrue(screen.contains("nowPlayingContentLeft"));
        assertTrue(screen.contains("nowPlayingContentRight"));
        assertTrue(screen.contains("nowPlayingContentTop"));
        assertTrue(screen.contains("nowPlayingContentBottom"));
    }

    @Test
    public void nowPlayingPrefixesAreIncludedInTheAvailableTextWidth() throws IOException {
        String screen = readSource("src/main/java/com/horizonradio/client/HorizonRadioScreen.java");
        assertTrue(screen.contains("truncateUiWithPrefix(\"\\u266A \", nowPlaying, contentWidth)"));
        assertTrue(screen.contains("truncateUiWithPrefix(\"Radio: \", radioStatus(), contentWidth)"));
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
    public void playlistPendingStateIsIndependentFromPendingChartAdds() {
        TestScreen screen = new TestScreen();
        HorizonRadioScreen.SearchResult shared = new HorizonRadioScreen.SearchResult(
            "shared",
            "Shared",
            "",
            "2:00",
            "");
        HorizonRadioScreen.SearchResult playlistOnly = new HorizonRadioScreen.SearchResult(
            "playlist-only",
            "Playlist only",
            "",
            "3:00",
            "");

        assertEquals(Collections.singletonList(shared), screen.beginChartAdd(Collections.singletonList(shared)));
        assertEquals(Arrays.asList(shared, playlistOnly), screen.beginPlaylistAdd(Arrays.asList(shared, playlistOnly)));

        assertTrue(screen.isChartAddPending("shared"));
        assertTrue(screen.isPlaylistAddPending("shared"));
        assertTrue(screen.isPlaylistAddPending("playlist-only"));

        screen.completePlaylistAdds(Collections.singletonList("shared"));

        assertTrue(screen.isChartAddPending("shared"));
        assertFalse(screen.isPlaylistAddPending("shared"));
        assertTrue(screen.isPlaylistAddPending("playlist-only"));

        screen.completeChartAdds(Collections.singletonList("shared"));

        assertFalse(screen.isChartAddPending("shared"));
        assertTrue(screen.isPlaylistAddPending("playlist-only"));
    }

    @Test
    public void queueAndPlaylistsAreDifferentTabsAndFields() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();

        screen.selectPlaylistDiscoveryTab();

        assertTrue(screen.isPlaylistDiscoveryTab());
        assertFalse(screen.isPlaylistTab());
        assertNotSame(screen.searchField(), screen.playlistUrlField());

        screen.selectPlaylistTab();

        assertTrue(screen.isPlaylistTab());
        assertFalse(screen.isPlaylistDiscoveryTab());
    }

    @Test
    public void playlistResultsIgnoreOtherTabsSearchTextAndNeverFallBackToCharts() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.updateChartResults(singleResult(), "DE");
        screen.setSearchText("unrelated song query");
        screen.selectPlaylistDiscoveryTab();
        screen.click(screen.playlistFirstRowQueueButtonCenterX(), screen.playlistFirstRowQueueButtonCenterY());
        assertNull(transport.addRequest);
        assertTrue(transport.chartSelections.isEmpty());

        screen.updatePlaylistResults(
            Collections.singletonList(
                new HorizonRadioScreen.SearchResult("playlist-song", "Playlist Song", "Artist", "2:00", "")),
            "My Playlist");
        screen.click(screen.playlistFirstRowQueueButtonCenterX(), screen.playlistFirstRowQueueButtonCenterY());
        assertEquals(Collections.singletonList("playlist-song|120000"), transport.chartSelections);
        assertEquals("My Playlist", screen.getPlaylistTitle());
    }

    @Test
    public void playlistRowQueueButtonUsesQueueTransportWithoutAddingToQueueLocally() {
        TestScreen screen = initializedPlaylistScreen(
            Collections
                .singletonList(new HorizonRadioScreen.SearchResult("playlist-song", "Playlist Song", "", "2:00", "")));

        screen.click(screen.resultQueueButtonCenterX(), screen.resultRowCenterY());

        assertEquals(Collections.singletonList("playlist-song|120000"), transport.chartSelections);
        assertEquals(
            "playlist-song",
            screen.getPlaylistResultsSnapshot()
                .get(0).videoId);
        assertTrue(
            screen.getPlaylistSnapshot()
                .isEmpty());
    }

    @Test
    public void playlistFirstRowQueueButtonCenterAddsOnlyTheFirstResult() {
        TestScreen screen = initializedPlaylistScreen(
            Arrays.asList(
                new HorizonRadioScreen.SearchResult("playlist-one", "Playlist One", "", "1:00", ""),
                new HorizonRadioScreen.SearchResult("playlist-two", "Playlist Two", "", "2:00", "")));

        screen.click(screen.playlistFirstRowQueueButtonCenterX(), screen.playlistFirstRowQueueButtonCenterY());

        assertEquals(Collections.singletonList("playlist-one|60000"), transport.chartSelections);
    }

    @Test
    public void playlistRowsCannotBeActedOnWhileTheirRevealIsPending() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();
        screen.beginPlaylistLoading();
        screen.updatePlaylistResults(
            Arrays.asList(
                new HorizonRadioScreen.SearchResult("playlist-one", "Playlist One", "", "1:00", ""),
                new HorizonRadioScreen.SearchResult("playlist-two", "Playlist Two", "", "2:00", "")));

        assertTrue(screen.hasPlaylistResultsRevealPending());
        screen.click(screen.playlistFirstRowQueueButtonCenterX(), screen.playlistFirstRowQueueButtonCenterY());

        assertTrue(transport.chartSelections.isEmpty());
        assertNull(transport.playNowRequest);
    }

    @Test
    public void playlistImportActionsAreIgnoredWhileLoadingForButtonAndEnter() {
        PendingPlaylistImportProvider provider = new PendingPlaylistImportProvider(new CompletableFuture<String>());
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();
        screen.setPlaylistUrlText("https://www.youtube.com/playlist?list=PLfirst");

        screen.invokeSearchAction();
        screen.setPlaylistUrlText("https://www.youtube.com/playlist?list=PLsecond");
        screen.invokeSearchAction();
        screen.invokePlaylistEnter();

        assertEquals(1, provider.playlistImportCallCount);
    }

    @Test
    public void playlistRowClickPlaysNowAndSwitchesToQueue() {
        TestScreen screen = initializedPlaylistScreen(
            Collections
                .singletonList(new HorizonRadioScreen.SearchResult("playlist-song", "Playlist Song", "", "2:00", "")));

        screen.click(screen.resultRowCenterX(), screen.resultRowCenterY());

        assertEquals("playlist-song|120000", transport.playNowRequest);
        assertFalse(screen.isPlaylistTab());
    }

    @Test
    public void playlistBulkButtonUsesCompactQueueTransportInSourceOrder() {
        TestScreen screen = initializedPlaylistScreen(
            Arrays.asList(
                new HorizonRadioScreen.SearchResult("playlist-one", "Playlist One", "", "1:00", ""),
                new HorizonRadioScreen.SearchResult("playlist-two", "Playlist Two", "", "2:00", "")));

        screen.click(screen.resultQueueButtonCenterX(), screen.bulkButtonCenterY());

        assertEquals(Arrays.asList("playlist-one|60000", "playlist-two|120000"), transport.chartSelections);
        assertTrue(
            screen.getPlaylistSnapshot()
                .isEmpty());
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
        assertTrue(tabBottom < titleTop);
        assertTrue(titleTop + titleHeight < listTop);
    }

    @Test
    public void chartAndRadioLabelsUseBalancedSearchMargins() {
        int labelTop = screenConstant("CONTENT_LABEL_Y_OFFSET");
        int searchBottom = screenConstant("SEARCH_CONTROL_Y_OFFSET") + screenConstant("SEARCH_CONTROL_HEIGHT");
        int resultListTop = screenConstant("CONTENT_LIST_TOP_OFFSET");
        int labelHeight = 8;

        assertEquals(screenConstant("SECTION_TOP_OFFSET"), labelTop);
        assertTrue(searchBottom < labelTop);
        assertTrue(labelTop + labelHeight < resultListTop);
    }

    @Test
    public void chartsSearchUsesCanonicalRegionWithoutDiscoveryTransport() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();
            screen.selectChartsTab();
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
            screen.selectChartsTab();
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
            screen.selectChartsTab();
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
            screen.selectChartsTab();
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
            screen.selectChartsTab();

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
            assertFalse(screen.refreshButton().visible);
            assertFalse(screen.controlButton(18).visible);
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
        InputStream logoStream = GuiLayoutTest.class
            .getResourceAsStream("/assets/horizonradio/textures/gui/HorizonRadioLogo.png");
        assertNotNull("Missing HorizonRadio logo", logoStream);
        try {
            BufferedImage logo = ImageIO.read(logoStream);
            assertNotNull("Invalid HorizonRadio logo", logo);
            assertEquals(1431, logo.getWidth());
            assertEquals(307, logo.getHeight());
        } finally {
            logoStream.close();
        }
    }

    @Test
    public void searchQueueButtonAddsAndRemovesWithoutChangingTab() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectChartsTab();
        HorizonRadioScreen.SearchResult result = new HorizonRadioScreen.SearchResult(
            "video",
            "Song",
            "Channel",
            "2:00",
            "");
        List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
        results.add(result);
        screen.updateChartResults(results);

        screen.click(screen.resultQueueButtonCenterX(), screen.resultRowCenterY());

        assertTrue(transport.addChartsRequest);
        assertNull(transport.addRequest);
        assertNull(transport.removedVideoId);
        assertFalse(screen.isPlaylistTab());

        List<HorizonRadioScreen.PlaylistEntry> playlist = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        playlist.add(new HorizonRadioScreen.PlaylistEntry("video", "Song", "2:00", "Alice"));
        screen.updatePlaylist(playlist);
        transport.addRequest = null;

        screen.click(screen.resultQueueButtonCenterX(), screen.resultRowCenterY());

        assertNull(transport.addRequest);
        assertEquals("video", transport.removedVideoId);
        assertFalse(screen.isPlaylistTab());
    }

    @Test
    public void directChartClickPlaysNowAndSwitchesToPlaylist() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = resultScreen();

        screen.click(screen.resultRowCenterX(), screen.resultRowCenterY());

        assertEquals("video|120000", transport.playNowRequest);
        assertFalse(screen.isPlaylistTab());
        assertNull(transport.addRequest);
        assertFalse(transport.addChartsRequest);
        assertFalse(transport.stopRadio);
    }

    @Test
    public void directSearchClickPlaysNowAndSwitchesToPlaylist() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectSearchTab();
        screen.setSearchText("Song");
        screen.updateSearchResults(singleResult());

        screen.click(screen.resultRowCenterX(), screen.resultRowCenterY());

        assertEquals("video|120000", transport.playNowRequest);
        assertFalse(screen.isPlaylistTab());
        assertFalse(transport.stopRadio);
    }

    @Test
    public void resultGlyphTogglesFavoriteWithoutPlayingOrQueueing() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.setSearchText("Song");
        screen.updateSearchResults(singleResult());
        int x = screen.prototypePanelLeft() + screenConstant("CONTENT_LEFT_INSET")
            + screenConstant("RESULT_GLYPH_LEFT_INSET")
            + screenConstant("RESULT_GLYPH_AREA_WIDTH") / 2;
        int y = screen.searchRowCenterY(0);

        screen.click(x, y);
        screen.release(x, y);
        assertTrue(HorizonRadioClient.isSongFavorite("video"));
        assertEquals(
            "Song",
            HorizonRadioClient.getFavoriteSongs()
                .get(0)
                .getTitle());
        assertNull(transport.playNowRequest);
        assertNull(transport.addRequest);

        screen.click(x, y);
        screen.release(x, y);
        assertFalse(HorizonRadioClient.isSongFavorite("video"));
        assertNull(transport.playNowRequest);
        assertNull(transport.removedVideoId);
    }

    @Test
    public void heartClickRemovesSongFromEmptyQueryFavorites() {
        HorizonRadioClient.toggleSongFavorite(singleResult().get(0));
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        int x = screen.prototypePanelLeft() + screenConstant("CONTENT_LEFT_INSET")
            + screenConstant("RESULT_GLYPH_LEFT_INSET")
            + screenConstant("RESULT_GLYPH_AREA_WIDTH") / 2;

        screen.click(x, screen.searchRowCenterY(0));

        assertTrue(
            screen.searchDisplayVideoIds()
                .isEmpty());
        assertFalse(HorizonRadioClient.isSongFavorite("video"));
        assertNull(transport.playNowRequest);
    }

    @Test
    public void activeRadioAlreadyInQueueDoesNotAddASyntheticRowOrShiftSongClicks() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.updateRadioPresentation(ClientRadioPresentation.active(5L, "radio-id", "Station", "LIVE"));
        assertTrue(screen.hasStandaloneRadioRow());
        screen.updatePlaylist(
            Arrays.asList(
                new HorizonRadioScreen.PlaylistEntry(
                    com.horizonradio.core.model.MediaSourceType.RADIO,
                    "radio-id",
                    "Alice",
                    null,
                    null),
                new HorizonRadioScreen.PlaylistEntry("next", "Next Song", "2:00", "Bob")));
        assertFalse(screen.hasStandaloneRadioRow());
        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        assertEquals("next|120000", transport.playNowRequest);
    }

    @Test
    public void queueRowClickSendsPlayNowOnlyOnRelease() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("video", "Song", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("other", "Other", "3:00", "Bob"));
        screen.updatePlaylist(entries);

        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(0));
        assertNull(transport.playNowRequest);

        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(0));

        assertEquals("video|120000", transport.playNowRequest);
        assertNull(transport.removedVideoId);
        assertNull(transport.reorderRequest);
        assertFalse(transport.stopRadio);
    }

    @Test
    public void queueRowDragStillReordersInsteadOfPlaying() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("first", "First", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("second", "Second", "3:00", "Bob"));
        screen.updatePlaylist(entries);

        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(0));
        screen.moveHeldMouse(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(1));

        assertEquals("0|1", transport.reorderRequest);
        assertNull(transport.playNowRequest);
    }

    @Test
    public void currentQueueRowCanBeClickedButRemainsNonDraggable() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("current", "Current", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("next", "Next", "3:00", "Bob"));
        screen.updatePlaylist(entries);
        screen.updateNowPlaying("Current", 0.5f);

        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(0));
        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(0));

        assertEquals("current|120000", transport.playNowRequest);
        assertNull(transport.reorderRequest);

        transport.playNowRequest = null;
        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(0));
        screen.moveHeldMouse(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(1));

        assertNull(transport.playNowRequest);
        assertNull(transport.reorderRequest);
    }

    @Test
    public void droppingQueuedSongOnFirstPositionStartsItImmediately() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.selectPlaylistTab();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("current", "Current", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("next", "Next", "3:00", "Bob"));
        screen.updatePlaylist(entries);
        screen.updateNowPlaying("Current", 0.5f);

        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        screen.moveHeldMouse(screen.queueRowCenterX(), screen.queueRowCenterY(0));
        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(0));

        assertEquals("next|180000", transport.playNowRequest);
        assertNull(transport.reorderRequest);
    }

    @Test
    public void droppingOutsideQueueCancelsTheMove() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("first", "First", "2:00", "Alice"));
        entries.add(new HorizonRadioScreen.PlaylistEntry("second", "Second", "3:00", "Bob"));
        screen.updatePlaylist(entries);
        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        screen.moveHeldMouse(0, 0);
        screen.release(0, 0);

        assertNull(transport.playNowRequest);
        assertNull(transport.reorderRequest);
    }

    @Test
    public void queuePreviewShiftsOnlyRowsBetweenSourceAndDestination() {
        int[] movingUp = { 0, 3, 1, 2, 4 };
        int[] movingDown = { 0, 2, 3, 1, 4 };
        for (int row = 0; row < movingUp.length; row++) {
            assertEquals(movingUp[row], HorizonRadioScreen.queuePreviewSourceIndex(row, 3, 1));
            assertEquals(movingDown[row], HorizonRadioScreen.queuePreviewSourceIndex(row, 1, 3));
            assertEquals(row, HorizonRadioScreen.queuePreviewSourceIndex(row, 2, 2));
        }
    }

    @Test
    public void queueDragScrollsAtTheEdgeAndUsesTheVisibleDropPosition() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        for (int index = 0; index < 10; index++) {
            entries.add(new HorizonRadioScreen.PlaylistEntry("song" + index, "Song", "2:00", "Alice"));
        }
        screen.updatePlaylist(entries);
        int x = screen.queueRowCenterX();
        int bottom = screen.queueRowCenterY(7);
        screen.click(x, screen.queueRowCenterY(1));
        screen.moveHeldMouse(x, bottom);
        screen.updateQueueDragScroll(x, bottom, 1000L);
        screen.updateQueueDragScroll(x, bottom, 1250L);
        screen.updateQueueDragScroll(x, bottom, 1500L);
        screen.release(x, bottom);

        assertEquals("1|9", transport.reorderRequest);
        assertNull(transport.playNowRequest);
    }

    @Test
    public void droppingFirstQueuedSongOnLiveRadioStartsTheSong() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio", "Station", "LIVE"));
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("next", "Next", "3:00", "Bob"));
        screen.updatePlaylist(entries);
        screen.click(screen.queueRowCenterX(), screen.queueRowCenterY(1));
        screen.moveHeldMouse(screen.queueRowCenterX(), screen.queueRowCenterY(0));
        screen.release(screen.queueRowCenterX(), screen.queueRowCenterY(0));

        assertEquals("next|180000", transport.playNowRequest);
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
    public void radioGlyphTogglesFavoriteWithoutStartingTheStation() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectRadioTab();
        screen.updateRadioResults(singleRadioStation());
        int x = screen.prototypePanelLeft() + screenConstant("CONTENT_LEFT_INSET")
            + screenConstant("RESULT_GLYPH_LEFT_INSET")
            + screenConstant("RESULT_GLYPH_AREA_WIDTH") / 2;
        screen.click(x, screen.radioRowCenterY());
        assertTrue(HorizonRadioClient.isRadioFavorite("radio-uuid"));
        assertEquals(
            "Station",
            HorizonRadioClient.getFavoriteRadios()
                .get(0)
                .getName());
        assertNull(transport.selectedRadioUuid);

        screen.click(x, screen.radioRowCenterY());
        assertFalse(HorizonRadioClient.isRadioFavorite("radio-uuid"));
        assertNull(transport.selectedRadioUuid);
    }

    @Test
    public void radioRowSelectionSendsUuidOnly() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.selectRadioTab();
        screen.updateRadioResults(singleRadioStation());

        screen.click(screen.resultRowCenterX(), screen.radioRowCenterY());

        assertEquals("radio-uuid", transport.selectedRadioUuid);
        assertNull(transport.playNowRequest);
        assertNull(transport.addRequest);
        assertNull(transport.removedVideoId);
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "radio-uuid"));
        assertEquals(
            "Station",
            HorizonRadioClient.getCachedRadioPresentation()
                .getStationName());
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
    public void activeRadioHighlightsOnlyTheFirstPlaylistRow() {
        assertTrue(HorizonRadioScreen.isPlaylistRowPlaying(0, true, true));
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
        assertEquals(160, HorizonRadioScreen.radioStationNameMaxWidth(0));
        assertEquals(160, HorizonRadioScreen.radioStationNameMaxWidth(47));
    }

    @Test
    public void radioResultsUseEightRowScrollbarForStationSelection() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.selectRadioTab();
        screen.updateRadioResults(radioStations(12));

        screen.click(
            screen.radioScrollbarX(),
            screen.prototypePanelTop() + screenConstant("RADIO_LIST_TOP_WITH_PROGRESS_OFFSET"));
        screen
            .moveHeldMouse(screen.radioScrollbarX(), screen.prototypePanelTop() + screenConstant("BODY_BOTTOM_OFFSET"));
        screen.release(screen.radioScrollbarX(), screen.prototypePanelTop() + screenConstant("BODY_BOTTOM_OFFSET"));
        screen.click(screen.resultRowCenterX(), screen.radioRowCenterY());

        assertEquals("radio-uuid-4", transport.selectedRadioUuid);
        screen.click(screen.resultRowCenterX(), screen.radioRowCenterY() + 7 * screenConstant("ROW_HEIGHT"));
        assertEquals("radio-uuid-11", transport.selectedRadioUuid);
        assertTrue(
            HorizonRadioScreen.radioListTopOffset(false) + 8 * screenConstant("ROW_HEIGHT")
                < screenConstant("BODY_BOTTOM_OFFSET"));
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
    public void queueCrossRemovesRadioInsteadOfPausingIt() {
        HorizonRadioClient.updateRadioPresentation(ClientRadioPresentation.active(1L, "radio-uuid", "Station", "LIVE"));
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.updatePlaylist(
            Collections.singletonList(
                new HorizonRadioScreen.PlaylistEntry(MediaSourceType.RADIO, "radio-uuid", "Alice", null, null)));
        int removeX = screen.prototypePanelLeft() + screenConstant("QUEUE_LEFT_INSET")
            + screenConstant("QUEUE_WIDTH")
            - screenConstant("QUEUE_BUTTON_WIDTH") / 2
            - 4;
        screen.click(removeX, screen.queueRowCenterY(0));
        assertEquals("radio-uuid", transport.removedVideoId);
        assertFalse(transport.stopRadio);
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
        assertTrue(screen.controlButton(5).enabled);
        assertTrue(screen.controlButton(6).enabled);
        assertTrue(screen.controlButton(7).enabled);
        assertFalse(screen.controlButton(8).enabled);

        screen.invokeControlAction(5);
        screen.invokeControlAction(7);

        assertTrue(transport.previousTrack);
        assertTrue(transport.skipTrack);

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
        screen.selectPlaylistTab();
        screen.updateRadioPresentation(ClientRadioPresentation.inactive(1L, "radio-uuid", "Station", "", false));

        assertFalse(screen.controlButton(4).enabled);
        assertTrue(screen.controlButton(5).enabled);
        assertTrue(screen.controlButton(6).enabled);
        assertTrue(screen.controlButton(7).enabled);
        assertFalse(screen.controlButton(8).enabled);
        assertEquals("Station", screen.getNowPlayingSnapshot());

        screen.invokeControlAction(5);
        screen.invokeControlAction(7);

        assertTrue(transport.previousTrack);
        assertTrue(transport.skipTrack);

        screen.invokePlaybackAction();

        assertEquals("radio-uuid", transport.selectedRadioUuid);
        assertFalse(transport.stopRadio);
        assertFalse(transport.togglePlayback);
    }

    @Test
    public void radioStopSyncKeepsTheStationVisibleAndResumableOnTheQueueTab() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();
            screen.selectPlaylistTab();

            HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "radio-uuid"));
            HorizonRadioClient.handleLocalRadioStarted(5L, "radio-uuid", "Station");
            HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(6L));

            assertFalse(HorizonRadioClient.isRadioActive());
            assertEquals("Station", screen.getNowPlayingSnapshot());
            assertTrue(screen.radioControlsVisible());
            assertTrue(screen.controlButton(6).enabled);
            assertTrue(screen.controlButton(5).enabled);
            assertTrue(screen.controlButton(7).enabled);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void favoriteControlIsDisabledWithoutCurrentSource() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();

        assertTrue(screen.controlButton(12).visible);
        assertFalse(screen.controlButton(12).enabled);
    }

    @Test
    public void emptySearchShowsOnlyFavoritesButChartsTabRemainsRaw() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "favorite", 0L, 0L, true));
        HorizonRadioClient.toggleCurrentFavorite();
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.updateChartResults(
            Collections.singletonList(new HorizonRadioScreen.SearchResult("chart", "Chart", "", "3:00", "")));
        screen.selectSearchTab();

        assertEquals(Collections.singletonList("favorite"), screen.searchDisplayVideoIds());
        screen.selectChartsTab();
        assertEquals(Collections.singletonList("chart"), screen.chartResultsSnapshotVideoIds());
    }

    @Test
    public void favoritesStayUnfilteredUntilTheTypingPauseEnds() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioClient
            .toggleSongFavorite(new HorizonRadioScreen.SearchResult("favorite", "Jazz Song", "Alice", "2:00", ""));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.setSearchText("ro");
        screen.updateAutoSearch(100L);
        screen.updateAutoSearch(399L);
        assertEquals(Collections.singletonList("favorite"), screen.searchDisplayVideoIds());
        assertTrue(provider.searchQueries.isEmpty());
        screen.updateAutoSearch(400L);
        assertEquals(Collections.singletonList("ro"), provider.searchQueries);
        assertTrue(
            screen.searchDisplayVideoIds()
                .isEmpty());
    }

    @Test
    public void autoSearchWaitsForThreeHundredMillisecondsAfterTheLastEditAndRunsOnce() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.setSearchText("ja");
        screen.updateAutoSearch(100L);
        screen.updateAutoSearch(399L);
        assertTrue(provider.searchQueries.isEmpty());
        screen.setSearchText("jazz");
        screen.updateAutoSearch(399L);
        screen.updateAutoSearch(698L);
        assertTrue(provider.searchQueries.isEmpty());
        screen.updateAutoSearch(699L);
        screen.updateAutoSearch(5000L);
        assertEquals(Collections.singletonList("jazz"), provider.searchQueries);
    }

    @Test
    public void configuredSearchDelayAndDisabledAutomaticSearchAreRespected() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        ClientUiSettings settings = HorizonRadioClient.uiSettings();
        int oldDelay = settings.searchDelay;
        boolean oldAutoSearch = settings.autoSearch;
        try {
            settings.searchDelay = 1200;
            settings.autoSearch = true;
            TestScreen screen = new TestScreen();
            screen.initialize();
            screen.setSearchText("jazz");
            screen.updateAutoSearch(100L);
            screen.updateAutoSearch(1299L);
            assertTrue(provider.searchQueries.isEmpty());
            screen.updateAutoSearch(1300L);
            assertEquals(Collections.singletonList("jazz"), provider.searchQueries);
            settings.autoSearch = false;
            screen.setSearchText("rock");
            screen.updateAutoSearch(1400L);
            screen.updateAutoSearch(10000L);
            assertEquals(Collections.singletonList("jazz"), provider.searchQueries);
            screen.invokeSearchAction();
            assertEquals(java.util.Arrays.asList("jazz", "rock"), provider.searchQueries);
        } finally {
            settings.searchDelay = oldDelay;
            settings.autoSearch = oldAutoSearch;
        }
    }

    @Test
    public void clickingSearchFieldsClearsExistingQueriesInEveryDiscoveryTab() {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        for (int tab = 0; tab < 4; tab++) {
            screen.initialize();
            if (tab == 0) screen.selectSearchTab();
            else if (tab == 1) screen.selectChartsTab();
            else if (tab == 2) screen.selectRadioTab();
            else screen.selectPlaylistDiscoveryTab();
            GuiTextField field = screen.searchField();
            if (tab == 3) screen.setPlaylistUrlText("previous playlist link");
            field.setText("previous input");
            screen.click(field.xPosition + 5, field.yPosition + 5);
            assertEquals("Tab " + tab, "", field.getText());
            assertTrue(field.isFocused());
            field.setText("new input");
            screen.click(field.xPosition + 5, field.yPosition + 5);
            assertEquals("Tab " + tab, "", field.getText());
        }
    }

    @Test
    public void playlistNameAutoSearchShowsCollectionsAndClickImportsTheSelectedPlaylist() throws Exception {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider(
            "{\"title\":\"Jazz collection\",\"entries\":[{\"id\":\"song\",\"title\":\"Song\",\"duration\":60}]}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();
        HorizonRadioScreen.setActiveScreen(screen);
        try {
            screen.setSearchText("jazz");
            screen.updateAutoSearch(100L);
            screen.updateAutoSearch(600L);
            assertEquals(Collections.singletonList("jazz"), provider.playlistQueries);
            assertTrue(provider.playlistUrls.isEmpty());
            java.lang.reflect.Field revealAt = HorizonRadioScreen.class.getDeclaredField("playlistResultsRevealAt");
            revealAt.setAccessible(true);
            revealAt.setLong(screen, 0L);
            java.lang.reflect.Method reveal = HorizonRadioScreen.class.getDeclaredMethod("updatePendingResultReveals");
            reveal.setAccessible(true);
            reveal.invoke(screen);
            screen.click(screen.resultRowCenterX(), screen.playlistFirstRowQueueButtonCenterY());
            assertEquals(
                Collections.singletonList("https://www.youtube.com/playlist?list=PLjazz"),
                provider.playlistUrls);
            assertEquals(
                "song",
                HorizonRadioClient.getCachedPlaylistResults()
                    .get(0).videoId);
            assertNull(transport.playNowRequest);
            assertNull(transport.addRequest);
        } finally {
            HorizonRadioScreen.clearActiveScreen(screen);
        }
    }

    @Test
    public void manualSearchCancelsTheScheduledDuplicate() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.setSearchText("jazz");
        screen.updateAutoSearch(100L);
        screen.invokeSearchAction();
        screen.updateAutoSearch(2000L);
        assertEquals(Collections.singletonList("jazz"), provider.searchQueries);
    }

    @Test
    public void chartsAutoSearchWaitsForTheLastEdit() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.selectChartsTab();
        screen.setSearchText("Ger");
        screen.updateAutoSearch(100L);
        screen.setSearchText("Germany");
        screen.updateAutoSearch(500L);
        screen.updateAutoSearch(799L);
        assertTrue(provider.chartRegions.isEmpty());
        screen.updateAutoSearch(800L);
        screen.updateAutoSearch(2500L);
        assertEquals(Collections.singletonList("DE"), provider.chartRegions);
        assertEquals("DE", screen.getChartRegionCode());
    }

    @Test
    public void radioAutoSearchRunsOnceAfterThreeHundredMilliseconds() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.selectRadioTab();
        provider.radioQueries.clear();
        screen.setSearchText("jazz");
        screen.updateAutoSearch(100L);
        screen.updateAutoSearch(399L);
        assertTrue(provider.radioQueries.isEmpty());
        screen.updateAutoSearch(400L);
        screen.updateAutoSearch(2100L);
        assertEquals(Collections.singletonList("jazz"), provider.radioQueries);
    }

    @Test
    public void playlistAutoSearchUsesItsOwnFieldAndManualImportCancelsTimer() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();
        String url = "https://www.youtube.com/playlist?list=PLauto";
        screen.setPlaylistUrlText(url);
        screen.updateAutoSearch(100L);
        screen.updateAutoSearch(399L);
        assertTrue(provider.playlistUrls.isEmpty());
        screen.updateAutoSearch(400L);
        screen.updateAutoSearch(2100L);
        assertEquals(Collections.singletonList(url), provider.playlistUrls);
        String secondUrl = "https://www.youtube.com/playlist?list=PLmanual";
        screen.setPlaylistUrlText(secondUrl);
        screen.updateAutoSearch(2200L);
        screen.invokeSearchAction();
        screen.updateAutoSearch(4000L);
        assertEquals(java.util.Arrays.asList(url, secondUrl), provider.playlistUrls);
    }

    @Test
    public void clearingSearchCancelsPendingSearchAndHidesLateResults() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "favorite", 0L, 0L, true));
        HorizonRadioClient.toggleCurrentFavorite();
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.setSearchText("jazz");
        screen.updateAutoSearch(100L);
        screen.setSearchText("   ");
        screen.updateAutoSearch(500L);
        screen.updateSearchResults(singleResult());
        screen.updateAutoSearch(2000L);
        assertTrue(provider.searchQueries.isEmpty());
        assertEquals(Collections.singletonList("favorite"), screen.searchDisplayVideoIds());
    }

    @Test
    public void leavingSongSearchCancelsTheTimer() {
        ImmediatePlaylistImportProvider provider = new ImmediatePlaylistImportProvider("{}");
        HorizonRadioClient.setClientMediaService(new ClientMediaService(provider));
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.setSearchText("jazz");
        screen.updateAutoSearch(100L);
        screen.selectChartsTab();
        screen.updateAutoSearch(200L);
        screen.selectSearchTab();
        screen.updateAutoSearch(2000L);
        assertTrue(provider.searchQueries.isEmpty());
    }

    @Test
    public void nonEmptySearchShowsOnlySearchResults() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.selectSearchTab();
        screen.setSearchText("new query");
        screen.updateSearchResults(singleResult());

        assertEquals(Collections.singletonList("video"), screen.searchDisplayVideoIds());
    }

    @Test
    public void clearingSharedSearchQueryClampsSearchAndRadioScrollOffsets() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.selectSearchTab();
        screen.setSearchText("q");
        screen.updateSearchResults(searchResults(12));
        screen.setSearchScrollOffset(6);

        screen.setSearchText("");
        screen.applySharedSearchTextChange();

        assertEquals(0, screen.searchScrollOffset());

        screen.selectRadioTab();
        screen.setSearchText("q");
        screen.updateRadioResults(radioStations(12));
        screen.setRadioScrollOffset(6);

        screen.setSearchText("");
        screen.applySharedSearchTextChange();

        assertEquals(0, screen.radioScrollOffset());
    }

    @Test
    public void emptyRadioSearchShowsFavoritesBeforePopularStations() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "favorite-radio"));
        HorizonRadioClient.toggleCurrentFavorite();
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();
        screen.selectRadioTab();
        screen.updateRadioResults(
            Collections.singletonList(new HorizonRadioScreen.RadioStationResult("popular-radio", "Popular")));

        assertEquals(Arrays.asList("favorite-radio", "popular-radio"), screen.radioDisplayStationUuids());
    }

    @Test
    public void radioStartsWithFavoritesEvenAfterASongSearchAndRemembersItsOwnQuery() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "favorite-radio"));
        HorizonRadioClient.toggleCurrentFavorite();
        TestScreen screen = new TestScreen();
        screen.initialize();
        screen.setSearchText("jazz song");
        screen.selectRadioTab();
        screen.updateRadioResults(
            Arrays.asList(
                new HorizonRadioScreen.RadioStationResult("popular-radio", "Popular"),
                new HorizonRadioScreen.RadioStationResult("favorite-radio", "Favorite")));

        assertEquals(
            "",
            screen.searchField()
                .getText());
        assertEquals(Arrays.asList("favorite-radio", "popular-radio"), screen.radioDisplayStationUuids());

        screen.setSearchText("rock station");
        screen.selectSearchTab();
        assertEquals(
            "jazz song",
            screen.searchField()
                .getText());
        screen.selectRadioTab();
        assertEquals(
            "rock station",
            screen.searchField()
                .getText());
        assertEquals(Arrays.asList("favorite-radio", "popular-radio"), screen.radioDisplayStationUuids());

        screen.setSearchText("");
        assertEquals(Arrays.asList("favorite-radio", "popular-radio"), screen.radioDisplayStationUuids());
    }

    @Test
    public void favoriteControlRemovesCurrentFavorite() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "favorite", 0L, 0L, true));
        HorizonRadioClient.toggleCurrentFavorite();
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.initialize();

        screen.invokeFavoriteAction();

        assertTrue(
            HorizonRadioClient.getFavoriteSongs()
                .isEmpty());
        assertFalse(HorizonRadioClient.isCurrentSourceFavorite());
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
        assertTrue(screen.contains("PANEL_WIDTH = HorizonRadioUiLayout.REFERENCE_PANEL_WIDTH"));
        assertTrue(screen.contains("PANEL_HEIGHT = HorizonRadioUiLayout.REFERENCE_PANEL_HEIGHT"));
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
        assertFalse(screen.contains("heartLeft"));
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
        assertTrue(screen.contains("songsTabButton"));
        assertTrue(screen.contains("radioTabButton"));
        assertTrue(screen.contains("drawQueuePanel"));
        assertTrue(screen.contains("beginUiTransform"));
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

    private static List<HorizonRadioScreen.SearchResult> searchResults(int count) {
        List<HorizonRadioScreen.SearchResult> results = new ArrayList<HorizonRadioScreen.SearchResult>();
        for (int index = 0; index < count; index++) {
            results.add(new HorizonRadioScreen.SearchResult("video-" + index, "Song " + index, "", "2:00", ""));
        }
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
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectChartsTab();
        screen.updateChartResults(singleResult());
        return screen;
    }

    private static TestScreen initializedPlaylistScreen(List<HorizonRadioScreen.SearchResult> results) {
        TestScreen screen = new TestScreen();
        screen.usePrototypeViewport();
        screen.initialize();
        screen.selectPlaylistDiscoveryTab();
        screen.updatePlaylistResults(results);
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

    private static long playlistImportGeneration() {
        try {
            java.lang.reflect.Field field = HorizonRadioClient.class.getDeclaredField("playlistImportGeneration");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Playlist import generation was not available", exception);
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

    private static boolean invokeShouldShowQueueClearButton(int playlistSize, boolean radioActive) {
        try {
            java.lang.reflect.Method method = HorizonRadioScreen.class
                .getDeclaredMethod("shouldShowQueueClearButton", int.class, boolean.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(null, playlistSize, radioActive);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Queue clear visibility policy was not available", exception);
        }
    }

    private static String invokeSearchResultGlyph(boolean favorite) {
        try {
            java.lang.reflect.Method method = HorizonRadioScreen.class
                .getDeclaredMethod("searchResultGlyph", boolean.class);
            method.setAccessible(true);
            return (String) method.invoke(null, favorite);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Search result glyph policy was not available", exception);
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

        private void usePrototypeViewport() {
            setScreenSize(640, 360);
        }

        private int prototypePanelLeft() {
            return (width - HorizonRadioScreen.PANEL_WIDTH) / 2;
        }

        private int prototypePanelTop() {
            return (height - HorizonRadioScreen.PANEL_HEIGHT) / 2;
        }

        private int resultRowCenterX() {
            return prototypePanelLeft() + screenConstant("CONTENT_LEFT_INSET") + 35;
        }

        private int resultQueueButtonCenterX() {
            int contentRight = prototypePanelLeft() + screenConstant("CONTENT_LEFT_INSET")
                + screenConstant("CONTENT_WIDTH")
                - 7;
            int buttonLeft = contentRight - screenConstant("QUEUE_BUTTON_RIGHT_MARGIN")
                - screenConstant("QUEUE_BUTTON_COLUMN_WIDTH")
                + (screenConstant("QUEUE_BUTTON_COLUMN_WIDTH") - screenConstant("QUEUE_BUTTON_WIDTH")) / 2;
            return buttonLeft + screenConstant("QUEUE_BUTTON_WIDTH") / 2;
        }

        private int resultRowCenterY() {
            int listTop = currentTab() == screenConstant("SEARCH_TAB") ? HorizonRadioScreen.searchListTopOffset(false)
                : screenConstant("CONTENT_LIST_TOP_OFFSET");
            return prototypePanelTop() + listTop + screenConstant("ROW_HEIGHT") / 2;
        }

        private int searchRowCenterY(int row) {
            return prototypePanelTop() + HorizonRadioScreen.searchListTopOffset(false)
                + row * screenConstant("ROW_HEIGHT")
                + screenConstant("ROW_HEIGHT") / 2;
        }

        private int radioRowCenterY() {
            return prototypePanelTop() + screenConstant("RADIO_LIST_TOP_WITH_PROGRESS_OFFSET")
                + screenConstant("ROW_HEIGHT") / 2;
        }

        private int queueRowCenterX() {
            return prototypePanelLeft() + screenConstant("QUEUE_LEFT_INSET") + 35;
        }

        private int queueRowCenterY(int row) {
            return prototypePanelTop() + screenConstant("QUEUE_LIST_TOP_OFFSET")
                + row * screenConstant("ROW_HEIGHT")
                + screenConstant("ROW_HEIGHT") / 2;
        }

        private int radioScrollbarX() {
            return prototypePanelLeft() + screenConstant("CONTENT_LEFT_INSET")
                + screenConstant("CONTENT_WIDTH")
                - 7
                - screenConstant("RESULT_SCROLLBAR_LEFT_OFFSET");
        }

        private int bulkButtonCenterY() {
            return prototypePanelTop() + screenConstant("CHARTS_BULK_BUTTON_Y_OFFSET")
                + screenConstant("QUEUE_BUTTON_HEIGHT") / 2;
        }

        private void selectSearchTab() {
            actionPerformed(new GuiButton(9, 0, 0, "Search"));
        }

        private void selectChartsTab() {
            actionPerformed(new GuiButton(1, 0, 0, "Charts"));
        }

        private void selectPlaylistTab() {
            actionPerformed(new GuiButton(2, 0, 0, "Queue"));
        }

        private void selectPlaylistDiscoveryTab() {
            actionPerformed(new GuiButton(13, 0, 0, "Playlists"));
        }

        private void selectRadioTab() {
            actionPerformed(new GuiButton(11, 0, 0, "Radio"));
        }

        private void setScreenSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private void click(int mouseX, int mouseY) {
            mouseClicked(toPhysicalX(mouseX), toPhysicalY(mouseY), 0);
        }

        private void moveHeldMouse(int mouseX, int mouseY) {
            mouseClickMove(toPhysicalX(mouseX), toPhysicalY(mouseY), 0, 1L);
        }

        private void release(int mouseX, int mouseY) {
            mouseMovedOrUp(toPhysicalX(mouseX), toPhysicalY(mouseY), 0);
        }

        private int toPhysicalX(int logicalX) {
            HorizonRadioUiLayout layout = HorizonRadioUiLayout.create(width, height);
            return layout.panelLeft() + Math.round((logicalX - layout.referencePanelLeft()) * layout.scale());
        }

        private int toPhysicalY(int logicalY) {
            HorizonRadioUiLayout layout = HorizonRadioUiLayout.create(width, height);
            return layout.panelTop() + Math.round((logicalY - layout.referencePanelTop()) * layout.scale());
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

        private void applySharedSearchTextChange() {
            try {
                java.lang.reflect.Method method = HorizonRadioScreen.class
                    .getDeclaredMethod("clampSharedSearchResultScrollOffsets");
                method.setAccessible(true);
                method.invoke(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Shared search scroll clamping was not available", exception);
            }
        }

        private void setSearchScrollOffset(int offset) {
            setScrollOffset("searchScrollOffset", offset);
        }

        private int searchScrollOffset() {
            return scrollOffset("searchScrollOffset");
        }

        private void setRadioScrollOffset(int offset) {
            setScrollOffset("radioScrollOffset", offset);
        }

        private int radioScrollOffset() {
            return scrollOffset("radioScrollOffset");
        }

        private void setScrollOffset(String fieldName, int offset) {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(this, offset);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Result scroll offset was not available", exception);
            }
        }

        private int scrollOffset(String fieldName) {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getInt(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Result scroll offset was not available", exception);
            }
        }

        private void setPlaylistUrlText(String value) {
            GuiTextField field = playlistUrlField();
            field.setText(value);
            field.setFocused(true);
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

        private void invokePlaylistEnter() {
            keyTyped('\r', Keyboard.KEY_RETURN);
        }

        private void invokePlaybackAction() {
            actionPerformed(new GuiButton(6, 0, 0, "Playback"));
        }

        private void invokeControlAction(int id) {
            actionPerformed(new GuiButton(id, 0, 0, "Control"));
        }

        private void invokeFavoriteAction() {
            actionPerformed(new GuiButton(12, 0, 0, "Favorite"));
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

        private String buttonLabel(int id) {
            GuiButton button = controlButton(id);
            try {
                java.lang.reflect.Field field = button.getClass()
                    .getDeclaredField("label");
                field.setAccessible(true);
                return (String) field.get(button);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Control button label was not available", exception);
            }
        }

        private int currentTab() {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("currentTab");
                field.setAccessible(true);
                return field.getInt(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Current tab was not available", exception);
            }
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

        private GuiTextField playlistUrlField() {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class.getDeclaredField("playlistUrlField");
                field.setAccessible(true);
                return (GuiTextField) field.get(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Playlist URL field was not initialized", exception);
            }
        }

        boolean isPlaylistDiscoveryTab() {
            return super.isPlaylistDiscoveryTab();
        }

        private List<String> searchDisplayVideoIds() {
            List<SearchResult> results = invokeDisplayedSearchResults();
            List<String> ids = new ArrayList<String>();
            for (SearchResult result : results) {
                ids.add(result.videoId);
            }
            return ids;
        }

        private List<String> chartResultsSnapshotVideoIds() {
            List<String> ids = new ArrayList<String>();
            for (SearchResult result : chartResultsSnapshot()) {
                ids.add(result.videoId);
            }
            return ids;
        }

        private List<String> playlistResultVideoIds() {
            List<String> ids = new ArrayList<String>();
            for (SearchResult result : getPlaylistResultsSnapshot()) {
                ids.add(result.videoId);
            }
            return ids;
        }

        private boolean hasPlaylistResultsRevealPending() {
            try {
                java.lang.reflect.Field field = HorizonRadioScreen.class
                    .getDeclaredField("playlistResultsRevealPending");
                field.setAccessible(true);
                return field.getBoolean(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Playlist result reveal state was not available", exception);
            }
        }

        private int playlistFirstRowQueueButtonCenterY() {
            try {
                java.lang.reflect.Method listTopMethod = HorizonRadioScreen.class
                    .getDeclaredMethod("playlistDiscoveryListTop", int.class);
                java.lang.reflect.Method buttonTopMethod = HorizonRadioScreen.class
                    .getDeclaredMethod("queueButtonTop", int.class);
                listTopMethod.setAccessible(true);
                buttonTopMethod.setAccessible(true);
                int panelTop = (height - screenConstant("PANEL_HEIGHT")) / 2;
                int listTop = ((Integer) listTopMethod.invoke(this, panelTop)).intValue();
                int buttonTop = ((Integer) buttonTopMethod.invoke(this, listTop)).intValue();
                return buttonTop + screenConstant("QUEUE_BUTTON_HEIGHT") / 2;
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Playlist first-row queue button position was not available", exception);
            }
        }

        private int playlistFirstRowQueueButtonCenterX() {
            return resultQueueButtonCenterX();
        }

        private List<String> radioDisplayStationUuids() {
            List<RadioStationResult> results = invokeDisplayedRadioResults();
            List<String> ids = new ArrayList<String>();
            for (RadioStationResult result : results) {
                ids.add(result.stationUuid);
            }
            return ids;
        }

        @SuppressWarnings("unchecked")
        private List<SearchResult> invokeDisplayedSearchResults() {
            try {
                java.lang.reflect.Method method = HorizonRadioScreen.class.getDeclaredMethod("displayedSearchResults");
                method.setAccessible(true);
                return (List<SearchResult>) method.invoke(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Displayed search results were not available", exception);
            }
        }

        @SuppressWarnings("unchecked")
        private List<RadioStationResult> invokeDisplayedRadioResults() {
            try {
                java.lang.reflect.Method method = HorizonRadioScreen.class.getDeclaredMethod("displayedRadioResults");
                method.setAccessible(true);
                return (List<RadioStationResult>) method.invoke(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Displayed radio results were not available", exception);
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

        private final List<String> chartSelections = new ArrayList<String>();
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
        public void sendAddChartSelections(List<HorizonRadioClient.PlaylistSelection> selections, boolean remove) {
            addChartsRequest = true;
            if (remove) {
                return;
            }
            if (selections == null) {
                return;
            }
            for (HorizonRadioClient.PlaylistSelection selection : selections) {
                chartSelections.add(selection.videoId + "|" + selection.durationMs);
            }
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

    private static final class ImmediatePlaylistImportProvider implements ClientMediaService.RemoteProvider {

        private final List<String> playlistQueries = new ArrayList<String>();

        @Override
        public CompletableFuture<List<com.horizonradio.core.model.PlaylistSearchResult>> searchPlaylists(String query) {
            playlistQueries.add(query);
            return CompletableFuture.completedFuture(
                Collections.singletonList(
                    new com.horizonradio.core.model.PlaylistSearchResult(
                        "PLjazz",
                        "Jazz collection",
                        "Channel",
                        "10 videos")));
        }

        private final String playlistJson;
        private final List<String> searchQueries = new ArrayList<String>();
        private final List<String> radioQueries = new ArrayList<String>();
        private final List<String> playlistUrls = new ArrayList<String>();
        private final List<String> chartRegions = new ArrayList<String>();

        private ImmediatePlaylistImportProvider(String playlistJson) {
            this.playlistJson = playlistJson;
        }

        @Override
        public CompletableFuture<List<com.horizonradio.core.model.SearchResult>> search(String query,
            long maxDurationMs) {
            searchQueries.add(query);
            return CompletableFuture.completedFuture(Collections.<com.horizonradio.core.model.SearchResult>emptyList());
        }

        @Override
        public CompletableFuture<List<com.horizonradio.core.model.SearchResult>> fetchCharts(ChartRegion region) {
            chartRegions.add(region.getCode());
            return CompletableFuture.completedFuture(Collections.<com.horizonradio.core.model.SearchResult>emptyList());
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            playlistUrls.add(playlistUrl);
            return CompletableFuture.completedFuture(playlistJson);
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            return CompletableFuture.completedFuture("{}");
        }

        @Override
        public CompletableFuture<List<RadioStation>> searchRadio(String query) {
            radioQueries.add(query);
            return CompletableFuture.completedFuture(Collections.<RadioStation>emptyList());
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class PendingPlaylistImportProvider implements ClientMediaService.RemoteProvider {

        private final CompletableFuture<String> playlistJson;
        private int playlistImportCallCount;

        private PendingPlaylistImportProvider(CompletableFuture<String> playlistJson) {
            this.playlistJson = playlistJson;
        }

        @Override
        public CompletableFuture<List<com.horizonradio.core.model.SearchResult>> search(String query,
            long maxDurationMs) {
            return CompletableFuture.completedFuture(Collections.<com.horizonradio.core.model.SearchResult>emptyList());
        }

        @Override
        public CompletableFuture<List<com.horizonradio.core.model.SearchResult>> fetchCharts(ChartRegion region) {
            return CompletableFuture.completedFuture(Collections.<com.horizonradio.core.model.SearchResult>emptyList());
        }

        @Override
        public CompletableFuture<String> extractPlaylistJson(String playlistUrl) {
            playlistImportCallCount++;
            return playlistJson;
        }

        @Override
        public CompletableFuture<String> extractVideoJson(String videoUrl) {
            return CompletableFuture.completedFuture("{}");
        }

        @Override
        public CompletableFuture<List<RadioStation>> searchRadio(String query) {
            return CompletableFuture.completedFuture(Collections.<RadioStation>emptyList());
        }

        @Override
        public CompletableFuture<RadioStation> lookupRadio(String stationUuid) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
