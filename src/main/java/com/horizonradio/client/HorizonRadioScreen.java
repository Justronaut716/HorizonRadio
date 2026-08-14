package com.horizonradio.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.horizonradio.core.model.DurationParser;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;

/** Forge 1.7.10 port of the active HorizonRadio search and playlist screen. */
public class HorizonRadioScreen extends GuiScreen {

    public static final int PANEL_WIDTH = 300;
    public static final int PANEL_HEIGHT = 285;
    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int ROW_HEIGHT = 25;
    private static final int NOW_PLAYING_HEIGHT = 25;
    private static final int CONTROL_CENTER_HEIGHT = 34;
    private static final int CONTROL_BUTTON_WIDTH = 26;
    private static final int CONTROL_BUTTON_HEIGHT = 24;
    private static final int CONTROL_BUTTON_GAP = 3;
    private static final int CONTROL_BUTTON_COUNT = 6;
    private static final int CONTROL_ICON_SIZE = 16;
    private static final int CONTROL_ICON_TEXTURE_SIZE = 128;
    private static final int TAB_BUTTON_WIDTH = 50;
    private static final int TAB_BUTTON_HEIGHT = 20;
    private static final int TAB_BUTTON_Y = 5;
    private static final int SEARCH_CONTROL_HEIGHT = 20;
    private static final int SEARCH_CONTROL_Y_OFFSET = 30;
    private static final int SEARCH_SIDE_MARGIN = 10;
    private static final int SEARCH_CONTROL_GAP = 0;
    private static final int SEARCH_FIELD_X_OFFSET = SEARCH_SIDE_MARGIN + 1;
    private static final int SEARCH_BUTTON_WIDTH = CONTROL_BUTTON_WIDTH;
    private static final int SEARCH_FIELD_WIDTH = PANEL_WIDTH - SEARCH_FIELD_X_OFFSET
        - SEARCH_CONTROL_GAP
        - SEARCH_BUTTON_WIDTH
        - SEARCH_SIDE_MARGIN;
    private static final int SEARCH_BUTTON_BORDER_COLOR = 0xFFA0A0A0;
    private static final int SEARCH_BUTTON_HEIGHT = SEARCH_CONTROL_HEIGHT + 2;
    private static final int SEARCH_BUTTON_Y_OFFSET = SEARCH_CONTROL_Y_OFFSET - 1;
    private static final int CHARTS_BULK_BUTTON_Y_OFFSET = SEARCH_BUTTON_Y_OFFSET + SEARCH_BUTTON_HEIGHT + 1;
    private static final int CHARTS_TAB = 0;
    private static final int SEARCH_TAB = 1;
    private static final int PLAYLIST_TAB = 2;
    private static final int PLAYLIST_DISCOVERY_TAB = 3;
    private static final int RADIO_TAB = 4;
    private static final int CHARTS_TAB_X = 8;
    private static final int SEARCH_TAB_X = 61;
    private static final int PLAYLIST_TAB_X = 114;
    private static final int PLAYLIST_DISCOVERY_TAB_X = 167;
    private static final int RADIO_TAB_X = 220;
    private static final int BUTTON_SEARCH = 0;
    private static final int BUTTON_CHARTS_TAB = 1;
    private static final int BUTTON_PLAYLIST_TAB = 2;
    private static final int BUTTON_SEARCH_TAB = 9;
    private static final int BUTTON_REFRESH_CHARTS = 10;
    private static final int BUTTON_RADIO_TAB = 11;
    private static final int BUTTON_FAVORITE = 12;
    private static final int BUTTON_PLAYLIST_DISCOVERY_TAB = 13;
    private static final int QUEUE_BUTTON_WIDTH = 20;
    private static final int QUEUE_BUTTON_HEIGHT = 18;
    private static final int QUEUE_BUTTON_COLUMN_WIDTH = 26;
    private static final int QUEUE_BUTTON_RIGHT_MARGIN = 10;
    private static final ResourceLocation ICON_SHUFFLE = new ResourceLocation(
        "horizonradio",
        "textures/gui/Shuffle.png");
    private static final ResourceLocation ICON_PREVIOUS = new ResourceLocation(
        "horizonradio",
        "textures/gui/Previous.png");
    private static final ResourceLocation ICON_PLAY = new ResourceLocation("horizonradio", "textures/gui/Play.png");
    private static final ResourceLocation ICON_NEXT = new ResourceLocation("horizonradio", "textures/gui/Next.png");
    private static final ResourceLocation ICON_LOOP = new ResourceLocation("horizonradio", "textures/gui/Repeat.png");
    private static final ResourceLocation ICON_PAUSE = new ResourceLocation("horizonradio", "textures/gui/Pause.png");
    private static final ResourceLocation ICON_SEARCH = new ResourceLocation("horizonradio", "textures/gui/Search.png");
    private static final int SEARCH_PROGRESS_Y_OFFSET = 55;
    private static final int SEARCH_PROGRESS_HEIGHT = 6;
    private static final int SEARCH_LIST_TOP_OFFSET = 70;
    private static final int SEARCH_LIST_TOP_WITHOUT_PROGRESS_OFFSET = 55;
    private static final int CONTENT_HEADER_Y_OFFSET = 50;
    private static final int CONTENT_LABEL_Y_OFFSET = 56;
    private static final int CONTENT_LIST_TOP_OFFSET = 70;
    private static final int PLAYLIST_HEADER_Y_OFFSET = 25;
    private static final int PLAYLIST_TITLE_Y_OFFSET = 31;
    private static final int PLAYLIST_LIST_TOP_OFFSET = 45;
    private static final int RESULT_DURATION_COLUMN_WIDTH = 40;
    private static final int RESULT_SCROLLBAR_WIDTH = 3;
    private static final int RESULT_SCROLLBAR_LEFT_OFFSET = 6;
    private static final int RESULT_SCROLLBAR_MIN_THUMB_HEIGHT = 10;
    private static final int QUEUE_DISPLAY_LIMIT = 50;
    private static final long SEARCH_PROGRESS_ESTIMATE_MILLIS = 1500L;
    private static final long CHART_PROGRESS_ESTIMATE_MILLIS = 1000L;
    private static final long RADIO_PROGRESS_ESTIMATE_MILLIS = 400L;
    private static final long RESULT_REVEAL_DELAY_MILLIS = 150L;
    private static final int TIME_BAR_SIDE_SPACE = 38;

    private GuiTextField searchField;
    private GuiTextField playlistUrlField;
    private ControlButton searchButton;
    private ControlButton refreshChartsButton;
    private HorizonRadioVolumeSlider volumeSlider;
    private List<SearchResult> chartResults = new ArrayList<SearchResult>();
    private final Set<String> pendingChartAdds = new HashSet<String>();
    private final Set<String> pendingPlaylistAdds = new HashSet<String>();
    private String chartRegionCode = "";
    private String chartSearchMessage = "";
    private List<SearchResult> searchResults = new ArrayList<SearchResult>();
    private String searchError = "";
    private List<SearchResult> playlistResults = new ArrayList<SearchResult>();
    private boolean playlistLoading;
    private String playlistError = "";
    private List<PlaylistEntry> playlist = new ArrayList<PlaylistEntry>();
    private List<RadioStationResult> radioResults = new ArrayList<RadioStationResult>();
    private int currentTab;
    private int chartScrollOffset;
    private int searchScrollOffset;
    private int playlistScrollOffset;
    private int queueScrollOffset;
    private int radioScrollOffset;
    private String nowPlaying;
    private String currentDuration;
    private float playbackProgress;
    private float searchProgress;
    private boolean searchLoading;
    private long searchStartedAt;
    private boolean searchResultsRevealPending;
    private long searchResultsRevealAt;
    private float chartProgress;
    private boolean chartLoading;
    private String chartError = "";
    private long chartStartedAt;
    private boolean chartResultsRevealPending;
    private long chartResultsRevealAt;
    private float playlistProgress;
    private long playlistStartedAt;
    private boolean playlistResultsRevealPending;
    private long playlistResultsRevealAt;
    private float radioProgress;
    private boolean radioLoading;
    private String radioError = "";
    private long radioStartedAt;
    private boolean radioResultsRevealPending;
    private long radioResultsRevealAt;
    private boolean radioPopularRequested;
    private ClientRadioPresentation radioState;
    private boolean seeking;
    private float seekProgress;
    private ControlButton playbackButton;
    private ControlButton loopButton;
    private ControlButton shuffleButton;
    private ControlButton previousButton;
    private ControlButton nextButton;
    private ControlButton favoriteButton;
    private int draggedPlaylistIndex = -1;
    private PlaylistEntry draggedPlaylistEntry;
    private boolean playlistDragMoved;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private boolean draggingResultScrollbar;
    private int resultScrollbarDragOffset;
    private static HorizonRadioScreen activeScreen;

    public HorizonRadioScreen() {
        super();
    }

