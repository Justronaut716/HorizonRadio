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

    public static final int PANEL_WIDTH = HorizonRadioUiLayout.REFERENCE_PANEL_WIDTH;
    public static final int PANEL_HEIGHT = HorizonRadioUiLayout.REFERENCE_PANEL_HEIGHT;
    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int ROW_HEIGHT = 22;
    private static final int NOW_PLAYING_HEIGHT = 22;
    private static final int CONTROL_CENTER_HEIGHT = 18;
    private static final int CONTROL_BUTTON_WIDTH = 18;
    private static final int CONTROL_BUTTON_HEIGHT = 18;
    private static final int CONTROL_BUTTON_GAP = 3;
    private static final int CONTROL_BUTTON_COUNT = 5;
    private static final int CONTROL_ICON_SIZE = 12;
    private static final int CONTROL_ICON_TEXTURE_SIZE = 128;
    private static final int TAB_BUTTON_WIDTH = 34;
    private static final int TAB_BUTTON_HEIGHT = 17;
    private static final int TAB_BUTTON_Y = 11;
    private static final int SEARCH_CONTROL_HEIGHT = 17;
    private static final int SEARCH_CONTROL_Y_OFFSET = 62;
    private static final int SEARCH_SIDE_MARGIN = 13;
    private static final int SEARCH_CONTROL_GAP = 3;
    private static final int SEARCH_FIELD_X_OFFSET = SEARCH_SIDE_MARGIN;
    private static final int SEARCH_BUTTON_WIDTH = 18;
    private static final int SEARCH_FIELD_WIDTH = 188;
    /* The content column is deliberately narrower than the complete panel. */
    private static final int CONTENT_LEFT_INSET = 8;
    private static final int CONTENT_WIDTH = 220;
    private static final int QUEUE_LEFT_INSET = 232;
    private static final int QUEUE_WIDTH = 120;
    private static final int BODY_TOP_OFFSET = 36;
    private static final int BODY_BOTTOM_OFFSET = 245;
    private static final int FOOTER_TOP_OFFSET = 251;
    private static final int VOLUME_TOP_OFFSET = 304;
    private static final int SONGS_TAB_X = 282;
    private static final int RADIO_TAB_X = 318;
    private static final int MODE_SEARCH_X = 13;
    private static final int MODE_CHARTS_X = 49;
    private static final int MODE_PLAYLISTS_X = 85;
    static final float MODE_BUTTON_TEXT_SCALE = 0.80F;
    private static final int REFRESH_BUTTON_X = 175;
    private static final int MODE_TOP_OFFSET = BODY_TOP_OFFSET + 5;
    private static final int SECTION_TOP_OFFSET = 90;
    private static final int CONTENT_LIST_TOP_OFFSET = 103;
    private static final int QUEUE_TOP_OFFSET = 41;
    private static final int QUEUE_LIST_TOP_OFFSET = 67;
    private static final int VOLUME_HEIGHT = 11;
    private static final int SEARCH_BUTTON_BORDER_COLOR = 0xFFA0A0A0;
    private static final int SEARCH_BUTTON_HEIGHT = SEARCH_CONTROL_HEIGHT + 2;
    private static final int SEARCH_BUTTON_Y_OFFSET = SEARCH_CONTROL_Y_OFFSET - 1;
    private static final int CHARTS_BULK_BUTTON_Y_OFFSET = 84;
    private static final int CHARTS_TAB = 0;
    private static final int SEARCH_TAB = 1;
    private static final int PLAYLIST_TAB = 2;
    private static final int PLAYLIST_DISCOVERY_TAB = 3;
    private static final int RADIO_TAB = 4;
    private static final int BUTTON_SEARCH = 0;
    private static final int BUTTON_CHARTS_TAB = 1;
    private static final int BUTTON_PLAYLIST_TAB = 2;
    private static final int BUTTON_SEARCH_TAB = 9;
    private static final int BUTTON_REFRESH_CHARTS = 10;
    private static final int BUTTON_RADIO_TAB = 11;
    private static final int BUTTON_FAVORITE = 12;
    private static final int BUTTON_PLAYLIST_DISCOVERY_TAB = 13;
    private static final int BUTTON_SETTINGS = 14;
    private static final int BUTTON_QUEUE_CLEAR = 17;
    private static final int BUTTON_BULK_ADD = 18;
    private static final int QUEUE_BUTTON_WIDTH = 18;
    private static final int QUEUE_BUTTON_HEIGHT = 17;
    private static final int QUEUE_BUTTON_COLUMN_WIDTH = 22;
    private static final int QUEUE_BUTTON_RIGHT_MARGIN = 5;
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
    private static final ResourceLocation ICON_LOGO = new ResourceLocation(
        "horizonradio",
        "textures/gui/HorizonRadioLogo.png");
    private static final int BUTTON_SONGS_TAB = 15;
    private static final int BUTTON_MODE_PLAYLISTS = 16;
    private static final int SEARCH_PROGRESS_Y_OFFSET = BODY_TOP_OFFSET + 42;
    private static final int SEARCH_PROGRESS_HEIGHT = 6;
    private static final int SEARCH_LIST_TOP_OFFSET = CONTENT_LIST_TOP_OFFSET;
    private static final int SEARCH_LIST_TOP_WITHOUT_PROGRESS_OFFSET = CONTENT_LIST_TOP_OFFSET;
    private static final int CONTENT_HEADER_Y_OFFSET = SECTION_TOP_OFFSET;
    private static final int CONTENT_LABEL_Y_OFFSET = SECTION_TOP_OFFSET;
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
    private static final int TIME_BAR_SIDE_SPACE = 29;

    private GuiTextField searchField;
    private GuiTextField playlistUrlField;
    private ControlButton searchButton;
    private ControlButton refreshChartsButton;
    private GuiButton settingsButton;
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
    private boolean draggingQueueScrollbar;
    private int queueScrollbarDragOffset;
    private static HorizonRadioScreen activeScreen;
    private HorizonRadioUiLayout uiLayout;
    private ControlButton songsTabButton;
    private ControlButton radioTabButton;
    private ControlButton chartsTabButton;
    private ControlButton searchTabButton;
    private ControlButton playlistsTabButton;
    private ControlButton queueClearButton;
    private ControlButton bulkAddButton;

    public HorizonRadioScreen() {
        super();
    }

    @Override
    public void initGui() {
        setActiveScreen(this);
        uiLayout = HorizonRadioUiLayout.create(width, height);
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
            panelLeft + SEARCH_FIELD_X_OFFSET + SEARCH_FIELD_WIDTH + SEARCH_CONTROL_GAP,
            panelTop + SEARCH_BUTTON_Y_OFFSET,
            SEARCH_BUTTON_WIDTH,
            SEARCH_BUTTON_HEIGHT,
            ICON_SEARCH,
            SEARCH_BUTTON_BORDER_COLOR);
        addButton(searchButton);
        chartsTabButton = createModeButton(
            BUTTON_CHARTS_TAB,
            panelLeft + MODE_CHARTS_X,
            panelTop + MODE_TOP_OFFSET,
            "Charts");
        addButton(chartsTabButton);
        searchTabButton = createModeButton(
            BUTTON_SEARCH_TAB,
            panelLeft + MODE_SEARCH_X,
            panelTop + MODE_TOP_OFFSET,
            "Search");
        addButton(searchTabButton);
        playlistsTabButton = createModeButton(
            BUTTON_PLAYLIST_DISCOVERY_TAB,
            panelLeft + MODE_PLAYLISTS_X,
            panelTop + MODE_TOP_OFFSET,
            "Playlists");
        addButton(playlistsTabButton);
        songsTabButton = createTextButton(BUTTON_SONGS_TAB, panelLeft + SONGS_TAB_X, panelTop + TAB_BUTTON_Y, "Songs");
        addButton(songsTabButton);
        radioTabButton = createTextButton(BUTTON_RADIO_TAB, panelLeft + RADIO_TAB_X, panelTop + TAB_BUTTON_Y, "Radio");
        addButton(radioTabButton);
        refreshChartsButton = new ControlButton(
            BUTTON_REFRESH_CHARTS,
            panelLeft + REFRESH_BUTTON_X,
            panelTop + MODE_TOP_OFFSET,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            ICON_LOOP);
        addButton(refreshChartsButton);
        bulkAddButton = new ControlButton(
            BUTTON_BULK_ADD,
            panelLeft + 197,
            panelTop + CHARTS_BULK_BUTTON_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            "+");
        bulkAddButton.setGreenActive(true);
        addButton(bulkAddButton);
        settingsButton = null;
        queueClearButton = new ControlButton(
            BUTTON_QUEUE_CLEAR,
            panelLeft + QUEUE_LEFT_INSET + QUEUE_WIDTH - QUEUE_BUTTON_WIDTH - 6,
            panelTop + QUEUE_TOP_OFFSET - 2,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            "×");
        addButton(queueClearButton);
        addControlButtons(panelLeft, panelTop);
        volumeSlider = new HorizonRadioVolumeSlider(
            3,
            panelLeft + 24,
            panelTop + VOLUME_TOP_OFFSET + 3,
            312,
            VOLUME_HEIGHT,
            HorizonRadioClient.getVolume());
        addButton(volumeSlider);
        openCharts();
        updateChartRefreshButtonState();
        updateControlVisibility();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (uiLayout == null) {
            uiLayout = HorizonRadioUiLayout.create(width, height);
        }
        int logicalMouseX = uiLayout.toLogicalMouseX(mouseX);
        int logicalMouseY = uiLayout.toLogicalMouseY(mouseY);
        beginUiTransform();
        int left = panelLeft();
        int top = panelTop();
        drawPanelBackground(left, top);
        drawHeader(left, top);
        updatePendingResultReveals();

        if (currentTab == CHARTS_TAB) {
            updateChartProgress();
            drawChartsTab(left, top, logicalMouseX, logicalMouseY);
        } else if (currentTab == SEARCH_TAB) {
            updateSearchProgress();
            drawSearchTab(left, top, logicalMouseX, logicalMouseY);
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            updatePlaylistProgress();
            drawPlaylistDiscoveryTab(left, top, logicalMouseX, logicalMouseY);
        } else if (currentTab == RADIO_TAB) {
            updateRadioProgress();
            drawRadioTab(left, top, logicalMouseX, logicalMouseY);
        } else {
            drawSearchTab(left, top, logicalMouseX, logicalMouseY);
        }
        drawQueuePanel(left, top, logicalMouseX, logicalMouseY);
        int nowPlayingTop = nowPlayingTop(top);
        drawNowPlaying(left, nowPlayingTop);
        drawControlCenter(left, nowPlayingTop);
        drawVolumeLabels(left, top);

        searchButton.visible = showsSearchButton();
        searchButton.enabled = currentTab != PLAYLIST_DISCOVERY_TAB || !playlistLoading;
        refreshChartsButton.visible = currentTab == CHARTS_TAB;
        boolean showBulkAdd = currentTab == CHARTS_TAB || currentTab == PLAYLIST_DISCOVERY_TAB;
        boolean bulkAddComplete = currentTab == CHARTS_TAB ? areAllChartsInQueueOrPending()
            : areAllPlaylistResultsInQueueOrPending();
        bulkAddButton.visible = showBulkAdd;
        bulkAddButton.enabled = showBulkAdd && !bulkAddComplete
            && !(currentTab == CHARTS_TAB ? isChartResultsLoading() : isPlaylistResultsLoading());
        bulkAddButton.setLabel(bulkAddComplete ? "\u2713" : "+");
        bulkAddButton.setActive(bulkAddComplete);
        queueClearButton.visible = true;
        queueClearButton.enabled = !playlist.isEmpty() || isRadioActive();
        songsTabButton.setActive(currentTab != RADIO_TAB);
        radioTabButton.setActive(currentTab == RADIO_TAB);
        chartsTabButton.setActive(currentTab == CHARTS_TAB);
        searchTabButton.setActive(currentTab == SEARCH_TAB);
        playlistsTabButton.setActive(currentTab == PLAYLIST_DISCOVERY_TAB);
        updateChartRefreshButtonState();
        updateControlVisibility();
        if (usesSharedSearchField()) {
            searchField.drawTextBox();
            if (searchField.getText()
                .trim()
                .length() == 0 && !searchField.isFocused()) {
                drawString(
                    fontRendererObj,
                    searchPlaceholder(),
                    searchField.xPosition + 4,
                    searchField.yPosition + 5,
                    0xFF8D8D8D);
            }
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            playlistUrlField.drawTextBox();
        }
        super.drawScreen(logicalMouseX, logicalMouseY, partialTicks);
        drawPanelBorder(left, top);
        drawActiveTabBorder(left, top);
        endUiTransform();
    }

    private String searchPlaceholder() {
        if (currentTab == CHARTS_TAB) {
            return "Search charts by country...";
        }
        if (currentTab == RADIO_TAB) {
            return "Search radio stations...";
        }
        if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            return "Search playlists...";
        }
        return "Search songs...";
    }

    private void beginUiTransform() {
        GL11.glPushMatrix();
        GL11.glTranslatef(width / 2.0f, height / 2.0f, 0.0f);
        GL11.glScalef(uiLayout.scale(), uiLayout.scale(), 1.0f);
        GL11.glTranslatef(-width / 2.0f, -height / 2.0f, 0.0f);
    }

    private void endUiTransform() {
        GL11.glPopMatrix();
    }

    private void drawPanelBackground(int left, int top) {
        drawRect(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF202020);
        drawRect(left + 3, top + 3, left + PANEL_WIDTH - 3, top + PANEL_HEIGHT - 3, 0xFF202020);
        drawRect(
            left + CONTENT_LEFT_INSET,
            top + BODY_TOP_OFFSET,
            left + CONTENT_LEFT_INSET + CONTENT_WIDTH,
            top + BODY_BOTTOM_OFFSET,
            0xFF242424);
        drawRect(
            left + QUEUE_LEFT_INSET,
            top + BODY_TOP_OFFSET,
            left + QUEUE_LEFT_INSET + QUEUE_WIDTH,
            top + BODY_BOTTOM_OFFSET,
            0xFF272727);
        drawPanelBoxBorder(
            left + CONTENT_LEFT_INSET,
            top + BODY_TOP_OFFSET,
            left + CONTENT_LEFT_INSET + CONTENT_WIDTH,
            top + BODY_BOTTOM_OFFSET);
        drawPanelBoxBorder(
            left + QUEUE_LEFT_INSET,
            top + BODY_TOP_OFFSET,
            left + QUEUE_LEFT_INSET + QUEUE_WIDTH,
            top + BODY_BOTTOM_OFFSET);
        drawRect(
            left + CONTENT_LEFT_INSET,
            top + BODY_TOP_OFFSET,
            left + CONTENT_LEFT_INSET + CONTENT_WIDTH,
            top + BODY_TOP_OFFSET + 1,
            0xFF555555);
        drawRect(
            left + QUEUE_LEFT_INSET,
            top + BODY_TOP_OFFSET,
            left + QUEUE_LEFT_INSET + QUEUE_WIDTH,
            top + BODY_TOP_OFFSET + 1,
            0xFF555555);
        drawRect(left + 3, top + VOLUME_TOP_OFFSET - 1, left + PANEL_WIDTH - 3, top + VOLUME_TOP_OFFSET, 0xFF555555);
    }

    private void drawPanelBoxBorder(int left, int top, int right, int bottom) {
        drawRect(left, top, right, top + 1, 0xFF555555);
        drawRect(left, bottom - 1, right, bottom, 0xFF555555);
        drawRect(left, top, left + 1, bottom, 0xFF555555);
        drawRect(right - 1, top, right, bottom, 0xFF555555);
    }

    private void drawHeader(int left, int top) {
        drawRect(left + 3, top + 3, left + PANEL_WIDTH - 3, top + BODY_TOP_OFFSET, 0xFF181818);
        drawRect(left + 3, top + BODY_TOP_OFFSET - 1, left + PANEL_WIDTH - 3, top + BODY_TOP_OFFSET, 0xFF555555);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(ICON_LOGO);
        Gui.func_152125_a(left + 12, top + 11, 0, 0, 1431, 307, 102, 22, 1431, 307);
    }

    private void drawVolumeLabels(int left, int top) {
        drawString(fontRendererObj, "VOL", left + 10, top + VOLUME_TOP_OFFSET + 3, 0xFFB3B3B3);
        int value = volumeSlider == null ? Math.round(HorizonRadioClient.getVolume() * 100.0f)
            : Math.round(volumeSlider.getValue() * 100.0f);
        drawString(
            fontRendererObj,
            String.valueOf(value),
            left + PANEL_WIDTH - 20,
            top + VOLUME_TOP_OFFSET + 3,
            0xFFB3B3B3);
    }

    private void drawPanelBorder(int left, int top) {
        drawRect(left, top, left + PANEL_WIDTH, top + 1, 0xFFAAAAAA);
        drawRect(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF555555);
        drawRect(left, top, left + 1, top + PANEL_HEIGHT, 0xFFAAAAAA);
        drawRect(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF555555);
    }

    private void drawActiveTabBorder(int panelLeft, int panelTop) {
        GuiButton activeHeader = currentTab == RADIO_TAB ? radioTabButton : songsTabButton;
        GuiButton activeMode = currentTab == CHARTS_TAB ? chartsTabButton
            : (currentTab == SEARCH_TAB ? searchTabButton : playlistsTabButton);
        drawButtonBorder(activeHeader, 0xFFFFFFFF);
        drawButtonBorder(activeMode, 0xFFFFFFFF);
    }

    private void drawButtonBorder(GuiButton button, int color) {
        if (button == null || !button.visible) {
            return;
        }
        drawRect(
            button.xPosition - 1,
            button.yPosition - 1,
            button.xPosition + button.width + 1,
            button.yPosition,
            color);
        drawRect(
            button.xPosition - 1,
            button.yPosition + button.height,
            button.xPosition + button.width + 1,
            button.yPosition + button.height + 1,
            color);
        drawRect(button.xPosition - 1, button.yPosition, button.xPosition, button.yPosition + button.height, color);
        drawRect(
            button.xPosition + button.width,
            button.yPosition,
            button.xPosition + button.width + 1,
            button.yPosition + button.height,
            color);
    }

    private void drawChartsTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isChartResultsLoading();
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, chartProgress);
        } else {
            String chartHeader = chartHeaderLabel(hasChartRegion(), chartRegionDisplayName());
            if (chartHeader.length() > 0) {
                drawString(
                    fontRendererObj,
                    chartHeader,
                    contentLeft(left) + 5,
                    top + CONTENT_LABEL_Y_OFFSET,
                    0xFFF0F0F0);
            }
            if (chartSearchMessage.length() > 0) {
                drawString(
                    fontRendererObj,
                    chartSearchMessage,
                    contentLeft(left) + 5,
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
    }

    private void drawSearchTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isSearchListLoading();
        List<SearchResult> results = displayedSearchResults();
        if (shouldDrawSearchProgressBar(resultsLoading)) {
            drawProgressBar(left, top, searchProgress);
        }
        drawString(fontRendererObj, "Songs", contentLeft(left) + 5, top + SECTION_TOP_OFFSET, 0xFFF0F0F0);
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
        List<SearchResult> results = displayedPlaylistResults();
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, playlistProgress);
        }
        drawString(fontRendererObj, "Playlists", contentLeft(left) + 5, top + SECTION_TOP_OFFSET, 0xFFF0F0F0);
        drawResultList(
            resultsLoading ? Collections.<SearchResult>emptyList() : results,
            playlistScrollOffset,
            left,
            playlistDiscoveryListTop(top),
            mouseX,
            mouseY,
            resultsLoading ? "Loading playlist..." : (playlistError.length() > 0 ? playlistError : "No songs found"));
    }

    private List<SearchResult> displayedPlaylistResults() {
        List<SearchResult> source = playlistResults.isEmpty() ? chartResults : playlistResults;
        String query = searchField == null ? ""
            : searchField.getText()
                .trim()
                .toLowerCase(java.util.Locale.ENGLISH);
        if (query.length() == 0) {
            return new ArrayList<SearchResult>(source);
        }
        List<SearchResult> filtered = new ArrayList<SearchResult>();
        for (SearchResult result : source) {
            if (result == null) {
                continue;
            }
            String title = result.title == null ? "" : result.title.toLowerCase(java.util.Locale.ENGLISH);
            String channel = result.channel == null ? "" : result.channel.toLowerCase(java.util.Locale.ENGLISH);
            if (title.contains(query) || channel.contains(query)) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    private void drawQueuePanel(int left, int top, int mouseX, int mouseY) {
        int queueLeft = left + QUEUE_LEFT_INSET;
        int headerTop = top + QUEUE_TOP_OFFSET;
        int rowTop = top + QUEUE_LIST_TOP_OFFSET;
        int queueCount = playlist.size() + (isRadioActive() ? 1 : 0);
        drawString(fontRendererObj, "QUEUE", queueLeft + 7, headerTop + 3, 0xFFF2F2F2);
        drawString(
            fontRendererObj,
            "(" + queueCount + "/" + QUEUE_DISPLAY_LIMIT + ")",
            queueLeft + 45,
            headerTop + 3,
            0xFF8F9A91);
        drawRect(
            queueLeft + 5,
            headerTop + TAB_BUTTON_HEIGHT + 2,
            queueLeft + QUEUE_WIDTH - 5,
            headerTop + TAB_BUTTON_HEIGHT + 3,
            0xFF4B4B4B);

        int renderedRows = 0;
        if (isRadioActive() && radioState != null) {
            drawQueueRow(
                queueLeft,
                rowTop,
                renderedRows++,
                radioState.getStationName(),
                "LIVE",
                "LIVE",
                true,
                mouseX,
                mouseY,
                true);
        }
        for (int index = queueScrollOffset; index < playlist.size() && renderedRows < MAX_VISIBLE_ROWS; index++) {
            PlaylistEntry entry = playlist.get(index);
            if (entry == null) {
                continue;
            }
            drawQueueRow(
                queueLeft,
                rowTop,
                renderedRows++,
                entry.displayTitle(),
                entry.addedBy,
                entry.displayDuration(),
                isPlaylistRowPlaying(index, entry),
                mouseX,
                mouseY,
                false);
        }
        if (queueCount == 0) {
            drawString(fontRendererObj, "Queue is empty", queueLeft + 8, rowTop + 10, 0xFF999999);
        }
        if (queueCount > MAX_VISIBLE_ROWS) {
            drawQueueScrollbar(queueLeft, rowTop, queueCount);
        }
    }

    private void drawQueueRow(int queueLeft, int listTop, int row, String title, String artist, String duration,
        boolean active, int mouseX, int mouseY, boolean radioRow) {
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        duration = duration == null ? "" : duration;
        int y = listTop + row * ROW_HEIGHT;
        boolean hovered = mouseX >= queueLeft + 3 && mouseX <= queueLeft + QUEUE_WIDTH - 3
            && mouseY >= y
            && mouseY < y + ROW_HEIGHT;
        int background = active ? 0xFF315B38 : (hovered ? 0xFF343434 : 0xFF292929);
        drawRect(queueLeft + 5, y, queueLeft + QUEUE_WIDTH - 6, y + ROW_HEIGHT - 1, background);
        drawString(fontRendererObj, String.valueOf(row + 1), queueLeft + 6, y + 7, active ? 0xFFC3F1C7 : 0xFF999999);
        int textLeft = queueLeft + 17;
        int removeLeft = queueLeft + QUEUE_WIDTH - QUEUE_BUTTON_WIDTH - 6;
        int textWidth = removeLeft - 4 - textLeft - 17;
        drawString(fontRendererObj, truncate(title, textWidth), textLeft, y + 3, active ? 0xFFE3F5E4 : 0xFFE6E6E6);
        drawString(fontRendererObj, truncate(artist, textWidth), textLeft, y + 13, active ? 0xFFB9D5BC : 0xFF999999);
        drawString(
            fontRendererObj,
            duration,
            removeLeft - fontRendererObj.getStringWidth(duration) - 3,
            y + 3,
            active ? 0xFFE3F5E4 : 0xFF999999);
        drawTextButtonAbsolute(
            removeLeft,
            queueButtonTop(y),
            radioRow ? "×" : "×",
            isMouseOver(removeLeft, queueButtonTop(y), QUEUE_BUTTON_WIDTH, QUEUE_BUTTON_HEIGHT, mouseX, mouseY));
    }

    private void drawQueueScrollbar(int queueLeft, int listTop, int resultCount) {
        int trackLeft = queueLeft + QUEUE_WIDTH - 4;
        int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight);
        int thumbTop = resultScrollbarThumbTop(resultCount, queueScrollOffset, listTop, trackHeight, thumbHeight);
        drawRect(trackLeft, listTop, trackLeft + 2, listTop + trackHeight, 0x66555555);
        drawRect(trackLeft, thumbTop, trackLeft + 2, thumbTop + thumbHeight, 0xFFDDDDDD);
    }

    private boolean isQueueScrollbarAt(int queueLeft, int listTop, int resultCount, int mouseX, int mouseY) {
        if (resultCount <= MAX_VISIBLE_ROWS) {
            return false;
        }
        int trackLeft = queueLeft + QUEUE_WIDTH - 4;
        int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
        return isMouseOver(trackLeft - 1, listTop, 4, trackHeight, mouseX, mouseY);
    }

    private void updateQueueScrollbarScroll(int mouseY) {
        int resultCount = playlist.size() + (isRadioActive() ? 1 : 0);
        int listTop = panelTop() + QUEUE_LIST_TOP_OFFSET;
        int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight);
        int maxOffset = Math.max(0, resultCount - MAX_VISIBLE_ROWS);
        int maxThumbTop = Math.max(1, trackHeight - thumbHeight);
        int desiredThumbTop = Math.max(listTop, Math.min(listTop + maxThumbTop, mouseY - queueScrollbarDragOffset));
        queueScrollOffset = (desiredThumbTop - listTop) * maxOffset / maxThumbTop;
        queueScrollOffset = Math.max(0, Math.min(queueScrollOffset, queueMaxScrollOffset()));
    }

    private List<SearchResult> displayedSearchResults() {
        if (searchField == null) {
            return new ArrayList<SearchResult>(searchResults);
        }
        if (!isEmptySearchQuery() || !searchResults.isEmpty()) {
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

    private void clampSharedSearchResultScrollOffsets() {
        searchScrollOffset = 0;
        radioScrollOffset = 0;
    }

    private boolean isSearchListLoading() {
        return !isEmptySearchQuery() && isSearchResultsLoading();
    }

    public void drawRadioTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isRadioResultsLoading();
        List<RadioStationResult> results = displayedRadioResults();
        int contentLeft = contentLeft(left);
        int contentRight = contentRight(left);
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, radioProgress);
        }
        drawString(fontRendererObj, "Stations", contentLeft + 5, top + SECTION_TOP_OFFSET, 0xFFF0F0F0);
        int listTop = top + radioListTopOffset(resultsLoading);
        if (results.isEmpty()) {
            String emptyMessage = resultsLoading ? "Loading stations..."
                : (radioError.length() > 0 ? radioError : "No radio stations available");
            drawCenteredString(
                fontRendererObj,
                emptyMessage,
                contentLeft + CONTENT_WIDTH / 2,
                listTop + 20,
                0xFF888888);
            return;
        }
        for (int row = 0; row < MAX_VISIBLE_ROWS && radioScrollOffset + row < results.size(); row++) {
            int y = listTop + row * ROW_HEIGHT;
            RadioStationResult station = results.get(radioScrollOffset + row);
            boolean hovered = mouseX >= contentLeft + 5 && mouseX <= contentRight
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT;
            boolean active = isActiveRadioStation(station.stationUuid);
            drawRect(
                contentLeft + 5,
                y,
                contentRight,
                y + ROW_HEIGHT - 1,
                active ? 0xFF315B38 : (hovered ? 0xFF343434 : 0xFF292929));
            int textColor = active ? 0xFFE3F5E4 : 0xFFF2F2F2;
            int textWidth = contentRight - contentLeft - 62;
            drawString(fontRendererObj, truncate(station.name, textWidth), contentLeft + 9, y + 3, textColor);
            drawString(fontRendererObj, "LIVE", contentRight - 42, y + 3, active ? 0xFFC3F1C7 : 0xFF999999);
            drawString(fontRendererObj, active ? "■" : "▶", contentRight - 18, y + 3, active ? 0xFFC3F1C7 : 0xFFD3D3D3);
        }
        drawResultScrollbar(results.size(), radioScrollOffset, left, listTop);
    }

    private void drawResultList(List<SearchResult> results, int scrollOffset, int left, int listTop, int mouseX,
        int mouseY, String emptyMessage) {
        int contentLeft = contentLeft(left);
        int contentRight = contentRight(left);
        if (results.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                emptyMessage,
                contentLeft + CONTENT_WIDTH / 2,
                listTop + 20,
                0xFF888888);
            return;
        }
        for (int row = 0; row < MAX_VISIBLE_ROWS && scrollOffset + row < results.size(); row++) {
            int y = listTop + row * ROW_HEIGHT;
            SearchResult result = results.get(scrollOffset + row);
            boolean hovered = mouseX >= contentLeft + 5 && mouseX <= contentRight
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT;
            boolean active = !isRadioActive() && nowPlaying != null && nowPlaying.equals(result.title);
            drawRect(
                contentLeft + 5,
                y,
                contentRight,
                y + ROW_HEIGHT - 1,
                active ? 0xFF315B38 : (hovered ? 0xFF343434 : 0xFF292929));
            int queueButtonLeft = queueButtonLeft(left);
            int durationLeft = queueButtonLeft - 2;
            int textRight = durationLeft - 5;
            int textWidth = textRight - (contentLeft + 23);
            drawString(fontRendererObj, "\u266B", contentLeft + 9, y + 4, active ? 0xFFC3F1C7 : 0xFFB0B0B0);
            drawString(
                fontRendererObj,
                truncate(result.title, textWidth),
                contentLeft + 23,
                y + 3,
                active ? 0xFFE3F5E4 : 0xFFF2F2F2);
            drawString(
                fontRendererObj,
                result.duration,
                queueButtonLeft - fontRendererObj.getStringWidth(result.duration) - 5,
                y + 3,
                0xFFB3B3B3);
            drawString(
                fontRendererObj,
                truncate(result.channel, textWidth),
                contentLeft + 23,
                y + 13,
                active ? 0xFFC9E6CB : 0xFF999999);
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
            boolean isPlaying = isPlaylistRowPlaying(index, entry);
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
        drawRect(left + 3, y, left + PANEL_WIDTH - 3, y + 46, 0xFF181818);
        drawRect(left + 3, y, left + PANEL_WIDTH - 3, y + 1, 0xFF555555);
        boolean radioActive = isRadioActive();
        boolean pausedRadio = !radioActive && isPausedRadio();
        if (radioActive || pausedRadio) {
            String radioLabel = radioNowPlayingDisplayLabel(nowPlaying, radioActive || pausedRadio);
            drawString(fontRendererObj, truncate(radioLabel, PANEL_WIDTH - 40), left + 10, y + 6, 0xFFA8D6AB);
        }
        if (!shouldDrawPlaybackProgress(radioActive, pausedRadio)) {
            return;
        }
        if (!isRadioActive() && hasRadioStatus()) {
            drawString(
                fontRendererObj,
                "Radio: " + truncate(radioStatus(), PANEL_WIDTH - 40),
                left + 10,
                y + 6,
                0xFFFF7777);
            return;
        }
        if (nowPlaying == null) {
            drawString(fontRendererObj, "Nothing playing", left + 10, y + 6, 0xFF666666);
            return;
        }
        drawString(fontRendererObj, "\u266A " + truncate(nowPlaying, PANEL_WIDTH - 40), left + 10, y + 3, 0xFFFFFFFF);
        String artist = currentArtistLabel();
        if (artist.length() > 0) {
            drawString(fontRendererObj, truncate(artist, PANEL_WIDTH - 40), left + 10, y + 13, 0xFFA6A6A6);
        }
        int barLeft = left + TIME_BAR_SIDE_SPACE;
        int barWidth = PANEL_WIDTH - 2 * TIME_BAR_SIDE_SPACE;
        int barTop = y + 24;
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
            controlLeft + controlGroupWidth() + 10,
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            "\u2665");
        favoriteButton.setGreenActive(true);
        addButton(favoriteButton);
        updateFavoriteState();
    }

    @SuppressWarnings("unchecked")
    private void addButton(GuiButton button) {
        buttonList.add(button);
    }

    private ControlButton createTextButton(int id, int x, int y, String label) {
        return new ControlButton(id, x, y, TAB_BUTTON_WIDTH, TAB_BUTTON_HEIGHT, label);
    }

    private ControlButton createModeButton(int id, int x, int y, String label) {
        ControlButton button = createTextButton(id, x, y, label);
        button.setLabelScale(MODE_BUTTON_TEXT_SCALE);
        return button;
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
        int favoriteLeft = controlLeft + groupWidth + 10;
        drawRect(
            favoriteLeft - 5,
            controlTop + 3,
            favoriteLeft - 4,
            controlTop + CONTROL_BUTTON_HEIGHT - 3,
            0xFF606060);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BUTTON_SEARCH) {
            if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                if (playlistUrlField != null && playlistUrlField.getText()
                    .trim()
                    .length() > 0
                    && (searchField == null || searchField.getText()
                        .trim()
                        .length() == 0)) {
                    performPlaylistImport();
                } else {
                    performSearch();
                }
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
        } else if (button.id == BUTTON_SONGS_TAB) {
            if (currentTab == RADIO_TAB) {
                openCharts();
            }
        } else if (button.id == BUTTON_BULK_ADD) {
            performBulkAdd();
        } else if (button.id == BUTTON_QUEUE_CLEAR) {
            if (isRadioActive()) {
                HorizonRadioClient.sendStopRadio();
            }
            if (!playlist.isEmpty()) {
                HorizonRadioClient.sendClearPlaylist();
            }
        } else if (button.id == BUTTON_SETTINGS) {
            Minecraft.getMinecraft()
                .displayGuiScreen(new HorizonRadioSettingsScreen(this));
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
        } else if (button.id == 7) {
            HorizonRadioClient.sendSkipTrack();
        } else if (button.id == 5) {
            HorizonRadioClient.sendPreviousTrack();
        } else if (button.id == 8 && !radioControlsLocked()) {
            HorizonRadioClient.sendToggleLoop();
        } else if (button.id == 4 && !radioControlsLocked()) {
            HorizonRadioClient.sendToggleShuffle();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (usesSharedSearchField()) {
            boolean wasEmpty = isEmptySearchQuery();
            if (searchField.textboxKeyTyped(typedChar, keyCode)) {
                if (wasEmpty != isEmptySearchQuery()) {
                    clampSharedSearchResultScrollOffsets();
                }
                return;
            }
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
        if (uiLayout == null) {
            uiLayout = HorizonRadioUiLayout.create(width, height);
        }
        mouseX = uiLayout.toLogicalMouseX(mouseX);
        mouseY = uiLayout.toLogicalMouseY(mouseY);
        if (button == 0 && handleQueueClick(mouseX, mouseY)) {
            return;
        }
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
        if (button == 0 && isSongResultTab(currentTab)
            && !(currentTab == PLAYLIST_DISCOVERY_TAB && isPlaylistResultsLoading())) {
            boolean charts = currentTab == CHARTS_TAB;
            boolean playlistDiscovery = currentTab == PLAYLIST_DISCOVERY_TAB;
            if ((charts || playlistDiscovery) && isChartsBulkButtonAt(panelLeft(), panelTop(), mouseX, mouseY)) {
                performBulkAdd();
                return;
            }
            List<SearchResult> results = charts ? chartResults
                : (playlistDiscovery ? displayedPlaylistResults() : displayedSearchResults());
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
        if (usesSharedSearchField() && searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, button);
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB && playlistUrlField != null) {
            playlistUrlField.mouseClicked(mouseX, mouseY, button);
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleQueueClick(int mouseX, int mouseY) {
        int left = queuePanelLeft(panelLeft());
        int listTop = panelTop() + QUEUE_LIST_TOP_OFFSET;
        int queueCount = playlist.size() + (isRadioActive() ? 1 : 0);
        if (queueCount > MAX_VISIBLE_ROWS && isQueueScrollbarAt(left, listTop, queueCount, mouseX, mouseY)) {
            int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
            int thumbHeight = resultScrollbarThumbHeight(queueCount, trackHeight);
            int thumbTop = resultScrollbarThumbTop(queueCount, queueScrollOffset, listTop, trackHeight, thumbHeight);
            queueScrollbarDragOffset = mouseY - thumbTop;
            draggingQueueScrollbar = true;
            updateQueueScrollbarScroll(mouseY);
            return true;
        }
        int row = queueRowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        int removeLeft = left + QUEUE_WIDTH - QUEUE_BUTTON_WIDTH - 4;
        int rowTop = listTop + row * ROW_HEIGHT;
        if (isMouseOver(removeLeft, queueButtonTop(rowTop), QUEUE_BUTTON_WIDTH, QUEUE_BUTTON_HEIGHT, mouseX, mouseY)) {
            if (isRadioActive() && row == 0) {
                HorizonRadioClient.sendStopRadio();
            } else {
                int playlistIndex = queueIndexAtRow(row);
                if (playlistIndex >= 0 && playlistIndex < playlist.size()) {
                    HorizonRadioClient.sendRemove(playlist.get(playlistIndex).sourceId);
                }
            }
            return true;
        }
        if (isRadioActive() && row == 0) {
            return true;
        }
        int playlistIndex = queueIndexAtRow(row);
        if (playlistIndex >= 0 && playlistIndex < playlist.size()) {
            draggedPlaylistIndex = playlistIndex;
            draggedPlaylistEntry = playlist.get(playlistIndex);
            playlistDragMoved = false;
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            return true;
        }
        return true;
    }

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int eventMouseX = width / 2;
            int eventMouseY = height / 2;
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.displayWidth > 0 && minecraft.displayHeight > 0) {
                eventMouseX = Mouse.getEventX() * width / minecraft.displayWidth;
                eventMouseY = height - Mouse.getEventY() * height / minecraft.displayHeight - 1;
            }
            if (uiLayout == null) {
                uiLayout = HorizonRadioUiLayout.create(width, height);
            }
            int logicalMouseX = uiLayout.toLogicalMouseX(eventMouseX);
            int logicalMouseY = uiLayout.toLogicalMouseY(eventMouseY);
            if (queueRowAt(logicalMouseX, logicalMouseY) >= 0) {
                queueScrollOffset = scroll(queueScrollOffset, playlist.size() + (isRadioActive() ? 1 : 0), wheel);
            } else if (currentTab == CHARTS_TAB) {
                chartScrollOffset = scroll(chartScrollOffset, chartResults.size(), wheel);
            } else if (currentTab == SEARCH_TAB) {
                searchScrollOffset = scroll(searchScrollOffset, displayedSearchResults().size(), wheel);
            } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                playlistScrollOffset = scroll(playlistScrollOffset, displayedPlaylistResults().size(), wheel);
            } else if (currentTab == RADIO_TAB) {
                radioScrollOffset = scroll(radioScrollOffset, displayedRadioResults().size(), wheel);
            } else {
                queueScrollOffset = scroll(queueScrollOffset, playlist.size() + (isRadioActive() ? 1 : 0), wheel);
            }
        }
        super.handleMouseInput();
    }

    /** Forge 1.7.10-compatible drag hook retained for slider/input integrations. */
    protected void mouseDragged(Minecraft minecraft, int mouseX, int mouseY) {
        if (uiLayout != null) {
            mouseX = uiLayout.toLogicalMouseX(mouseX);
            mouseY = uiLayout.toLogicalMouseY(mouseY);
        }
        updatePlaylistDrag(mouseX, mouseY);
        if (volumeSlider != null) {
            volumeSlider.mouseDragged(minecraft, mouseX, mouseY);
        }
    }

    /** Forge 1.7.10 dispatches held-mouse movement through this hook. */
    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceClick) {
        if (uiLayout != null) {
            mouseX = uiLayout.toLogicalMouseX(mouseX);
            mouseY = uiLayout.toLogicalMouseY(mouseY);
        }
        if (clickedMouseButton == 0) {
            if (draggingQueueScrollbar) {
                updateQueueScrollbarScroll(mouseY);
                return;
            }
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
        if (uiLayout != null) {
            mouseX = uiLayout.toLogicalMouseX(mouseX);
            mouseY = uiLayout.toLogicalMouseY(mouseY);
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0) {
            draggingResultScrollbar = false;
            draggingQueueScrollbar = false;
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
        if (searchField == null) {
            return;
        }
        String query = searchField.getText()
            .trim();
        if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            if (query.length() > 0 && looksLikePlaylistUrl(query)) {
                if (playlistUrlField != null) {
                    playlistUrlField.setText(query);
                    performPlaylistImport();
                }
            } else {
                playlistScrollOffset = 0;
            }
            return;
        }
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
        if (playlistLoading) {
            return;
        }
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
        playlistResults.clear();
        playlistScrollOffset = 0;
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
        playlistResults.clear();
        playlistScrollOffset = 0;
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
        return hasRegion ? "Top 50 Charts \u00B7 " + regionDisplayName : "";
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
        queueScrollOffset = Math.min(queueScrollOffset, queueMaxScrollOffset());
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

    Set<String> pendingAddIds(boolean playlistOrigin, List<String> videoIds) {
        Set<String> pendingAdds = playlistOrigin ? pendingPlaylistAdds : pendingChartAdds;
        Set<String> pendingIds = new HashSet<String>();
        if (videoIds != null) {
            for (String videoId : videoIds) {
                if (videoId != null && pendingAdds.contains(videoId)) {
                    pendingIds.add(videoId);
                }
            }
        }
        return pendingIds;
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
        return inQueue || pending ? "\u2713" : "+";
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
            playbackButton.setIcon(paused || canResumeRadio() ? ICON_PLAY : ICON_PAUSE);
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
        draggingQueueScrollbar = false;
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
        return uiLayout == null ? width / 2 - PANEL_WIDTH / 2 : uiLayout.referencePanelLeft();
    }

    private int panelTop() {
        return uiLayout == null ? height / 2 - PANEL_HEIGHT / 2 : uiLayout.referencePanelTop();
    }

    private int contentLeft(int panelLeft) {
        return panelLeft + CONTENT_LEFT_INSET;
    }

    private int contentRight(int panelLeft) {
        return contentLeft(panelLeft) + CONTENT_WIDTH - 7;
    }

    private int queuePanelLeft(int panelLeft) {
        return panelLeft + QUEUE_LEFT_INSET;
    }

    private int queuePanelRight(int panelLeft) {
        return queuePanelLeft(panelLeft) + QUEUE_WIDTH;
    }

    private int rowAt(int mouseX, int mouseY, int listTop) {
        if (mouseX < contentLeft(panelLeft()) + 5 || mouseX > contentRight(panelLeft())) {
            return -1;
        }
        int row = (mouseY - listTop) / ROW_HEIGHT;
        return mouseY >= listTop && row >= 0 && row < MAX_VISIBLE_ROWS ? row : -1;
    }

    private int queueRowAt(int mouseX, int mouseY) {
        int left = queuePanelLeft(panelLeft());
        if (mouseX < left + 3 || mouseX > queuePanelRight(panelLeft()) - 3) {
            return -1;
        }
        int listTop = panelTop() + QUEUE_LIST_TOP_OFFSET;
        int row = (mouseY - listTop) / ROW_HEIGHT;
        return mouseY >= listTop && row >= 0 && row < MAX_VISIBLE_ROWS ? row : -1;
    }

    private int queueIndexAtRow(int row) {
        return queueScrollOffset + row - (isRadioActive() ? 1 : 0);
    }

    private int queueMaxScrollOffset() {
        int visiblePlaylistRows = MAX_VISIBLE_ROWS - (isRadioActive() ? 1 : 0);
        return Math.max(0, playlist.size() - Math.max(1, visiblePlaylistRows));
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

    private String currentArtistLabel() {
        if (nowPlaying == null) {
            return "";
        }
        for (PlaylistEntry entry : playlist) {
            if (entry != null && nowPlaying.equals(entry.displayTitle()) && entry.localVideoMetadata != null) {
                return entry.localVideoMetadata.channel == null ? "" : entry.localVideoMetadata.channel;
            }
        }
        return "";
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
        return top + FOOTER_TOP_OFFSET;
    }

    private int timeBarTop(int top) {
        return nowPlayingTop(top) + 24;
    }

    private int controlTop(int nowPlayingTop) {
        return nowPlayingTop + 30;
    }

    private int controlGroupWidth() {
        return CONTROL_BUTTON_COUNT * CONTROL_BUTTON_WIDTH + (CONTROL_BUTTON_COUNT - 1) * CONTROL_BUTTON_GAP;
    }

    private int queueButtonLeft(int panelLeft) {
        int columnLeft = contentRight(panelLeft) - QUEUE_BUTTON_RIGHT_MARGIN - QUEUE_BUTTON_COLUMN_WIDTH;
        return columnLeft + (QUEUE_BUTTON_COLUMN_WIDTH - QUEUE_BUTTON_WIDTH) / 2;
    }

    private int queueRemoveButtonLeft(int panelLeft) {
        return queuePanelRight(panelLeft) - QUEUE_BUTTON_WIDTH - 6;
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
        int trackLeft = contentRight(panelLeft) - RESULT_SCROLLBAR_LEFT_OFFSET;
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
        int trackLeft = contentRight(panelLeft) - RESULT_SCROLLBAR_LEFT_OFFSET;
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
            resultCount = displayedPlaylistResults().size();
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
        drawTextButtonAt(panelLeft, top, inQueue ? "\u2713" : "+", hovered);
    }

    private void drawTextButtonAt(int panelLeft, int top, String label, boolean hovered) {
        drawTextButtonAbsolute(queueButtonLeft(panelLeft), top, label, hovered);
    }

    private void drawTextButtonAbsolute(int left, int top, String label, boolean hovered) {
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
        private boolean greenActive;
        private final int borderColor;
        private String label;
        private float labelScale = 1.0F;

        private ControlButton(int id, int x, int y, int width, int height, ResourceLocation iconTexture) {
            this(id, x, y, width, height, iconTexture, 0xFF111111);
        }

        private ControlButton(int id, int x, int y, int width, int height, ResourceLocation iconTexture,
            int borderColor) {
            super(id, x, y, width, height, "");
            this.iconTexture = iconTexture;
            this.borderColor = borderColor;
            this.label = "";
            this.greenActive = true;
        }

        private ControlButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, "");
            this.iconTexture = null;
            this.borderColor = 0xFF111111;
            this.label = label == null ? "" : label;
            this.greenActive = false;
        }

        private void setIcon(ResourceLocation iconTexture) {
            this.iconTexture = iconTexture;
        }

        private void setLabel(String label) {
            this.label = label == null ? "" : label;
        }

        private void setLabelScale(float labelScale) {
            this.labelScale = Math.max(0.5F, Math.min(1.0F, labelScale));
        }

        private void setGreenActive(boolean greenActive) {
            this.greenActive = greenActive;
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
            int outer = !enabled ? 0xFF4A4A4A
                : (active && greenActive ? 0xFF6EAA6E : (hovered ? 0xFF777777 : 0xFF5F5F5F));
            int inner = !enabled ? 0xFF383838
                : (active && greenActive ? 0xFF456B45 : (hovered ? 0xFF666666 : 0xFF4A4A4A));
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
                if (labelScale == 1.0F) {
                    drawCenteredString(
                        minecraft.fontRenderer,
                        label,
                        xPosition + width / 2,
                        yPosition + (height - 8) / 2 + 1,
                        0xFFFFFFFF);
                } else {
                    GL11.glPushMatrix();
                    GL11.glTranslatef(xPosition + width / 2.0F, yPosition + height / 2.0F, 0.0F);
                    GL11.glScalef(labelScale, labelScale, 1.0F);
                    drawCenteredString(minecraft.fontRenderer, label, 0, -4, 0xFFFFFFFF);
                    GL11.glPopMatrix();
                }
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
        int barLeft = contentLeft(left) + 5;
        int barRight = contentRight(left);
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
        return (isRadioActive() || canResumeRadio()) && playbackButton != null && playbackButton.visible;
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
        return radioState != null && radioState.getStationUuid() != null
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
        setEnabled(previousButton, true);
        setEnabled(playbackButton, true);
        setEnabled(nextButton, true);
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
        List<SearchResult> results = displayedPlaylistResults();
        if (results.isEmpty()) {
            return false;
        }
        for (SearchResult result : results) {
            if (!isInQueue(result.videoId)) {
                return false;
            }
        }
        return true;
    }

    private boolean areAllPlaylistResultsInQueueOrPending() {
        List<SearchResult> results = displayedPlaylistResults();
        if (results.isEmpty()) {
            return false;
        }
        for (SearchResult result : results) {
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

    private void performBulkAdd() {
        boolean charts = currentTab == CHARTS_TAB;
        List<SearchResult> results = charts ? chartResults : displayedPlaylistResults();
        if (results.isEmpty()) {
            return;
        }
        boolean playlistTransport = !charts && !playlistResults.isEmpty();
        if (charts ? areAllChartsInQueue() : areAllPlaylistResultsInQueue()) {
            if (playlistTransport) {
                HorizonRadioClient.sendPlaylistResultsToQueue(toPlaylistSelections(results), true);
            } else {
                HorizonRadioClient.sendAddChartsToPlaylist(toPlaylistSelections(results), true);
            }
            return;
        }
        List<SearchResult> request = charts ? beginChartAdd(results) : beginPlaylistAdd(results);
        if (request.isEmpty()) {
            return;
        }
        if (playlistTransport) {
            HorizonRadioClient.sendPlaylistResultsToQueue(request);
        } else {
            HorizonRadioClient.sendAddChartsToPlaylist(request);
        }
    }

    private void playResultNow(SearchResult result) {
        if (result == null) {
            return;
        }
        HorizonRadioClient.sendPlayNow(result);
    }

    private boolean usesSharedSearchField() {
        return currentTab == CHARTS_TAB || currentTab == SEARCH_TAB
            || currentTab == PLAYLIST_DISCOVERY_TAB
            || currentTab == RADIO_TAB;
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
        int row = queueRowAt(mouseX, mouseY);
        int index = row < 0 ? -1 : queueIndexAtRow(row);
        return index >= 0 && index < playlist.size() ? index : -1;
    }

    private boolean isPlaylistIndexDraggable(int index) {
        return index >= 0 && index < playlist.size() && !(index == 0 && nowPlaying != null);
    }

    static boolean isPlaylistRowPlaying(int index, boolean hasNowPlaying, boolean radioActive) {
        return index == 0 && hasNowPlaying;
    }

    private boolean isPlaylistRowPlaying(int index, PlaylistEntry entry) {
        if (index != 0 || entry == null) {
            return false;
        }
        if (entry.sourceType == MediaSourceType.RADIO) {
            return isRadioActive() && radioState != null
                && radioState.getStationUuid()
                    .equals(entry.sourceId);
        }
        return entry.isFinite() && nowPlaying != null && !isRadioActive();
    }

    private int playlistListTop(int top) {
        return top + PLAYLIST_LIST_TOP_OFFSET;
    }

    private int playlistDiscoveryListTop(int top) {
        return top + CONTENT_LIST_TOP_OFFSET;
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
        return CONTENT_WIDTH - 60;
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
        return panelLeft + CONTENT_LEFT_INSET + CONTENT_WIDTH - 49;
    }

    private String truncate(String text, int maxWidth) {
        text = text == null ? "" : text;
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