    @Override
    public void initGui() {
        setActiveScreen(this);
        int panelLeft = panelLeft();
        int panelTop = panelTop();
        chartResults = HorizonRadioClient.getCachedCharts();
        chartRegionCode = normalizeChartRegionCode(HorizonRadioClient.getCachedChartRegionCode());
        playlistResults = HorizonRadioClient.getCachedPlaylistResults();
        playlist = HorizonRadioClient.getCachedPlaylist();
        radioLoading = false;
        radioResultsRevealPending = false;
        updateRadioResultsFromStations(HorizonRadioClient.getCachedRadioResults());
        nowPlaying = HorizonRadioClient.getCachedNowPlaying();
        playbackProgress = HorizonRadioClient.getCachedProgress();
        refreshCurrentDuration();
        updateRadioPresentation(HorizonRadioClient.getCachedRadioPresentation());

        searchField = new GuiTextField(
            fontRendererObj,
            panelLeft + SEARCH_FIELD_X_OFFSET,
            panelTop + SEARCH_CONTROL_Y_OFFSET,
            SEARCH_FIELD_WIDTH,
            SEARCH_CONTROL_HEIGHT);
        searchField.setMaxStringLength(100);
        searchField.setFocused(false);
        playlistUrlField = new GuiTextField(
            fontRendererObj,
            panelLeft + SEARCH_FIELD_X_OFFSET,
            panelTop + SEARCH_CONTROL_Y_OFFSET,
            SEARCH_FIELD_WIDTH,
            SEARCH_CONTROL_HEIGHT);
        playlistUrlField.setMaxStringLength(100);
        playlistUrlField.setFocused(false);

        buttonList.clear();
        searchButton = new ControlButton(
            BUTTON_SEARCH,
            panelLeft + PANEL_WIDTH - SEARCH_SIDE_MARGIN - SEARCH_BUTTON_WIDTH,
            panelTop + SEARCH_BUTTON_Y_OFFSET,
            SEARCH_BUTTON_WIDTH,
            SEARCH_BUTTON_HEIGHT,
            ICON_SEARCH,
            SEARCH_BUTTON_BORDER_COLOR);
        addButton(searchButton);
        addButton(
            new GuiButton(
                BUTTON_CHARTS_TAB,
                panelLeft + CHARTS_TAB_X,
                panelTop + TAB_BUTTON_Y,
                TAB_BUTTON_WIDTH,
                TAB_BUTTON_HEIGHT,
                "Charts"));
        addButton(
            new GuiButton(
                BUTTON_SEARCH_TAB,
                panelLeft + SEARCH_TAB_X,
                panelTop + TAB_BUTTON_Y,
                TAB_BUTTON_WIDTH,
                TAB_BUTTON_HEIGHT,
                "Search"));
        addButton(
            new GuiButton(
                BUTTON_PLAYLIST_TAB,
                panelLeft + PLAYLIST_TAB_X,
                panelTop + TAB_BUTTON_Y,
                TAB_BUTTON_WIDTH,
                TAB_BUTTON_HEIGHT,
                "Queue"));
        addButton(
            new GuiButton(
                BUTTON_PLAYLIST_DISCOVERY_TAB,
                panelLeft + PLAYLIST_DISCOVERY_TAB_X,
                panelTop + TAB_BUTTON_Y,
                TAB_BUTTON_WIDTH,
                TAB_BUTTON_HEIGHT,
                "Playlists"));
        addButton(
            new GuiButton(
                BUTTON_RADIO_TAB,
                panelLeft + RADIO_TAB_X,
                panelTop + TAB_BUTTON_Y,
                TAB_BUTTON_WIDTH,
                TAB_BUTTON_HEIGHT,
                "Radio"));
        refreshChartsButton = new ControlButton(
            BUTTON_REFRESH_CHARTS,
            panelLeft + PANEL_WIDTH - CONTROL_BUTTON_WIDTH,
            panelTop + TAB_BUTTON_Y,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            ICON_LOOP);
        addButton(refreshChartsButton);
        addControlButtons(panelLeft, panelTop);
        volumeSlider = new HorizonRadioVolumeSlider(
            3,
            panelLeft + 10,
            panelTop + PANEL_HEIGHT + 5,
            PANEL_WIDTH - 20,
            20,
            HorizonRadioClient.getVolume());
        addButton(volumeSlider);
        openCharts();
        updateChartRefreshButtonState();
        updateControlVisibility();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int left = panelLeft();
        int top = panelTop();
        drawRect(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101010);
        drawPanelBorder(left, top);
        drawCenteredString(fontRendererObj, "HorizonRadio - Music Player", width / 2, top - 15, 0xFFFFFFFF);
        updatePendingResultReveals();

        if (currentTab == CHARTS_TAB) {
            updateChartProgress();
            drawChartsTab(left, top, mouseX, mouseY);
        } else if (currentTab == SEARCH_TAB) {
            updateSearchProgress();
            drawSearchTab(left, top, mouseX, mouseY);
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            updatePlaylistProgress();
            drawPlaylistDiscoveryTab(left, top, mouseX, mouseY);
        } else if (currentTab == RADIO_TAB) {
            updateRadioProgress();
            drawRadioTab(left, top, mouseX, mouseY);
        } else {
            drawPlaylistTab(left, top, mouseX, mouseY);
        }
        int nowPlayingTop = nowPlayingTop(top);
        drawNowPlaying(left, nowPlayingTop);
        drawControlCenter(left, nowPlayingTop);

        searchButton.visible = showsSearchButton();
        searchButton.enabled = currentTab != PLAYLIST_DISCOVERY_TAB || !playlistLoading;
        refreshChartsButton.visible = currentTab == CHARTS_TAB;
        updateChartRefreshButtonState();
        updateControlVisibility();
        if (usesSharedSearchField()) {
            searchField.drawTextBox();
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            playlistUrlField.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawPanelBorder(left, top);
        drawActiveTabBorder(left, top);
    }

    private void drawPanelBorder(int left, int top) {
        drawRect(left, top, left + PANEL_WIDTH, top + 1, 0xFFAAAAAA);
        drawRect(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF555555);
        drawRect(left, top, left + 1, top + PANEL_HEIGHT, 0xFFAAAAAA);
        drawRect(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF555555);
    }

    private void drawActiveTabBorder(int panelLeft, int panelTop) {
        int tabLeft;
        if (currentTab == CHARTS_TAB) {
            tabLeft = panelLeft + CHARTS_TAB_X;
        } else if (currentTab == SEARCH_TAB) {
            tabLeft = panelLeft + SEARCH_TAB_X;
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            tabLeft = panelLeft + PLAYLIST_DISCOVERY_TAB_X;
        } else if (currentTab == RADIO_TAB) {
            tabLeft = panelLeft + RADIO_TAB_X;
        } else {
            tabLeft = panelLeft + PLAYLIST_TAB_X;
        }
        int tabTop = panelTop + TAB_BUTTON_Y;
        int tabRight = tabLeft + TAB_BUTTON_WIDTH;
        int tabBottom = tabTop + TAB_BUTTON_HEIGHT;
        int color = 0xFFFFFFFF;
        drawRect(tabLeft - 1, tabTop - 1, tabRight + 1, tabTop, color);
        drawRect(tabLeft - 1, tabBottom, tabRight + 1, tabBottom + 1, color);
        drawRect(tabLeft - 1, tabTop, tabLeft, tabBottom, color);
        drawRect(tabRight, tabTop, tabRight + 1, tabBottom, color);
    }

    private void drawChartsTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isChartResultsLoading();
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, chartProgress);
        } else {
            String chartHeader = chartHeaderLabel(hasChartRegion(), chartRegionDisplayName());
            if (chartHeader.length() > 0) {
                drawString(fontRendererObj, chartHeader, left + 10, top + CONTENT_LABEL_Y_OFFSET, 0xFFE0E0E0);
            }
            if (chartSearchMessage.length() > 0) {
                drawString(
                    fontRendererObj,
                    chartSearchMessage,
                    left + 10,
                    top + CONTENT_HEADER_Y_OFFSET + 15,
                    0xFFFF7777);
            }
        }
        drawResultList(
            resultsLoading ? Collections.<SearchResult>emptyList() : chartResults,
            chartScrollOffset,
            left,
            top + CONTENT_LIST_TOP_OFFSET,
            mouseX,
            mouseY,
            resultsLoading ? "Loading charts..."
                : (chartError.length() > 0 ? chartError
                    : (hasChartRegion() ? "No charts available" : "Search for a country above")));
        if (!resultsLoading && !chartResults.isEmpty()) {
            drawQueueButtonAt(
                left,
                top + CHARTS_BULK_BUTTON_Y_OFFSET,
                areAllChartsInQueueOrPending(),
                isChartsBulkButtonAt(left, top, mouseX, mouseY) && !areAllChartsInQueueOrPending());
        }
    }

    private void drawSearchTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isSearchListLoading();
        List<SearchResult> results = displayedSearchResults();
        if (shouldDrawSearchProgressBar(resultsLoading)) {
            drawProgressBar(left, top, searchProgress);
        }
        drawResultList(
            resultsLoading ? Collections.<SearchResult>emptyList() : results,
            searchScrollOffset,
            left,
            top + searchListTopOffset(resultsLoading),
            mouseX,
            mouseY,
            resultsLoading ? "Searching..." : (searchError.length() > 0 ? searchError : "Search for songs above"));
    }

    private void drawPlaylistDiscoveryTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isPlaylistResultsLoading();
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, playlistProgress);
        }
        drawResultList(
            resultsLoading ? Collections.<SearchResult>emptyList() : playlistResults,
            playlistScrollOffset,
            left,
            playlistDiscoveryListTop(top),
            mouseX,
            mouseY,
            resultsLoading ? "Loading playlist..."
                : (playlistError.length() > 0 ? playlistError : "Paste a YouTube playlist URL"));
        if (!resultsLoading && !playlistResults.isEmpty()) {
            drawQueueButtonAt(
                left,
                top + CHARTS_BULK_BUTTON_Y_OFFSET,
                areAllPlaylistResultsInQueueOrPending(),
                isChartsBulkButtonAt(left, top, mouseX, mouseY) && !areAllPlaylistResultsInQueueOrPending());
        }
    }

    private List<SearchResult> displayedSearchResults() {
        if (searchField == null) {
            return new ArrayList<SearchResult>(searchResults);
        }
        if (!isEmptySearchQuery()) {
            return new ArrayList<SearchResult>(searchResults);
        }
        return FavoriteResultComposer.composeSongs(HorizonRadioClient.getFavoriteSongs(), chartResults);
    }

    private List<RadioStationResult> displayedRadioResults() {
        if (searchField == null) {
            return new ArrayList<RadioStationResult>(radioResults);
        }
        if (!isEmptySearchQuery()) {
            return new ArrayList<RadioStationResult>(radioResults);
        }
        return FavoriteResultComposer.composeRadios(HorizonRadioClient.getFavoriteRadios(), radioResults);
    }

    private boolean isEmptySearchQuery() {
        return searchField == null || searchField.getText()
            .trim()
            .length() == 0;
    }

    private boolean isSearchListLoading() {
        return !isEmptySearchQuery() && isSearchResultsLoading();
    }

    public void drawRadioTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isRadioResultsLoading();
        List<RadioStationResult> results = displayedRadioResults();
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, radioProgress);
        }
        int listTop = top + radioListTopOffset(resultsLoading);
        if (results.isEmpty()) {
            String emptyMessage = resultsLoading ? "Loading stations..."
                : (radioError.length() > 0 ? radioError : "No radio stations available");
            drawCenteredString(fontRendererObj, emptyMessage, left + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            return;
        }
        for (int row = 0; row < MAX_VISIBLE_ROWS && radioScrollOffset + row < results.size(); row++) {
            int y = listTop + row * ROW_HEIGHT;
            RadioStationResult station = results.get(radioScrollOffset + row);
            boolean hovered = mouseX >= left + 10 && mouseX <= left + PANEL_WIDTH - 10
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT;
            boolean active = isActiveRadioStation(station.stationUuid);
            drawRect(
                left + 10,
                y,
                left + PANEL_WIDTH - 10,
                y + ROW_HEIGHT - 2,
                active ? 0x4400FF00 : (hovered ? 0x44FFFFFF : 0x22FFFFFF));
            int textColor = active ? 0xFF55FF55 : 0xFFFFFFFF;
            int textWidth = active ? radioStationNameMaxWidth(left) : left + PANEL_WIDTH - 25 - (left + 15);
            drawString(fontRendererObj, truncate(station.name, textWidth), left + 15, y + 8, textColor);
            if (active) {
                drawString(fontRendererObj, "LIVE", radioLiveLabelLeft(left), y + 8, 0xFF55FF55);
            }
        }
        drawResultScrollbar(results.size(), radioScrollOffset, left, listTop);
    }

    private void drawResultList(List<SearchResult> results, int scrollOffset, int left, int listTop, int mouseX,
        int mouseY, String emptyMessage) {
        if (results.isEmpty()) {
            drawCenteredString(fontRendererObj, emptyMessage, left + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            return;
        }
        for (int row = 0; row < MAX_VISIBLE_ROWS && scrollOffset + row < results.size(); row++) {
            int y = listTop + row * ROW_HEIGHT;
            SearchResult result = results.get(scrollOffset + row);
            boolean hovered = mouseX >= left + 10 && mouseX <= left + PANEL_WIDTH - 10
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT;
            drawRect(left + 10, y, left + PANEL_WIDTH - 10, y + ROW_HEIGHT - 2, hovered ? 0x44FFFFFF : 0x22FFFFFF);
            int queueButtonLeft = queueButtonLeft(left);
            int durationLeft = queueButtonLeft - RESULT_DURATION_COLUMN_WIDTH;
            int textRight = durationLeft - 5;
            int textWidth = textRight - (left + 15);
            drawString(fontRendererObj, truncate(result.title, textWidth), left + 15, y + 4, 0xFFFFFFFF);
            drawString(
                fontRendererObj,
                result.duration,
                queueButtonLeft - fontRendererObj.getStringWidth(result.duration) - 5,
                y + 4,
                0xFFAAAAAA);
            drawString(fontRendererObj, truncate(result.channel, textWidth), left + 15, y + 14, 0xFF888888);
            boolean pending = isCurrentResultAddPending(result.videoId);
            drawQueueButton(
                left,
                y,
                isInQueue(result.videoId) || pending,
                isQueueButtonAt(left, y, mouseX, mouseY) && !pending);
        }
        drawResultScrollbar(results.size(), scrollOffset, left, listTop);
    }

    private void drawPlaylistTab(int left, int top, int mouseX, int mouseY) {
        int listTop = playlistListTop(top);
        drawString(
            fontRendererObj,
            "Queue (" + playlist.size() + " von " + QUEUE_DISPLAY_LIMIT + ")",
            left + 10,
            top + PLAYLIST_TITLE_Y_OFFSET,
            0xFFE0E0E0);
        if (!playlist.isEmpty()) {
            drawTextButtonAt(
                left,
                top + PLAYLIST_HEADER_Y_OFFSET,
                "X",
                isPlaylistClearButtonAt(left, top, mouseX, mouseY));
        }
        if (playlist.isEmpty()) {
            drawCenteredString(fontRendererObj, "Playlist is empty", left + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            drawCenteredString(
                fontRendererObj,
                "Search and add songs!",
                left + PANEL_WIDTH / 2,
                listTop + 35,
                0xFF666666);
            return;
        }
        for (int row = 0; row < MAX_VISIBLE_ROWS && queueScrollOffset + row < playlist.size(); row++) {
            int index = queueScrollOffset + row;
            int y = listTop + row * ROW_HEIGHT;
            PlaylistEntry entry = playlist.get(index);
            boolean hovered = mouseX >= left + 10 && mouseX <= left + PANEL_WIDTH - 10
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT;
            boolean isPlaying = isPlaylistRowPlaying(index, nowPlaying != null, isRadioActive());
            boolean isDragged = index == draggedPlaylistIndex;
            drawRect(
                left + 10,
                y,
                left + PANEL_WIDTH - 10,
                y + ROW_HEIGHT - 2,
                isPlaying ? 0x4400FF00 : (isDragged ? 0x6688AAFF : (hovered ? 0x44FFFFFF : 0x22FFFFFF)));
            drawString(fontRendererObj, (index + 1) + ".", left + 15, y + 8, 0xFFAAAAAA);
            boolean canRemove = true;
            int titleWidth = queueButtonLeft(left) - 5 - (left + 35);
            drawString(fontRendererObj, truncate(entry.displayTitle(), titleWidth), left + 35, y + 4, 0xFFFFFFFF);
            drawString(fontRendererObj, "by " + entry.addedBy, left + 35, y + 14, 0xFF888888);
            if (canRemove) {
                int removeY = queueButtonTop(y);
                drawTextButtonAt(
                    left,
                    removeY,
                    "X",
                    isMouseOver(
                        queueButtonLeft(left),
                        removeY,
                        QUEUE_BUTTON_WIDTH,
                        QUEUE_BUTTON_HEIGHT,
                        mouseX,
                        mouseY));
            }
        }
        if (playlistDragMoved) {
            int dropIndex = playlistIndexAt(mouseX, mouseY);
            if (dropIndex >= 0 && isPlaylistDropAllowed(dropIndex)) {
                int dropRow = dropIndex - queueScrollOffset;
                if (dropRow >= 0 && dropRow < MAX_VISIBLE_ROWS) {
                    int dropY = listTop + dropRow * ROW_HEIGHT;
                    drawRect(left + 8, dropY, left + PANEL_WIDTH - 8, dropY + 2, 0xFF55AAFF);
                }
            }
        }
        drawResultScrollbar(playlist.size(), queueScrollOffset, left, listTop);
    }

    private void drawNowPlaying(int left, int y) {
        drawRect(left, y, left + PANEL_WIDTH, y + 25, 0x60000000);
        boolean radioActive = isRadioActive();
        boolean pausedRadio = !radioActive && isPausedRadio();
        if (radioActive || pausedRadio) {
            String radioLabel = radioNowPlayingDisplayLabel(nowPlaying, radioActive || pausedRadio);
            drawString(fontRendererObj, truncate(radioLabel, PANEL_WIDTH - 40), left + 10, y + 8, 0xFF55FF55);
        }
        if (!shouldDrawPlaybackProgress(radioActive, pausedRadio)) {
            return;
        }
        if (!isRadioActive() && hasRadioStatus()) {
            drawString(
                fontRendererObj,
                "Radio: " + truncate(radioStatus(), PANEL_WIDTH - 40),
                left + 10,
                y + 8,
                0xFFFF7777);
            return;
        }
        if (nowPlaying == null) {
            drawString(fontRendererObj, "Nothing playing", left + 10, y + 8, 0xFF666666);
            return;
        }
        drawString(fontRendererObj, "\u266A " + truncate(nowPlaying, PANEL_WIDTH - 40), left + 10, y + 4, 0xFF55FF55);
        int barLeft = left + TIME_BAR_SIDE_SPACE;
        int barWidth = PANEL_WIDTH - 2 * TIME_BAR_SIDE_SPACE;
        int barTop = y + 18;
        float displayedProgress = seeking ? seekProgress : playbackProgress;
        long totalMillis = DurationParser.parseMillisStrict(currentDuration);
        long elapsedMillis = totalMillis < 0L ? 0L : Math.min(totalMillis, (long) (totalMillis * displayedProgress));
        drawString(fontRendererObj, formatTime(elapsedMillis), left + 10, barTop - 2, 0xFFE0E0E0);
        drawString(
            fontRendererObj,
            totalMillis < 0L ? "--:--" : formatTime(totalMillis),
            barLeft + barWidth + 5,
            barTop - 2,
            0xFFE0E0E0);
        drawRect(barLeft, barTop, barLeft + barWidth, barTop + 3, 0x44FFFFFF);
        drawRect(barLeft, barTop, barLeft + (int) (barWidth * displayedProgress), barTop + 3, 0xFF55FF55);
        int knobX = barLeft + (int) ((barWidth - 1) * displayedProgress);
        drawRect(knobX - 2, barTop - 1, knobX + 2, barTop + 4, 0xFFFFFFFF);
    }

    private void addControlButtons(int panelLeft, int panelTop) {
        int controlLeft = panelLeft + (PANEL_WIDTH - controlGroupWidth()) / 2;
        int controlTop = controlTop(nowPlayingTop(panelTop));
        shuffleButton = new ControlButton(
            4,
            controlLeft,
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            ICON_SHUFFLE);
        shuffleButton.setActive(HorizonRadioClient.isShuffling());
        addButton(shuffleButton);
        previousButton = new ControlButton(
            5,
            controlLeft + CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP,
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            ICON_PREVIOUS);
        addButton(previousButton);
        playbackButton = new ControlButton(
            6,
            controlLeft + 2 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            HorizonRadioClient.isPaused() ? ICON_PLAY : ICON_PAUSE);
        addButton(playbackButton);
        nextButton = new ControlButton(
            7,
            controlLeft + 3 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            ICON_NEXT);
        addButton(nextButton);
        loopButton = new ControlButton(
            8,
            controlLeft + 4 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            ICON_LOOP);
        loopButton.setActive(HorizonRadioClient.isLooping());
        addButton(loopButton);
        favoriteButton = new ControlButton(
            BUTTON_FAVORITE,
            controlLeft + 5 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            "\u2605");
        addButton(favoriteButton);
        updateFavoriteState();
    }

    @SuppressWarnings("unchecked")
    private void addButton(GuiButton button) {
        buttonList.add(button);
    }

    private void drawControlCenter(int left, int nowPlayingTop) {
        int groupWidth = controlGroupWidth();
        int controlLeft = left + (PANEL_WIDTH - groupWidth) / 2;
        int controlTop = controlTop(nowPlayingTop);
        drawRect(
            controlLeft - 3,
            controlTop - 2,
            controlLeft + groupWidth + 3,
            controlTop + CONTROL_BUTTON_HEIGHT + 2,
            0xCC000000);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BUTTON_SEARCH) {
            if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                performPlaylistImport();
            } else {
                performSearch();
            }
        } else if (button.id == BUTTON_CHARTS_TAB) {
            openCharts();
        } else if (button.id == BUTTON_SEARCH_TAB) {
            currentTab = SEARCH_TAB;
        } else if (button.id == BUTTON_PLAYLIST_TAB) {
            currentTab = PLAYLIST_TAB;
        } else if (button.id == BUTTON_PLAYLIST_DISCOVERY_TAB) {
            currentTab = PLAYLIST_DISCOVERY_TAB;
        } else if (button.id == BUTTON_RADIO_TAB) {
            openRadio();
        } else if (button.id == BUTTON_REFRESH_CHARTS && !isChartRefreshBusy()) {
            beginChartLoading();
            HorizonRadioClient.sendChartsRequest(chartRegionCode, true);
        } else if (button.id == BUTTON_FAVORITE) {
            HorizonRadioClient.toggleCurrentFavorite();
            updateFavoriteState();
        } else if (button.id == 6) {
            if (isRadioActive()) {
                HorizonRadioClient.sendStopRadio();
            } else if (canResumeRadio()) {
                HorizonRadioClient.sendSelectRadio(radioState.getStationUuid());
            } else if (currentTab != RADIO_TAB) {
                HorizonRadioClient.sendTogglePlayback();
            }
        } else if (button.id == 7 && !radioControlsLocked()) {
            HorizonRadioClient.sendSkipTrack();
        } else if (button.id == 5 && !radioControlsLocked()) {
            HorizonRadioClient.sendPreviousTrack();
        } else if (button.id == 8 && !radioControlsLocked()) {
            HorizonRadioClient.sendToggleLoop();
        } else if (button.id == 4 && !radioControlsLocked()) {
            HorizonRadioClient.sendToggleShuffle();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (usesSharedSearchField() && searchField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (currentTab == PLAYLIST_DISCOVERY_TAB && playlistUrlField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (usesSharedSearchField() && keyCode == Keyboard.KEY_RETURN && searchField.isFocused()) {
            performSearch();
            return;
        }
        if (currentTab == PLAYLIST_DISCOVERY_TAB && keyCode == Keyboard.KEY_RETURN && playlistUrlField.isFocused()) {
            performPlaylistImport();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** String overload retained for callers ported from the active Fabric screen. */
    public void keyTyped(String typedChar, int keyCode) {
        if (typedChar != null && typedChar.length() > 0) {
            keyTyped(typedChar.charAt(0), keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && currentTab == RADIO_TAB) {
            List<RadioStationResult> results = displayedRadioResults();
            int listTop = panelTop() + radioListTopOffset(isRadioResultsLoading());
            if (isResultScrollbarAt(panelLeft(), listTop, results.size(), mouseX, mouseY)) {
                int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
                int thumbHeight = resultScrollbarThumbHeight(results.size(), trackHeight);
                int thumbTop = resultScrollbarThumbTop(
                    results.size(),
                    radioScrollOffset,
                    listTop,
                    trackHeight,
                    thumbHeight);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, listTop);
            if (row >= 0 && row < results.size() - radioScrollOffset) {
                HorizonRadioClient.sendSelectRadio(results.get(radioScrollOffset + row).stationUuid);
                return;
            }
        }
        if (button == 0 && isSongResultTab(currentTab)) {
            boolean charts = currentTab == CHARTS_TAB;
            boolean playlistDiscovery = currentTab == PLAYLIST_DISCOVERY_TAB;
            if ((charts || playlistDiscovery) && isChartsBulkButtonAt(panelLeft(), panelTop(), mouseX, mouseY)) {
                if (charts && areAllChartsInQueue()) {
                    HorizonRadioClient.sendAddChartsToPlaylist(toPlaylistSelections(chartResults), true);
                } else if (playlistDiscovery && areAllPlaylistResultsInQueue()) {
                    HorizonRadioClient.sendAddChartsToPlaylist(toPlaylistSelections(playlistResults), true);
                } else {
                    List<SearchResult> request = charts ? beginChartAdd(chartResults)
                        : beginPlaylistAdd(playlistResults);
                    if (!request.isEmpty()) {
                        if (charts) {
                            HorizonRadioClient.sendAddChartsToPlaylist(request);
                        } else {
                            HorizonRadioClient.sendPlaylistResultsToQueue(request);
                        }
                    }
                }
                return;
            }
            List<SearchResult> results = charts ? chartResults
                : (playlistDiscovery ? playlistResults : displayedSearchResults());
            int scrollOffset = charts ? chartScrollOffset
                : (playlistDiscovery ? playlistScrollOffset : searchScrollOffset);
            int listTop = playlistDiscovery ? playlistDiscoveryListTop(panelTop()) : resultListTop(panelTop());
            if (isResultScrollbarAt(panelLeft(), listTop, results.size(), mouseX, mouseY)) {
                int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
                int thumbHeight = resultScrollbarThumbHeight(results.size(), trackHeight);
                int thumbTop = resultScrollbarThumbTop(results.size(), scrollOffset, listTop, trackHeight, thumbHeight);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, listTop);
            if (row >= 0 && row < results.size() - scrollOffset) {
                SearchResult result = results.get(scrollOffset + row);
                int rowTop = listTop + row * ROW_HEIGHT;
                if (isQueueButtonAt(panelLeft(), rowTop, mouseX, mouseY)) {
                    if ((charts && isChartAddPending(result.videoId))
                        || (playlistDiscovery && isPlaylistAddPending(result.videoId))) {
                        return;
                    }
                    if (isInQueue(result.videoId)) {
                        HorizonRadioClient.sendRemove(result.videoId);
                    } else {
                        if (charts) {
                            List<SearchResult> request = beginChartAdd(Collections.singletonList(result));
                            if (!request.isEmpty()) {
                                HorizonRadioClient.sendAddChartsToPlaylist(request);
                            }
                        } else if (playlistDiscovery) {
                            List<SearchResult> request = beginPlaylistAdd(Collections.singletonList(result));
                            if (!request.isEmpty()) {
                                HorizonRadioClient.sendPlaylistResultsToQueue(request);
                            }
                        } else {
                            sendResultToQueue(result, false);
                        }
                    }
                    return;
                }
                playResultNow(result);
                currentTab = PLAYLIST_TAB;
                return;
            }
        }
        if (button == 0 && currentTab == PLAYLIST_TAB) {
            if (isPlaylistClearButtonAt(panelLeft(), panelTop(), mouseX, mouseY)) {
                HorizonRadioClient.sendClearPlaylist();
                return;
            }
            if (isResultScrollbarAt(panelLeft(), playlistListTop(panelTop()), playlist.size(), mouseX, mouseY)) {
                int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
                int thumbHeight = resultScrollbarThumbHeight(playlist.size(), trackHeight);
                int thumbTop = resultScrollbarThumbTop(
                    playlist.size(),
                    queueScrollOffset,
                    playlistListTop(panelTop()),
                    trackHeight,
                    thumbHeight);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, playlistListTop(panelTop()));
            if (row >= 0 && row < playlist.size() - queueScrollOffset) {
                PlaylistEntry entry = playlist.get(queueScrollOffset + row);
                if (isMouseOver(
                    queueButtonLeft(panelLeft()),
                    queueButtonTop(playlistListTop(panelTop()) + row * ROW_HEIGHT),
                    QUEUE_BUTTON_WIDTH,
                    QUEUE_BUTTON_HEIGHT,
                    mouseX,
                    mouseY)) {
                    HorizonRadioClient.sendRemove(entry.sourceId);
                    return;
                }
                int playlistIndex = queueScrollOffset + row;
                draggedPlaylistIndex = playlistIndex;
                draggedPlaylistEntry = entry;
                playlistDragMoved = false;
                dragStartMouseX = mouseX;
                dragStartMouseY = mouseY;
                return;
            }
        }
        if (button == 0 && nowPlaying != null && isTimeBarAt(mouseX, mouseY)) {
            seeking = true;
            seekProgress = seekProgressAt(mouseX);
            return;
        }
        if (usesSharedSearchField()) {
            searchField.mouseClicked(mouseX, mouseY, button);
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            playlistUrlField.mouseClicked(mouseX, mouseY, button);
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            if (currentTab == CHARTS_TAB) {
                chartScrollOffset = scroll(chartScrollOffset, chartResults.size(), wheel);
            } else if (currentTab == SEARCH_TAB) {
                searchScrollOffset = scroll(searchScrollOffset, displayedSearchResults().size(), wheel);
            } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                playlistScrollOffset = scroll(playlistScrollOffset, playlistResults.size(), wheel);
            } else if (currentTab == RADIO_TAB) {
                radioScrollOffset = scroll(radioScrollOffset, displayedRadioResults().size(), wheel);
            } else {
                queueScrollOffset = scroll(queueScrollOffset, playlist.size(), wheel);
            }
        }
        super.handleMouseInput();
    }

    /** Forge 1.7.10-compatible drag hook retained for slider/input integrations. */
    protected void mouseDragged(Minecraft minecraft, int mouseX, int mouseY) {
        updatePlaylistDrag(mouseX, mouseY);
        if (volumeSlider != null) {
            volumeSlider.mouseDragged(minecraft, mouseX, mouseY);
        }
    }

    /** Forge 1.7.10 dispatches held-mouse movement through this hook. */
    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceClick) {
        if (clickedMouseButton == 0) {
            if (draggingResultScrollbar) {
                updateResultScrollbarScroll(mouseY);
                return;
            }
            if (seeking) {
                seekProgress = seekProgressAt(mouseX);
            } else {
                updatePlaylistDrag(mouseX, mouseY);
            }
            if (volumeSlider != null) {
                volumeSlider.mouseDragged(mc, mouseX, mouseY);
            }
        }
    }

    private void updatePlaylistDrag(int mouseX, int mouseY) {
        if (draggedPlaylistIndex >= 0) {
            if (mouseX != dragStartMouseX || mouseY != dragStartMouseY) {
                playlistDragMoved = true;
            }
        }
    }

    /** Forge 1.7.10 release hook for slider/input integrations. */
    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0) {
            draggingResultScrollbar = false;
        }
        if (state == 0 && seeking) {
            float requestedProgress = seekProgress;
            seeking = false;
            HorizonRadioClient.sendSeek(requestedProgress);
        }
        if (state == 0 && draggedPlaylistIndex >= 0) {
            int fromIndex = draggedPlaylistIndex;
            int targetIndex = playlistIndexAt(mouseX, mouseY);
            PlaylistEntry clickedEntry = draggedPlaylistEntry;
            boolean shouldPlay = !playlistDragMoved && targetIndex == fromIndex && clickedEntry != null;
            boolean shouldSendReorder = playlistDragMoved && isPlaylistIndexDraggable(fromIndex)
                && targetIndex >= 0
                && targetIndex != fromIndex
                && isPlaylistDropAllowed(targetIndex);
            draggedPlaylistIndex = -1;
            draggedPlaylistEntry = null;
            playlistDragMoved = false;
            if (shouldSendReorder) {
                HorizonRadioClient.sendReorder(fromIndex, targetIndex);
            } else if (shouldPlay && clickedEntry.isFinite()) {
                HorizonRadioClient.sendPlayNow(clickedEntry.sourceId, clickedEntry.durationMs);
            }
        }
        if (volumeSlider != null) {
            volumeSlider.mouseReleased(mouseX, mouseY);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void performSearch() {
        String query = searchField.getText()
            .trim();
        if (currentTab == CHARTS_TAB) {
            requestChartSearch(query);
            return;
        }
        if (query.length() == 0) {
            if (currentTab == RADIO_TAB) {
                requestRadioSearch(query);
            } else if (currentTab == SEARCH_TAB) {
                searchError = "";
                searchLoading = false;
                searchResultsRevealPending = false;
                searchScrollOffset = 0;
            } else {
                openCharts();
            }
            return;
        }
        if (currentTab == RADIO_TAB) {
            requestRadioSearch(query);
        } else {
            requestSearch(query);
        }
    }

    private void performPlaylistImport() {
        HorizonRadioClient.sendPlaylistImport(playlistUrlField.getText());
    }

    private void openCharts() {
        currentTab = CHARTS_TAB;
        if (!hasChartRegion()) {
            chartLoading = false;
            updateChartRefreshButtonState();
            return;
        }
        if (chartResults.isEmpty() && !HorizonRadioClient.isChartRequestPending()) {
            beginChartLoading();
            HorizonRadioClient.sendChartsRequest(chartRegionCode, false);
        }
    }

    private void requestChartSearch(String query) {
        if (query.length() == 0) {
            chartSearchMessage = "";
            return;
        }
        ChartRegion region = ChartRegionCatalog.resolve(query);
        if (region == null) {
            chartSearchMessage = ChartRegionCatalog.isAmbiguous(query) ? "Chart region is ambiguous; use an ISO code."
                : "Unknown chart region.";
            return;
        }
        if (ChartRegionCatalog.GLOBAL_CODE.equals(region.getCode())) {
            chartSearchMessage = "Global charts are not available; search for a country.";
            return;
        }
        chartSearchMessage = "";
        chartRegionCode = region.getCode();
        chartResults.clear();
        chartScrollOffset = 0;
        beginChartLoading();
        HorizonRadioClient.sendChartsRequest(chartRegionCode, false);
    }

    public void openRadio() {
        currentTab = RADIO_TAB;
        if (!radioPopularRequested) {
            radioPopularRequested = true;
            requestRadioSearch("");
        }
    }

    private void requestSearch(String query) {
        searchResults.clear();
        searchScrollOffset = 0;
        searchResultsRevealPending = false;
        currentTab = SEARCH_TAB;
        searchProgress = 0.02f;
        searchLoading = true;
        searchStartedAt = System.currentTimeMillis();
        if (looksLikePlaylistUrl(query)) {
            HorizonRadioClient.sendImportPlaylist(query);
        } else if (looksLikeVideoUrl(query)) {
            HorizonRadioClient.sendImportVideo(query);
        } else {
            HorizonRadioClient.sendSearch(query);
        }
    }

    private void requestRadioSearch(String query) {
        radioResults.clear();
        radioScrollOffset = 0;
        radioResultsRevealPending = false;
        radioProgress = 0.02f;
        radioLoading = true;
        radioStartedAt = System.currentTimeMillis();
        HorizonRadioClient.sendRadioSearch(query);
    }

    public void updateSearchResults(List<SearchResult> results) {
        boolean requestWasLoading = searchLoading;
        searchResults = results == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(results);
        searchScrollOffset = 0;
        searchLoading = false;
        searchError = "";
        searchProgress = 1.0f;
        scheduleSearchResultsReveal(requestWasLoading);
    }

    public void updateChartResults(List<SearchResult> results) {
        updateChartResults(results, chartRegionCode);
    }

    public void updateChartResults(List<SearchResult> results, String regionCode) {
        boolean requestWasLoading = chartLoading;
        chartRegionCode = normalizeChartRegionCode(regionCode);
        chartSearchMessage = "";
        chartResults = results == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(results);
        chartScrollOffset = 0;
        chartLoading = false;
        chartError = "";
        chartProgress = 1.0f;
        scheduleChartResultsReveal(requestWasLoading);
        updateChartRefreshButtonState();
    }

    void updateChartDuration(String videoId, String duration) {
        if (videoId == null || duration == null) {
            return;
        }
        for (int index = 0; index < chartResults.size(); index++) {
            SearchResult result = chartResults.get(index);
            if (result != null && videoId.equals(result.videoId) && !duration.equals(result.duration)) {
                chartResults.set(
                    index,
                    new SearchResult(result.videoId, result.title, result.channel, duration, result.thumbnail));
            }
        }
    }

    void updatePlaylistResultDuration(String videoId, String duration) {
        if (videoId == null || duration == null) {
            return;
        }
        updateResultDuration(playlistResults, videoId, duration);
    }

    private void updateResultDuration(List<SearchResult> results, String videoId, String duration) {
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            if (result != null && videoId.equals(result.videoId) && !duration.equals(result.duration)) {
                results.set(
                    index,
                    new SearchResult(result.videoId, result.title, result.channel, duration, result.thumbnail));
            }
        }
    }

    public void updateRadioResults(List<RadioStationResult> results) {
        boolean requestWasLoading = radioLoading;
        radioResults = results == null ? new ArrayList<RadioStationResult>()
            : new ArrayList<RadioStationResult>(results);
        radioScrollOffset = 0;
        radioLoading = false;
        radioError = "";
        radioProgress = 1.0f;
        scheduleRadioResultsReveal(requestWasLoading);
    }

    private void scheduleSearchResultsReveal(boolean requestWasLoading) {
        if (requestWasLoading) {
            searchResultsRevealPending = true;
            searchResultsRevealAt = System.currentTimeMillis() + RESULT_REVEAL_DELAY_MILLIS;
        }
    }

    private void scheduleChartResultsReveal(boolean requestWasLoading) {
        if (requestWasLoading) {
            chartResultsRevealPending = true;
            chartResultsRevealAt = System.currentTimeMillis() + RESULT_REVEAL_DELAY_MILLIS;
        }
    }

    private void schedulePlaylistResultsReveal(boolean requestWasLoading) {
        if (requestWasLoading) {
            playlistResultsRevealPending = true;
            playlistResultsRevealAt = System.currentTimeMillis() + RESULT_REVEAL_DELAY_MILLIS;
        }
    }

    private void scheduleRadioResultsReveal(boolean requestWasLoading) {
        if (requestWasLoading) {
            radioResultsRevealPending = true;
            radioResultsRevealAt = System.currentTimeMillis() + RESULT_REVEAL_DELAY_MILLIS;
        }
    }

    private void updatePendingResultReveals() {
        long now = System.currentTimeMillis();
        if (searchResultsRevealPending && shouldRevealResults(now, searchResultsRevealAt)) {
            searchResultsRevealPending = false;
        }
        if (chartResultsRevealPending && shouldRevealResults(now, chartResultsRevealAt)) {
            chartResultsRevealPending = false;
        }
        if (playlistResultsRevealPending && shouldRevealResults(now, playlistResultsRevealAt)) {
            playlistResultsRevealPending = false;
        }
        if (radioResultsRevealPending && shouldRevealResults(now, radioResultsRevealAt)) {
            radioResultsRevealPending = false;
        }
    }

    void updateRadioResultsFromStations(List<RadioStation> stations) {
        List<RadioStationResult> results = new ArrayList<RadioStationResult>();
        if (stations != null) {
            for (RadioStation station : stations) {
                if (station != null) {
                    results.add(new RadioStationResult(station.getStationUuid(), station.getName()));
                }
            }
        }
        updateRadioResults(results);
    }

    void updateRadioPresentation(ClientRadioPresentation presentation) {
        radioState = presentation;
        if (isRadioActive()) {
            nowPlaying = presentation.getStationName();
            currentDuration = "";
        } else {
            String cachedNowPlaying = HorizonRadioClient.getCachedNowPlaying();
            if (!isMusicMode() && hasResumableRadioStation()
                && (cachedNowPlaying == null || cachedNowPlaying.length() == 0)) {
                nowPlaying = presentation == null ? "" : presentation.getStationName();
                currentDuration = "";
            } else {
                nowPlaying = cachedNowPlaying;
                refreshCurrentDuration();
            }
        }
        updateControlVisibility();
    }

    public void beginChartLoading() {
        chartResultsRevealPending = false;
        chartLoading = true;
        chartError = "";
        chartProgress = 0.02f;
        chartStartedAt = System.currentTimeMillis();
        updateChartRefreshButtonState();
    }

    public void beginPlaylistLoading() {
        playlistResultsRevealPending = false;
        playlistLoading = true;
        playlistError = "";
        playlistProgress = 0.02f;
        playlistStartedAt = System.currentTimeMillis();
    }

    public void updatePlaylistResults(List<SearchResult> results) {
        boolean requestWasLoading = playlistLoading;
        playlistResults = results == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(results);
        playlistScrollOffset = 0;
        playlistLoading = false;
        playlistError = "";
        playlistProgress = 1.0f;
        schedulePlaylistResultsReveal(requestWasLoading);
    }

    public void showPlaylistError(String message) {
        playlistLoading = false;
        playlistResultsRevealPending = false;
        playlistError = message == null ? "" : message;
        playlistProgress = 1.0f;
    }

    void showSearchError() {
        updateSearchResults(new ArrayList<SearchResult>());
        searchError = "Search failed";
    }

    void showChartError() {
        updateChartResults(new ArrayList<SearchResult>(), chartRegionCode);
        chartError = "Charts failed to load";
    }

    void showRadioError() {
        updateRadioResults(new ArrayList<RadioStationResult>());
        radioError = "Radio search failed";
    }

    String getChartRegionCode() {
        return chartRegionCode;
    }

    String getChartSearchMessage() {
        return chartSearchMessage;
    }

    static boolean shouldEnableChartRefreshButton(boolean chartLoading, boolean chartRequestPending) {
        return !chartLoading && !chartRequestPending;
    }

    static boolean shouldEnableChartRefreshButton(boolean chartLoading, boolean chartRequestPending,
        boolean hasChartRegion) {
        return hasChartRegion && shouldEnableChartRefreshButton(chartLoading, chartRequestPending);
    }

    static String chartHeaderLabel(boolean hasRegion, String regionDisplayName) {
        return hasRegion ? "Top 50 Charts " + regionDisplayName + " (Weekly)" : "";
    }

    static boolean shouldRevealResults(long now, long revealAt) {
        return now >= revealAt;
    }

    static long resultRevealDelayMillis() {
        return RESULT_REVEAL_DELAY_MILLIS;
    }

    static boolean shouldDrawSearchProgressBar(boolean searchLoading) {
        return shouldDrawProgressBar(searchLoading);
    }

    static boolean shouldDrawProgressBar(boolean loading) {
        return loading;
    }

    static long progressEstimateMillis(int tab) {
        if (tab == CHARTS_TAB) {
            return CHART_PROGRESS_ESTIMATE_MILLIS;
        }
        if (tab == RADIO_TAB) {
            return RADIO_PROGRESS_ESTIMATE_MILLIS;
        }
        return SEARCH_PROGRESS_ESTIMATE_MILLIS;
    }

    static int searchListTopOffset(boolean searchLoading) {
        return searchLoading ? SEARCH_LIST_TOP_OFFSET : SEARCH_LIST_TOP_WITHOUT_PROGRESS_OFFSET;
    }

    static int radioListTopOffset(boolean radioLoading) {
        return radioLoading ? CONTENT_LIST_TOP_OFFSET : SEARCH_LIST_TOP_WITHOUT_PROGRESS_OFFSET;
    }

    boolean hasSearchResultsRevealPending() {
        return searchResultsRevealPending;
    }

    private boolean isSearchResultsLoading() {
        return searchLoading || searchResultsRevealPending;
    }

    private boolean isChartResultsLoading() {
        return chartLoading || chartResultsRevealPending;
    }

    private boolean isRadioResultsLoading() {
        return radioLoading || radioResultsRevealPending;
    }

    private boolean isPlaylistResultsLoading() {
        return playlistLoading || playlistResultsRevealPending;
    }

    private boolean isChartRefreshBusy() {
        return !shouldEnableChartRefreshButton(
            chartLoading,
            HorizonRadioClient.isChartRequestPending(),
            hasChartRegion());
    }

    private void updateChartRefreshButtonState() {
        if (refreshChartsButton != null) {
            refreshChartsButton.enabled = !isChartRefreshBusy();
        }
    }

    public void updatePlaylist(List<PlaylistEntry> entries) {
        playlist = entries == null ? new ArrayList<PlaylistEntry>() : new ArrayList<PlaylistEntry>(entries);
        for (PlaylistEntry entry : playlist) {
            if (entry != null) {
                pendingChartAdds.remove(entry.sourceId);
                pendingPlaylistAdds.remove(entry.sourceId);
            }
        }
        refreshCurrentDuration();
        queueScrollOffset = Math.min(queueScrollOffset, Math.max(0, playlist.size() - MAX_VISIBLE_ROWS));
        if (draggedPlaylistIndex >= playlist.size()) {
            draggedPlaylistIndex = -1;
            draggedPlaylistEntry = null;
            playlistDragMoved = false;
        }
    }

    void completeChartAdds(List<String> videoIds) {
        if (videoIds != null) {
            pendingChartAdds.removeAll(videoIds);
        }
    }

    void completePlaylistAdds(List<String> videoIds) {
        if (videoIds != null) {
            pendingPlaylistAdds.removeAll(videoIds);
        }
    }

    List<SearchResult> beginChartAdd(List<SearchResult> results) {
        return beginPendingAdd(results, pendingChartAdds);
    }

    List<SearchResult> beginPlaylistAdd(List<SearchResult> results) {
        return beginPendingAdd(results, pendingPlaylistAdds);
    }

    private List<SearchResult> beginPendingAdd(List<SearchResult> results, Set<String> pendingAdds) {
        List<SearchResult> request = new ArrayList<SearchResult>();
        if (results == null) {
            return request;
        }
        for (SearchResult result : results) {
            if (result == null || result.videoId == null
                || result.videoId.length() == 0
                || isInQueue(result.videoId)
                || !pendingAdds.add(result.videoId)) {
                continue;
            }
            request.add(result);
        }
        return request;
    }

    boolean isChartAddPending(String videoId) {
        return videoId != null && pendingChartAdds.contains(videoId);
    }

    boolean isPlaylistAddPending(String videoId) {
        return videoId != null && pendingPlaylistAdds.contains(videoId);
    }

    static String chartQueueButtonLabel(boolean inQueue, boolean pending) {
        return inQueue || pending ? "-" : "+";
    }

    boolean isInQueue(String videoId) {
        if (videoId == null) {
            return false;
        }
        for (PlaylistEntry entry : playlist) {
            if (videoId.equals(entry.sourceId)) {
                return true;
            }
        }
        return false;
    }

    public void updateNowPlaying(String title, float progress) {
        nowPlaying = title == null || title.length() == 0 ? null : title;
        refreshCurrentDuration();
        if (!seeking) {
            playbackProgress = Math.max(0.0f, Math.min(1.0f, progress));
        }
        updateFavoriteState();
    }

    public void updatePlaybackPaused(boolean paused) {
        if (playbackButton != null && !isRadioActive()) {
            playbackButton.setIcon(paused ? ICON_PLAY : ICON_PAUSE);
        }
        updateFavoriteState();
    }

    public void updateLooping(boolean looping) {
        if (loopButton != null) {
            loopButton.setActive(looping);
        }
    }

    public void updateShuffling(boolean shuffling) {
        if (shuffleButton != null) {
            shuffleButton.setActive(shuffling);
        }
    }

    void updateFavoriteState() {
        if (favoriteButton != null) {
            favoriteButton.enabled = HorizonRadioClient.hasCurrentFavoriteSource();
            favoriteButton.setActive(HorizonRadioClient.isCurrentSourceFavorite());
        }
    }

    @Override
    public void onGuiClosed() {
        seeking = false;
        draggingResultScrollbar = false;
        draggedPlaylistIndex = -1;
        draggedPlaylistEntry = null;
        playlistDragMoved = false;
        clearActiveScreen(this);
    }

    static synchronized void setActiveScreen(HorizonRadioScreen screen) {
        activeScreen = screen;
    }

    static void clearActiveScreen(HorizonRadioScreen screen) {
        boolean closed = false;
        synchronized (HorizonRadioScreen.class) {
            if (activeScreen == screen) {
                activeScreen = null;
                closed = true;
            }
        }
        if (closed) {
            HorizonRadioClient.onChartScreenClosed(screen);
            HorizonRadioClient.onPlaylistScreenClosed(screen);
        }
    }

    static synchronized HorizonRadioScreen getActiveScreen() {
        return activeScreen;
    }

    List<PlaylistEntry> getPlaylistSnapshot() {
        return new ArrayList<PlaylistEntry>(playlist);
    }

    List<SearchResult> getPlaylistResultsSnapshot() {
        return new ArrayList<SearchResult>(playlistResults);
    }

    String getNowPlayingSnapshot() {
        return nowPlaying;
    }

    float getPlaybackProgressSnapshot() {
        return playbackProgress;
    }

    private int panelLeft() {
        return width / 2 - PANEL_WIDTH / 2;
    }

    private int panelTop() {
        return height / 2 - PANEL_HEIGHT / 2;
    }

    private int rowAt(int mouseX, int mouseY, int listTop) {
        if (mouseX < panelLeft() + 10 || mouseX > panelLeft() + PANEL_WIDTH - 10) {
            return -1;
        }
        int row = (mouseY - listTop) / ROW_HEIGHT;
        return mouseY >= listTop && row >= 0 && row < MAX_VISIBLE_ROWS ? row : -1;
    }

    private boolean isTimeBarAt(int mouseX, int mouseY) {
        if (isRadioActive()) {
            return false;
        }
        int left = panelLeft() + TIME_BAR_SIDE_SPACE;
        int right = left + timeBarWidth();
        int top = timeBarTop(panelTop()) - 2;
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= top + 7;
    }

    private float seekProgressAt(int mouseX) {
        int left = panelLeft() + TIME_BAR_SIDE_SPACE;
        int width = timeBarWidth();
        return Math.max(0.0f, Math.min(1.0f, (float) (mouseX - left) / (float) Math.max(1, width - 1)));
    }

    private int timeBarWidth() {
        return PANEL_WIDTH - 2 * TIME_BAR_SIDE_SPACE;
    }

    private void refreshCurrentDuration() {
        currentDuration = "";
        if (nowPlaying == null) {
            return;
        }
        for (PlaylistEntry entry : playlist) {
            if (nowPlaying.equals(entry.displayTitle())) {
                currentDuration = entry.displayDuration();
                return;
            }
        }
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        }
        return minutes + ":" + twoDigits(seconds);
    }

    private static String twoDigits(long value) {
        return value < 10L ? "0" + value : String.valueOf(value);
    }

    private int nowPlayingTop(int top) {
        return top + PANEL_HEIGHT - CONTROL_CENTER_HEIGHT - NOW_PLAYING_HEIGHT - 5;
    }

    private int timeBarTop(int top) {
        return nowPlayingTop(top) + 18;
    }

    private int controlTop(int nowPlayingTop) {
        return nowPlayingTop + NOW_PLAYING_HEIGHT + 4;
    }

    private int controlGroupWidth() {
        return CONTROL_BUTTON_COUNT * CONTROL_BUTTON_WIDTH + (CONTROL_BUTTON_COUNT - 1) * CONTROL_BUTTON_GAP;
    }

    private int queueButtonLeft(int panelLeft) {
        int columnLeft = panelLeft + PANEL_WIDTH - QUEUE_BUTTON_RIGHT_MARGIN - QUEUE_BUTTON_COLUMN_WIDTH;
        return columnLeft + (QUEUE_BUTTON_COLUMN_WIDTH - QUEUE_BUTTON_WIDTH) / 2;
    }

    private int queueButtonTop(int rowTop) {
        return rowTop + (ROW_HEIGHT - QUEUE_BUTTON_HEIGHT) / 2;
    }

    private int queueButtonTextTop(int buttonTop) {
        return buttonTop + (QUEUE_BUTTON_HEIGHT - 8) / 2 + 1;
    }

    private boolean isQueueButtonAt(int panelLeft, int rowTop, int mouseX, int mouseY) {
        return isMouseOver(
            queueButtonLeft(panelLeft),
            queueButtonTop(rowTop),
            QUEUE_BUTTON_WIDTH,
            QUEUE_BUTTON_HEIGHT,
            mouseX,
            mouseY);
    }

    private boolean isChartsBulkButtonAt(int panelLeft, int panelTop, int mouseX, int mouseY) {
        return isMouseOver(
            queueButtonLeft(panelLeft),
            panelTop + CHARTS_BULK_BUTTON_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            QUEUE_BUTTON_HEIGHT,
            mouseX,
            mouseY);
    }

    private boolean isPlaylistClearButtonAt(int panelLeft, int panelTop, int mouseX, int mouseY) {
        return !playlist.isEmpty() && isMouseOver(
            queueButtonLeft(panelLeft),
            panelTop + PLAYLIST_HEADER_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            QUEUE_BUTTON_HEIGHT,
            mouseX,
            mouseY);
    }

    private void drawResultScrollbar(int resultCount, int scrollOffset, int panelLeft, int listTop) {
        if (resultCount <= MAX_VISIBLE_ROWS) {
            return;
        }
        int trackLeft = panelLeft + PANEL_WIDTH - RESULT_SCROLLBAR_LEFT_OFFSET;
        int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight);
        int thumbTop = resultScrollbarThumbTop(resultCount, scrollOffset, listTop, trackHeight, thumbHeight);
        drawRect(trackLeft, listTop, trackLeft + RESULT_SCROLLBAR_WIDTH, listTop + trackHeight, 0x66555555);
        drawRect(trackLeft, thumbTop, trackLeft + RESULT_SCROLLBAR_WIDTH, thumbTop + thumbHeight, 0xFFDDDDDD);
    }

    private boolean isResultScrollbarAt(int panelLeft, int listTop, int resultCount, int mouseX, int mouseY) {
        if (resultCount <= MAX_VISIBLE_ROWS) {
            return false;
        }
        int trackLeft = panelLeft + PANEL_WIDTH - RESULT_SCROLLBAR_LEFT_OFFSET;
        int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
        return isMouseOver(trackLeft, listTop, RESULT_SCROLLBAR_WIDTH, trackHeight, mouseX, mouseY);
    }

    private int resultScrollbarThumbHeight(int resultCount, int trackHeight) {
        return Math.max(RESULT_SCROLLBAR_MIN_THUMB_HEIGHT, trackHeight * MAX_VISIBLE_ROWS / resultCount);
    }

    private int resultScrollbarThumbTop(int resultCount, int scrollOffset, int listTop, int trackHeight,
        int thumbHeight) {
        int maxOffset = Math.max(1, resultCount - MAX_VISIBLE_ROWS);
        int maxThumbTop = trackHeight - thumbHeight;
        return listTop + maxThumbTop * Math.max(0, Math.min(maxOffset, scrollOffset)) / maxOffset;
    }

    private void updateResultScrollbarScroll(int mouseY) {
        int resultCount;
        int listTop;
        if (currentTab == CHARTS_TAB) {
            resultCount = chartResults.size();
            listTop = resultListTop(panelTop());
        } else if (currentTab == SEARCH_TAB) {
            resultCount = displayedSearchResults().size();
            listTop = resultListTop(panelTop());
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            resultCount = playlistResults.size();
            listTop = playlistDiscoveryListTop(panelTop());
        } else if (currentTab == RADIO_TAB) {
            resultCount = displayedRadioResults().size();
            listTop = panelTop() + radioListTopOffset(isRadioResultsLoading());
        } else {
            resultCount = playlist.size();
            listTop = playlistListTop(panelTop());
        }
        int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight);
        int maxOffset = Math.max(0, resultCount - MAX_VISIBLE_ROWS);
        int maxThumbTop = Math.max(1, trackHeight - thumbHeight);
        int desiredThumbTop = Math.max(listTop, Math.min(listTop + maxThumbTop, mouseY - resultScrollbarDragOffset));
        int offset = (desiredThumbTop - listTop) * maxOffset / maxThumbTop;
        if (currentTab == CHARTS_TAB) {
            chartScrollOffset = offset;
        } else if (currentTab == SEARCH_TAB) {
            searchScrollOffset = offset;
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            playlistScrollOffset = offset;
        } else if (currentTab == RADIO_TAB) {
            radioScrollOffset = offset;
        } else {
            queueScrollOffset = offset;
        }
    }

    private void drawQueueButton(int panelLeft, int rowTop, boolean inQueue, boolean hovered) {
        drawQueueButtonAt(panelLeft, queueButtonTop(rowTop), inQueue, hovered);
    }

    private void drawQueueButtonAt(int panelLeft, int top, boolean inQueue, boolean hovered) {
        drawTextButtonAt(panelLeft, top, inQueue ? "-" : "+", hovered);
    }

    private void drawTextButtonAt(int panelLeft, int top, String label, boolean hovered) {
        int left = queueButtonLeft(panelLeft);
        int outer = hovered ? 0xFF777777 : 0xFF5F5F5F;
        int inner = hovered ? 0xFF666666 : 0xFF4A4A4A;
        drawRect(left, top, left + QUEUE_BUTTON_WIDTH, top + QUEUE_BUTTON_HEIGHT, 0xFF111111);
        drawRect(left + 1, top + 1, left + QUEUE_BUTTON_WIDTH - 1, top + QUEUE_BUTTON_HEIGHT - 1, outer);
        drawRect(left + 3, top + 3, left + QUEUE_BUTTON_WIDTH - 3, top + QUEUE_BUTTON_HEIGHT - 3, inner);
        drawCenteredString(fontRendererObj, label, left + QUEUE_BUTTON_WIDTH / 2, queueButtonTextTop(top), 0xFFFFFFFF);
    }

    private static final class ControlButton extends GuiButton {

        private ResourceLocation iconTexture;
        private boolean active;
        private final int borderColor;
        private final String label;

        private ControlButton(int id, int x, int y, int width, int height, ResourceLocation iconTexture) {
            this(id, x, y, width, height, iconTexture, 0xFF111111);
        }

        private ControlButton(int id, int x, int y, int width, int height, ResourceLocation iconTexture,
            int borderColor) {
            super(id, x, y, width, height, "");
            this.iconTexture = iconTexture;
            this.borderColor = borderColor;
            this.label = "";
        }

        private ControlButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, "");
            this.iconTexture = null;
            this.borderColor = 0xFF111111;
            this.label = label == null ? "" : label;
        }

        private void setIcon(ResourceLocation iconTexture) {
            this.iconTexture = iconTexture;
        }

        private void setActive(boolean active) {
            this.active = active;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            boolean hovered = enabled && mouseX >= xPosition
                && mouseX < xPosition + width
                && mouseY >= yPosition
                && mouseY < yPosition + height;
            int outer = !enabled ? 0xFF4A4A4A : (active ? 0xFF6EAA6E : (hovered ? 0xFF777777 : 0xFF5F5F5F));
            int inner = !enabled ? 0xFF383838 : (active ? 0xFF456B45 : (hovered ? 0xFF666666 : 0xFF4A4A4A));
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, borderColor);
            drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, outer);
            drawRect(xPosition + 3, yPosition + 3, xPosition + width - 3, yPosition + height - 4, inner);
            drawRect(xPosition + 2, yPosition + 2, xPosition + width - 2, yPosition + 3, 0xFF9A9A9A);
            if (enabled) {
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                GL11.glColor4f(0.5F, 0.5F, 0.5F, 1.0F);
            }
            if (iconTexture == null) {
                drawCenteredString(
                    minecraft.fontRenderer,
                    label,
                    xPosition + width / 2,
                    yPosition + (height - 8) / 2 + 1,
                    0xFFFFFFFF);
                return;
            }
            minecraft.getTextureManager()
                .bindTexture(iconTexture);
            Gui.func_152125_a(
                xPosition + (width - CONTROL_ICON_SIZE) / 2,
                yPosition + (height - CONTROL_ICON_SIZE) / 2,
                0,
                0,
                CONTROL_ICON_TEXTURE_SIZE,
                CONTROL_ICON_TEXTURE_SIZE,
                CONTROL_ICON_SIZE,
                CONTROL_ICON_SIZE,
                CONTROL_ICON_TEXTURE_SIZE,
                CONTROL_ICON_TEXTURE_SIZE);
        }
    }

    private void drawProgressBar(int left, int top, float progress) {
        int barLeft = left + 10;
        int barRight = left + PANEL_WIDTH - 10;
        int barTop = top + SEARCH_PROGRESS_Y_OFFSET;
        drawRect(barLeft, barTop, barRight, barTop + SEARCH_PROGRESS_HEIGHT, 0xFF333333);
        int fillWidth = (int) ((barRight - barLeft) * Math.max(0.0f, Math.min(1.0f, progress)));
        if (fillWidth > 0) {
            drawRect(barLeft, barTop, barLeft + fillWidth, barTop + SEARCH_PROGRESS_HEIGHT, 0xFF55AA55);
        }
    }

    private void updateSearchProgress() {
        if (!searchLoading) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - searchStartedAt);
        float estimatedProgress = (float) elapsed / (float) progressEstimateMillis(SEARCH_TAB);
        searchProgress = Math.min(0.9f, Math.max(searchProgress, estimatedProgress * 0.9f));
    }

    private void updateChartProgress() {
        if (!chartLoading) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - chartStartedAt);
        float estimatedProgress = (float) elapsed / (float) progressEstimateMillis(CHARTS_TAB);
        chartProgress = Math.min(0.9f, Math.max(chartProgress, estimatedProgress * 0.9f));
    }

    private void updateRadioProgress() {
        if (!radioLoading) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - radioStartedAt);
        float estimatedProgress = (float) elapsed / (float) progressEstimateMillis(RADIO_TAB);
        radioProgress = Math.min(0.9f, Math.max(radioProgress, estimatedProgress * 0.9f));
    }

    private void updatePlaylistProgress() {
        if (!playlistLoading) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - playlistStartedAt);
        float estimatedProgress = (float) elapsed / (float) progressEstimateMillis(SEARCH_TAB);
        playlistProgress = Math.min(0.9f, Math.max(playlistProgress, estimatedProgress * 0.9f));
    }

    private boolean isSongResultTab(int tab) {
        return tab == CHARTS_TAB || tab == SEARCH_TAB || tab == PLAYLIST_DISCOVERY_TAB;
    }

    boolean isPlaylistTab() {
        return currentTab == PLAYLIST_TAB;
    }

    boolean isPlaylistDiscoveryTab() {
        return currentTab == PLAYLIST_DISCOVERY_TAB;
    }

    boolean isRadioTab() {
        return currentTab == RADIO_TAB;
    }

    boolean isRadioLoading() {
        return radioLoading;
    }

    boolean isRadioEmpty() {
        return radioResults.isEmpty();
    }

    boolean isActiveRadioStation(String stationUuid) {
        return isRadioActive() && radioState.getStationUuid()
            .equals(stationUuid);
    }

    String getRadioStatusSnapshot() {
        return hasRadioStatus() ? radioStatus() : "";
    }

    boolean musicControlsVisible() {
        return playbackButton != null && playbackButton.visible;
    }

    boolean radioControlsVisible() {
        return isRadioActive() && playbackButton != null && playbackButton.visible;
    }

    private boolean isRadioActive() {
        return radioState != null && radioState.isActive();
    }

    private boolean isPausedRadio() {
        String cachedNowPlaying = HorizonRadioClient.getCachedNowPlaying();
        return !isRadioActive() && !isMusicMode()
            && hasResumableRadioStation()
            && !hasRadioStatus()
            && (cachedNowPlaying == null || cachedNowPlaying.length() == 0)
            && nowPlaying != null
            && nowPlaying.equals(radioState.getStationName());
    }

    private boolean isMusicMode() {
        return radioState != null && radioState.isMusicMode();
    }

    static boolean shouldDrawPlaybackProgress(boolean radioActive, boolean pausedRadio) {
        return !radioActive && !pausedRadio;
    }

    private boolean canResumeRadio() {
        return currentTab == RADIO_TAB && radioState != null
            && radioState.getStationUuid() != null
            && radioState.getStationUuid()
                .length() > 0;
    }

    private boolean hasResumableRadioStation() {
        return radioState != null && radioState.getStationUuid() != null
            && radioState.getStationUuid()
                .length() > 0
            && radioState.getStationName() != null
            && radioState.getStationName()
                .length() > 0;
    }

    private boolean radioControlsLocked() {
        return isRadioActive() || canResumeRadio();
    }

    private String radioStatus() {
        String status = radioState == null ? "" : radioState.getStatus();
        return status == null || status.length() == 0 ? (isRadioActive() ? "LIVE" : "") : status;
    }

    private boolean hasRadioStatus() {
        return radioStatus().length() > 0;
    }

    private void updateControlVisibility() {
        boolean radioActive = isRadioActive();
        boolean controlsLocked = radioControlsLocked();
        setVisible(shuffleButton, true);
        setVisible(previousButton, true);
        setVisible(playbackButton, true);
        setVisible(nextButton, true);
        setVisible(loopButton, true);
        setVisible(favoriteButton, true);
        setEnabled(shuffleButton, !controlsLocked);
        setEnabled(previousButton, !controlsLocked);
        setEnabled(playbackButton, true);
        setEnabled(nextButton, !controlsLocked);
        setEnabled(loopButton, !controlsLocked);
        setEnabled(favoriteButton, HorizonRadioClient.hasCurrentFavoriteSource());
        if (favoriteButton != null) {
            favoriteButton.setActive(HorizonRadioClient.isCurrentSourceFavorite());
        }
        if (playbackButton != null) {
            playbackButton
                .setIcon(radioActive || !canResumeRadio() && !HorizonRadioClient.isPaused() ? ICON_PAUSE : ICON_PLAY);
        }
    }

    private static void setVisible(GuiButton button, boolean visible) {
        if (button != null) {
            button.visible = visible;
        }
    }

    private static void setEnabled(GuiButton button, boolean enabled) {
        if (button != null) {
            button.enabled = enabled;
        }
    }

    private boolean areAllChartsInQueue() {
        if (chartResults.isEmpty()) {
            return false;
        }
        for (SearchResult result : chartResults) {
            if (!isInQueue(result.videoId)) {
                return false;
            }
        }
        return true;
    }

    private boolean areAllChartsInQueueOrPending() {
        if (chartResults.isEmpty()) {
            return false;
        }
        for (SearchResult result : chartResults) {
            if (result == null || (!isInQueue(result.videoId) && !isChartAddPending(result.videoId))) {
                return false;
            }
        }
        return true;
    }

    private boolean areAllPlaylistResultsInQueue() {
        if (playlistResults.isEmpty()) {
            return false;
        }
        for (SearchResult result : playlistResults) {
            if (!isInQueue(result.videoId)) {
                return false;
            }
        }
        return true;
    }

    private boolean areAllPlaylistResultsInQueueOrPending() {
        if (playlistResults.isEmpty()) {
            return false;
        }
        for (SearchResult result : playlistResults) {
            if (result == null || (!isInQueue(result.videoId) && !isPlaylistAddPending(result.videoId))) {
                return false;
            }
        }
        return true;
    }

    private void sendResultToQueue(SearchResult result, boolean charts) {
        if (result == null) {
            return;
        }
        if (charts) {
            HorizonRadioClient.sendAddChartsToPlaylist(Collections.singletonList(result));
        } else {
            long durationMs = DurationParser.parseMillisStrict(result.duration);
            if (durationMs <= 0L) {
                return;
            }
            HorizonRadioClient.sendAdd(result.videoId, durationMs);
        }
    }

    private void playResultNow(SearchResult result) {
        if (result == null) {
            return;
        }
        HorizonRadioClient.sendPlayNow(result);
    }

    private boolean usesSharedSearchField() {
        return currentTab == CHARTS_TAB || currentTab == SEARCH_TAB || currentTab == RADIO_TAB;
    }

    private boolean showsSearchButton() {
        return usesSharedSearchField() || currentTab == PLAYLIST_DISCOVERY_TAB;
    }

    private boolean isCurrentResultAddPending(String videoId) {
        if (currentTab == CHARTS_TAB) {
            return isChartAddPending(videoId);
        }
        if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            return isPlaylistAddPending(videoId);
        }
        return false;
    }

    static List<HorizonRadioClient.PlaylistSelection> toPlaylistSelections(List<SearchResult> results) {
        List<HorizonRadioClient.PlaylistSelection> selections = new ArrayList<HorizonRadioClient.PlaylistSelection>();
        if (results == null) {
            return selections;
        }
        for (SearchResult result : results) {
            if (result == null) {
                continue;
            }
            long durationMs = DurationParser.parseMillisStrict(result.duration);
            if (durationMs > 0L) {
                selections.add(new HorizonRadioClient.PlaylistSelection(result.videoId, durationMs));
            }
        }
        return selections;
    }

    private String chartRegionDisplayName() {
        ChartRegion region = ChartRegionCatalog.byCode(chartRegionCode);
        return region == null ? "" : region.getDisplayName();
    }

    private String normalizeChartRegionCode(String value) {
        ChartRegion region = ChartRegionCatalog.byCode(value);
        return region == null ? "" : region.getCode();
    }

    private boolean hasChartRegion() {
        return chartRegionCode != null && chartRegionCode.length() > 0
            && !ChartRegionCatalog.GLOBAL_CODE.equals(chartRegionCode);
    }

    private static boolean looksLikePlaylistUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ENGLISH);
        return (lower.contains("youtube.com") || lower.contains("youtu.be")) && lower.contains("list=");
    }

    private static boolean looksLikeVideoUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ENGLISH);
        if (lower.contains("list=")) {
            return false;
        }
        return lower.contains("youtube.com/watch?v=") || lower.contains("youtu.be/")
            || lower.contains("youtube.com/shorts/")
            || lower.contains("youtube.com/live/");
    }

    private int playlistIndexAt(int mouseX, int mouseY) {
        int row = rowAt(mouseX, mouseY, playlistListTop(panelTop()));
        int index = row < 0 ? -1 : queueScrollOffset + row;
        return index >= 0 && index < playlist.size() ? index : -1;
    }

    private boolean isPlaylistIndexDraggable(int index) {
        return index >= 0 && index < playlist.size() && !(index == 0 && nowPlaying != null);
    }

    static boolean isPlaylistRowPlaying(int index, boolean hasNowPlaying, boolean radioActive) {
        return index == 0 && hasNowPlaying && !radioActive;
    }

    private int playlistListTop(int top) {
        return top + PLAYLIST_LIST_TOP_OFFSET;
    }

    private int playlistDiscoveryListTop(int top) {
        return top + searchListTopOffset(isPlaylistResultsLoading());
    }

    private int resultListTop(int top) {
        return currentTab == SEARCH_TAB ? top + searchListTopOffset(isSearchListLoading())
            : top + CONTENT_LIST_TOP_OFFSET;
    }

    private boolean isPlaylistDropAllowed(int index) {
        return isPlaylistIndexDraggable(index);
    }

    private static int scroll(int offset, int size, int wheel) {
        int direction = wheel > 0 ? -1 : 1;
        return Math.max(0, Math.min(offset + direction, Math.max(0, size - MAX_VISIBLE_ROWS)));
    }

    static int radioStationNameMaxWidth(int panelLeft) {
        return radioLiveLabelLeft(panelLeft) - 5 - (panelLeft + 15);
    }

    static String activeRadioNowPlayingLabel(String stationName) {
        return stationName == null ? "" : stationName;
    }

    static String radioNowPlayingDisplayLabel(String stationName) {
        return radioNowPlayingDisplayLabel(stationName, true);
    }

    static String radioNowPlayingDisplayLabel(String stationName, boolean showOnAir) {
        return showOnAir ? "ON AIR\u00B7 " + activeRadioNowPlayingLabel(stationName)
            : activeRadioNowPlayingLabel(stationName);
    }

    private static int radioLiveLabelLeft(int panelLeft) {
        return panelLeft + PANEL_WIDTH - 38;
    }

    private String truncate(String text, int maxWidth) {
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }
        while (text.length() > 0 && fontRendererObj.getStringWidth(text + "...") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    private static boolean isMouseOver(int x, int y, int width, int height, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public static final class SearchResult {

        public final String videoId;
        public final String title;
        public final String channel;
        public final String duration;
        public final String thumbnail;

        public SearchResult(String videoId, String title, String channel, String duration, String thumbnail) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
            this.thumbnail = thumbnail;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SearchResult)) {
                return false;
            }
            SearchResult that = (SearchResult) other;
            return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
                && Objects.equals(channel, that.channel)
                && Objects.equals(duration, that.duration)
                && Objects.equals(thumbnail, that.thumbnail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoId, title, channel, duration, thumbnail);
        }
    }

    public static final class RadioStationResult {

        public final String stationUuid;
        public final String name;

        public RadioStationResult(String stationUuid, String name) {
            this.stationUuid = stationUuid;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RadioStationResult)) {
                return false;
            }
            RadioStationResult that = (RadioStationResult) other;
            return Objects.equals(stationUuid, that.stationUuid) && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stationUuid, name);
        }
    }

    public static final class PlaylistEntry {

        private static final int SOURCE_ID_FALLBACK_LIMIT = 32;

        public final MediaSourceType sourceType;
        public final String sourceId;
        public final long durationMs;
        public final String videoId;
        public final String title;
        public final String duration;
        public final String addedBy;
        public final SearchResult localVideoMetadata;
        public final RadioStationResult localStationMetadata;

        public PlaylistEntry(String videoId, String title, String duration, String addedBy) {
            this(
                MediaSourceType.YOUTUBE,
                videoId,
                addedBy,
                new com.horizonradio.core.model.SearchResult(videoId, title, "", duration, ""),
                null);
        }

        public PlaylistEntry(MediaSourceType sourceType, String sourceId, String addedBy,
            com.horizonradio.core.model.SearchResult videoMetadata,
            com.horizonradio.core.model.RadioStation stationMetadata) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.addedBy = addedBy;
            this.localVideoMetadata = videoMetadata == null ? null
                : new SearchResult(
                    videoMetadata.getVideoId(),
                    videoMetadata.getTitle(),
                    videoMetadata.getChannel(),
                    videoMetadata.getDuration(),
                    videoMetadata.getThumbnail());
            this.localStationMetadata = stationMetadata == null ? null
                : new RadioStationResult(stationMetadata.getStationUuid(), stationMetadata.getName());
            this.videoId = sourceType == MediaSourceType.YOUTUBE ? sourceId : null;
            this.title = displayTitle();
            this.duration = displayDuration();
            this.durationMs = sourceType == MediaSourceType.YOUTUBE
                ? Math.max(0L, DurationParser.parseMillisStrict(this.duration))
                : 0L;
        }

        public boolean isFinite() {
            return sourceType == MediaSourceType.YOUTUBE;
        }

        public String displayTitle() {
            if (localVideoMetadata != null && localVideoMetadata.title != null
                && localVideoMetadata.title.length() > 0) {
                return localVideoMetadata.title;
            }
            if (localStationMetadata != null && localStationMetadata.name != null
                && localStationMetadata.name.length() > 0) {
                return localStationMetadata.name;
            }
            return boundedSourceId(sourceId);
        }

        public String displayDuration() {
            return localVideoMetadata == null || localVideoMetadata.duration == null ? "" : localVideoMetadata.duration;
        }

        static String boundedSourceId(String sourceId) {
            if (sourceId == null) {
                return "Loading...";
            }
            if (sourceId.length() <= SOURCE_ID_FALLBACK_LIMIT) {
                return sourceId;
            }
            return sourceId.substring(0, SOURCE_ID_FALLBACK_LIMIT - 3) + "...";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PlaylistEntry)) {
                return false;
            }
            PlaylistEntry that = (PlaylistEntry) other;
            return sourceType == that.sourceType && Objects.equals(sourceId, that.sourceId)
                && Objects.equals(title, that.title)
                && Objects.equals(duration, that.duration)
                && Objects.equals(addedBy, that.addedBy);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceType, sourceId, title, duration, addedBy);
        }
    }
}
