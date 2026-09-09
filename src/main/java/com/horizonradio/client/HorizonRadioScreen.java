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
import org.lwjgl.opengl.GL13;

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
    private static final int SEARCH_MAX_VISIBLE_ROWS = 7;
    private static final int RADIO_MAX_VISIBLE_ROWS = 8;
    private static final int QUEUE_MAX_VISIBLE_ROWS = 8;
    private static final int ROW_HEIGHT = 22;
    private static final int NOW_PLAYING_HEIGHT = 22;
    private static final int NOW_PLAYING_PANEL_INSET = 8;
    private static final int NOW_PLAYING_PANEL_HEIGHT = 73;
    private static final int NOW_PLAYING_CONTENT_MARGIN = 5;
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
    private static final int RESULT_GLYPH_LEFT_INSET = 5;
    private static final int RESULT_GLYPH_AREA_WIDTH = 18;
    private static final int RESULT_GLYPH_HEIGHT = 8;
    /* The content column is deliberately narrower than the complete panel. */
    private static final int CONTENT_LEFT_INSET = 8;
    private static final int CONTENT_WIDTH = 220;
    private static final int QUEUE_LEFT_INSET = 232;
    private static final int QUEUE_WIDTH = 120;
    private static final int BODY_TOP_OFFSET = 36;
    private static final int BODY_BOTTOM_OFFSET = 245;
    private static final int FOOTER_TOP_OFFSET = 251;
    private static final int VOLUME_TOP_OFFSET = 328;
    private static final int SONGS_TAB_X = 262;
    private static final int RADIO_TAB_X = 298;
    private static final int MODE_SEARCH_X = 13;
    private static final int MODE_CHARTS_X = 49;
    private static final int MODE_PLAYLISTS_X = 85;
    static final float UI_TEXT_SCALE = 0.80F;
    static final float MODE_BUTTON_TEXT_SCALE = UI_TEXT_SCALE;
    private static final int MODE_TOP_OFFSET = BODY_TOP_OFFSET + 5;
    private static final int SECTION_TOP_OFFSET = 90;
    private static final int CONTENT_LIST_TOP_OFFSET = 103;
    private static final int CHART_CONTENT_Y_OFFSET = 3;
    private static final int CHART_LIST_TOP_OFFSET = CONTENT_LIST_TOP_OFFSET + CHART_CONTENT_Y_OFFSET;
    private static final int SEARCH_RESULT_VERTICAL_MARGIN = 6;
    private static final int RADIO_SEARCH_CONTROL_Y_OFFSET = MODE_TOP_OFFSET;
    private static final int RADIO_LIST_TOP_OFFSET = RADIO_SEARCH_CONTROL_Y_OFFSET + SEARCH_CONTROL_HEIGHT + 5;
    private static final int RADIO_LIST_TOP_WITH_PROGRESS_OFFSET = RADIO_LIST_TOP_OFFSET;
    private static final int QUEUE_TOP_OFFSET = 41;
    private static final int QUEUE_LIST_TOP_OFFSET = 65;
    private static final int VOLUME_HEIGHT = 11;
    private static final int SEARCH_BUTTON_BORDER_COLOR = 0xFFA0A0A0;
    private static final int SEARCH_BUTTON_HEIGHT = SEARCH_CONTROL_HEIGHT + 2;
    private static final int SEARCH_BUTTON_Y_OFFSET = SEARCH_CONTROL_Y_OFFSET - 1;
    private static final int CHARTS_BULK_BUTTON_Y_OFFSET = 84 + CHART_CONTENT_Y_OFFSET;
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
    private static final ResourceLocation ICON_SETTINGS = new ResourceLocation(
        "horizonradio",
        "textures/gui/Settings.png");
    private static final ResourceLocation ICON_SEARCH = new ResourceLocation("horizonradio", "textures/gui/Search.png");
    private static final ResourceLocation ICON_FAVORITE = new ResourceLocation(
        "horizonradio",
        "textures/gui/Favorite.png");
    private static final ResourceLocation ICON_LOGO = new ResourceLocation(
        "horizonradio",
        "textures/gui/HorizonRadioLogoClean.png");
    private static final int BUTTON_SONGS_TAB = 15;
    private static final int BUTTON_MODE_PLAYLISTS = 16;
    private static final int SEARCH_PROGRESS_Y_OFFSET = BODY_TOP_OFFSET + 42;
    private static final int SEARCH_PROGRESS_HEIGHT = 6;
    private static final int SEARCH_LIST_TOP_OFFSET = SEARCH_CONTROL_Y_OFFSET + SEARCH_CONTROL_HEIGHT
        + SEARCH_RESULT_VERTICAL_MARGIN;
    private static final int SEARCH_LIST_TOP_WITHOUT_PROGRESS_OFFSET = SEARCH_LIST_TOP_OFFSET;
    private static final int CONTENT_HEADER_Y_OFFSET = SECTION_TOP_OFFSET;
    private static final int CONTENT_LABEL_Y_OFFSET = SECTION_TOP_OFFSET;
    private static final int PLAYLIST_HEADER_Y_OFFSET = 25;
    private static final int PLAYLIST_TITLE_Y_OFFSET = 31;
    private static final int PLAYLIST_LIST_TOP_OFFSET = 45;
    private static final int RESULT_DURATION_COLUMN_WIDTH = 40;
    private static final int RESULT_SCROLLBAR_WIDTH = 3;
    private static final int RESULT_SCROLLBAR_LEFT_OFFSET = 0;
    private static final int RESULT_SCROLLBAR_MIN_THUMB_HEIGHT = 10;
    private static final long SEARCH_PROGRESS_ESTIMATE_MILLIS = 1500L;
    private static final long CHART_PROGRESS_ESTIMATE_MILLIS = 1000L;
    private static final long RADIO_PROGRESS_ESTIMATE_MILLIS = 400L;
    private static final long RESULT_REVEAL_DELAY_MILLIS = 150L;
    private static final int TIME_BAR_SIDE_SPACE = 40;
    private static final int TIME_BAR_LABEL_LEFT_OFFSET = 13;
    private static final int TIME_BAR_HEIGHT = 5;
    private static final int TIME_BAR_TRACK_COLOR = 0xFF3C3C3C;
    private static final int TIME_BAR_PROGRESS_COLOR = 0xFF79D38A;
    private static final String FAVORITE_GLYPH = "\u2661";

    private GuiTextField searchField;
    private GuiTextField playlistUrlField;
    private ControlButton searchButton;
    private ControlButton refreshChartsButton;
    private GuiButton settingsButton;
    private HorizonRadioVolumeSlider volumeSlider;
    private List<SearchResult> chartResults = new ArrayList<SearchResult>();
    private boolean chartSearchStarted;
    private final Set<String> pendingChartAdds = new HashSet<String>();
    private final Set<String> pendingPlaylistAdds = new HashSet<String>();
    private String chartRegionCode = "";
    private String chartSearchMessage = "";
    private List<SearchResult> searchResults = new ArrayList<SearchResult>();
    private String searchError = "";
    private List<SearchResult> playlistResults = new ArrayList<SearchResult>();
    private boolean playlistSearchStarted;
    private boolean playlistSearchMode;
    private boolean playlistLoading;
    private String playlistError = "";
    private String playlistTitle = "";
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
    private String observedSearchText = "";
    private int observedSearchTab = SEARCH_TAB;
    private String musicSearchText = "";
    private String radioSearchText = "";
    private long autoSearchAt = -1L;
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
    private long queueDragScrollAt;
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
        chartSearchStarted = !chartResults.isEmpty();
        chartRegionCode = normalizeChartRegionCode(HorizonRadioClient.getCachedChartRegionCode());
        playlistResults = HorizonRadioClient.getCachedPlaylistResults();
        playlistSearchMode = false;
        playlistTitle = HorizonRadioClient.getCachedPlaylistTitle();
        playlistSearchStarted = !playlistResults.isEmpty();
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
            "Playlist");
        addButton(playlistsTabButton);
        songsTabButton = createTextButton(BUTTON_SONGS_TAB, panelLeft + SONGS_TAB_X, panelTop + TAB_BUTTON_Y, "Songs");
        addButton(songsTabButton);
        radioTabButton = createTextButton(BUTTON_RADIO_TAB, panelLeft + RADIO_TAB_X, panelTop + TAB_BUTTON_Y, "Radio");
        addButton(radioTabButton);
        refreshChartsButton = new ControlButton(
            BUTTON_REFRESH_CHARTS,
            queueButtonLeft(panelLeft) - QUEUE_BUTTON_WIDTH - SEARCH_CONTROL_GAP,
            panelTop + CHARTS_BULK_BUTTON_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            ICON_LOOP);
        addButton(refreshChartsButton);
        bulkAddButton = new ControlButton(
            BUTTON_BULK_ADD,
            queueButtonLeft(panelLeft),
            panelTop + CHARTS_BULK_BUTTON_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            "+");
        bulkAddButton.setGreenActive(true);
        addButton(bulkAddButton);
        settingsButton = new ControlButton(
            BUTTON_SETTINGS,
            panelLeft + 335,
            panelTop + TAB_BUTTON_Y,
            17,
            17,
            ICON_SETTINGS);
        addButton(settingsButton);
        queueClearButton = new ControlButton(
            BUTTON_QUEUE_CLEAR,
            panelLeft + QUEUE_LEFT_INSET + QUEUE_WIDTH - QUEUE_BUTTON_WIDTH - 6,
            panelTop + QUEUE_TOP_OFFSET - 2,
            QUEUE_BUTTON_WIDTH,
            TAB_BUTTON_HEIGHT,
            "×");
        addButton(queueClearButton);
        updateQueueClearButtonVisibility();
        addControlButtons(panelLeft, panelTop);
        volumeSlider = new HorizonRadioVolumeSlider(
            3,
            timeBarLeft(panelLeft) - 2,
            panelTop + VOLUME_TOP_OFFSET + 2,
            timeBarWidth() + 4,
            VOLUME_HEIGHT,
            HorizonRadioClient.getVolume());
        addButton(volumeSlider);
        currentTab = SEARCH_TAB;
        observedSearchText = searchField.getText();
        autoSearchAt = -1L;
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
        drawMediaStatus(left, nowPlayingTop);
        drawControlCenter(left, nowPlayingTop);
        drawVolumeLabels(left, top);

        searchButton.visible = showsSearchButton();
        searchButton.enabled = currentTab != PLAYLIST_DISCOVERY_TAB || !playlistLoading;
        updateChartControlVisibility();
        boolean showBulkAdd = bulkAddButton.visible;
        boolean bulkAddComplete = currentTab == CHARTS_TAB ? areAllChartsInQueueOrPending()
            : areAllPlaylistResultsInQueueOrPending();
        bulkAddButton.enabled = showBulkAdd && !bulkAddComplete
            && !(currentTab == CHARTS_TAB ? isChartResultsLoading() : isPlaylistResultsLoading());
        bulkAddButton.setLabel(bulkAddComplete ? "\u2713" : "+");
        bulkAddButton.setActive(bulkAddComplete);
        updateQueueClearButtonVisibility();
        songsTabButton.setActive(currentTab != RADIO_TAB);
        radioTabButton.setActive(currentTab == RADIO_TAB);
        chartsTabButton.setActive(currentTab == CHARTS_TAB);
        searchTabButton.setActive(currentTab == SEARCH_TAB);
        playlistsTabButton.setActive(currentTab == PLAYLIST_DISCOVERY_TAB);
        updateModeVisibility();
        updateChartRefreshButtonState();
        updateControlVisibility();
        if (usesSharedSearchField()) {
            drawScaledTextField(searchField);
            if (searchField.getText()
                .trim()
                .length() == 0 && !searchField.isFocused()) {
                drawUiString(searchPlaceholder(), searchField.xPosition + 4, searchField.yPosition + 5, 0xFF8D8D8D);
            }
        } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            drawScaledTextField(playlistUrlField);
        }
        super.drawScreen(logicalMouseX, logicalMouseY, partialTicks);
        drawPanelBorder(left, top);
        drawActiveTabBorder(left, top);
        drawQueueDragPreview(logicalMouseX, logicalMouseY);
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

    static float uiTextScale() {
        return UI_TEXT_SCALE;
    }

    static int uiTextWidth(int fontWidth) {
        return Math.round(Math.max(0, fontWidth) * UI_TEXT_SCALE);
    }

    private void drawUiString(String text, int x, int y, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glScalef(UI_TEXT_SCALE, UI_TEXT_SCALE, 1.0F);
        drawString(fontRendererObj, text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private void drawUiCenteredString(String text, int centerX, int y, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX, y, 0.0F);
        GL11.glScalef(UI_TEXT_SCALE, UI_TEXT_SCALE, 1.0F);
        drawCenteredString(fontRendererObj, text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private void drawScaledTextField(GuiTextField field) {
        int x = field.xPosition;
        int y = field.yPosition;
        int width = field.width;
        int height = field.height;
        drawRect(x - 1, y - 1, x + width + 1, y + height + 1, 0xFFA0A0A0);
        drawRect(x, y, x + width, y + height, 0xFF000000);

        boolean drawsBackground = field.getEnableBackgroundDrawing();
        field.setEnableBackgroundDrawing(false);
        field.xPosition = x + 4;
        field.yPosition = y + (height - 8) / 2;
        field.width = Math.max(1, width - 8);
        GL11.glPushMatrix();
        GL11.glTranslatef(field.xPosition, field.yPosition, 0.0F);
        GL11.glScalef(UI_TEXT_SCALE, UI_TEXT_SCALE, 1.0F);
        GL11.glTranslatef(-field.xPosition, -field.yPosition, 0.0F);
        field.drawTextBox();
        GL11.glPopMatrix();
        field.xPosition = x;
        field.yPosition = y;
        field.width = width;
        field.height = height;
        field.setEnableBackgroundDrawing(drawsBackground);
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
    }

    private void drawPanelBoxBorder(int left, int top, int right, int bottom) {
        drawRect(left, top, right, top + 1, 0xFF555555);
        drawRect(left, bottom - 1, right, bottom, 0xFF555555);
        drawRect(left, top, left + 1, bottom, 0xFF555555);
        drawRect(right - 1, top, right, bottom, 0xFF555555);
    }

    private void drawHeader(int left, int top) {
        drawGradientRect(left + 3, top + 3, left + PANEL_WIDTH - 3, top + BODY_TOP_OFFSET, 0xFF202020, 0xFF1B1B1B);
        drawRect(left + 8, top + BODY_TOP_OFFSET - 3, left + PANEL_WIDTH - 8, top + BODY_TOP_OFFSET - 2, 0xFF555555);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(ICON_LOGO);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        // Screen blending makes the clean logo's black backdrop invisible without an outer halo.
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        Gui.func_152125_a(left + 10, top + 11, 0, 210, 1853, 370, 94, 20, 1853, 849);
        GL11.glPopAttrib();
    }

    private void drawVolumeLabels(int left, int top) {
        int textHeight = uiTextWidth(fontRendererObj.FONT_HEIGHT);
        int labelTop = (volumeSlider == null ? top + VOLUME_TOP_OFFSET + 2 : volumeSlider.yPosition)
            + (VOLUME_HEIGHT - textHeight) / 2;
        drawUiString("VOL", nowPlayingContentLeft(left), labelTop, 0xFFB3B3B3);
        int value = volumeSlider == null ? Math.round(HorizonRadioClient.getVolume() * 100.0f)
            : Math.round(volumeSlider.getValue() * 100.0f);
        String valueText = value + "%";
        drawUiString(
            valueText,
            left + PANEL_WIDTH - 13 - uiTextWidth(fontRendererObj.getStringWidth(valueText)),
            labelTop,
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
            : (currentTab == SEARCH_TAB ? searchTabButton
                : (currentTab == PLAYLIST_DISCOVERY_TAB ? playlistsTabButton : null));
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
            String chartHeader = chartResults.isEmpty() ? ""
                : chartHeaderLabel(hasChartRegion(), chartRegionDisplayName());
            if (chartSearchMessage.length() > 0) {
                drawUiString(
                    chartSearchMessage,
                    contentLeft(left) + 5,
                    top + CONTENT_LABEL_Y_OFFSET + CHART_CONTENT_Y_OFFSET,
                    0xFFFF7777);
            } else if (chartHeader.length() > 0) {
                drawUiString(
                    chartHeader,
                    contentLeft(left) + 5,
                    top + CONTENT_LABEL_Y_OFFSET + CHART_CONTENT_Y_OFFSET,
                    0xFFF0F0F0);
            }
        }
        drawResultList(
            resultsLoading ? Collections.<SearchResult>emptyList() : chartResults,
            chartScrollOffset,
            left,
            top + CHART_LIST_TOP_OFFSET,
            mouseX,
            mouseY,
            resultsLoading ? "Loading charts..."
                : (chartError.length() > 0 ? chartError
                    : (chartSearchStarted && hasChartRegion() ? "No charts available" : "")));
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
            isEmptySearchQuery() ? "No favorite songs yet"
                : (resultsLoading ? "Searching..." : (searchError.length() > 0 ? searchError : "No songs found")));
    }

    private void drawPlaylistDiscoveryTab(int left, int top, int mouseX, int mouseY) {
        boolean resultsLoading = isPlaylistResultsLoading();
        List<SearchResult> results = displayedPlaylistResults();
        if (shouldDrawProgressBar(resultsLoading)) {
            drawProgressBar(left, top, playlistProgress);
        }
        if (hasVisiblePlaylistResults()) {
            int labelLeft = contentLeft(left) + 5;
            drawUiString(
                truncateUi(
                    playlistSearchMode ? "Playlists" : playlistTitle.isEmpty() ? "YouTube Playlist" : playlistTitle,
                    queueButtonLeft(left) - labelLeft - 5),
                labelLeft,
                top + CONTENT_LABEL_Y_OFFSET + CHART_CONTENT_Y_OFFSET,
                0xFFF0F0F0);
        }
        String emptyMessage = resultsLoading ? (playlistSearchMode ? "Searching playlists..." : "Loading playlist...")
            : (playlistError.length() > 0 ? playlistError
                : (playlistSearchStarted ? (playlistSearchMode ? "No playlists found" : "No songs found") : ""));
        drawResultList(
            resultsLoading ? Collections.<SearchResult>emptyList() : results,
            playlistScrollOffset,
            left,
            playlistDiscoveryListTop(top),
            mouseX,
            mouseY,
            emptyMessage);
    }

    private List<SearchResult> displayedPlaylistResults() {
        return new ArrayList<SearchResult>(playlistResults);
    }

    private void drawQueuePanel(int left, int top, int mouseX, int mouseY) {
        updateQueueDragScroll(mouseX, mouseY, System.currentTimeMillis());
        int queueLeft = left + QUEUE_LEFT_INSET;
        int headerTop = top + QUEUE_TOP_OFFSET;
        int rowTop = top + QUEUE_LIST_TOP_OFFSET;
        int queueCount = playlist.size() + (hasStandaloneRadioRow() ? 1 : 0);
        drawUiString("QUEUE", queueLeft + 7, headerTop + 3, 0xFFF2F2F2);
        drawUiString(
            "(" + queueCount + "/" + HorizonRadioClient.queueLimit() + ")",
            queueLeft + 45,
            headerTop + 3,
            0xFF8F9A91);
        drawRect(
            queueLeft + 5,
            headerTop + TAB_BUTTON_HEIGHT + 2,
            queueLeft + QUEUE_WIDTH - 5,
            headerTop + TAB_BUTTON_HEIGHT + 3,
            0xFF4B4B4B);

        int dropIndex = queueDropIndex(mouseX, mouseY);
        boolean preview = playlistDragMoved && dropIndex >= 0;
        int renderedRows = 0;
        if (hasStandaloneRadioRow() && !(preview && dropIndex == 0)) {
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
        for (int index = queueScrollOffset; index < playlist.size() && renderedRows < QUEUE_MAX_VISIBLE_ROWS; index++) {
            int sourceIndex = preview ? queuePreviewSourceIndex(index, draggedPlaylistIndex, dropIndex) : index;
            // Immediate playback replaces the current song instead of queueing it again.
            if (preview && dropIndex == 0
                && index != dropIndex
                && sourceIndex == 0
                && nowPlaying != null
                && !hasStandaloneRadioRow()) {
                continue;
            }
            int displayRow = renderedRows++;
            if (preview && index == dropIndex) {
                int gapTop = rowTop + displayRow * ROW_HEIGHT;
                int targetPosition = index + 1 + (hasStandaloneRadioRow() ? 1 : 0);
                drawRect(queueLeft + 5, gapTop, queueLeft + QUEUE_WIDTH - 6, gapTop + ROW_HEIGHT - 1, 0xFF263F30);
                drawRect(queueLeft + 5, gapTop, queueLeft + QUEUE_WIDTH - 6, gapTop + 2, 0xFF79D38A);
                drawUiString(
                    index == 0 ? "1 - Play now" : targetPosition + " - Move to #" + targetPosition,
                    queueLeft + 9,
                    gapTop + 7,
                    0xFFB9F1C5);
                continue;
            }
            PlaylistEntry entry = playlist.get(sourceIndex);
            if (entry == null) {
                continue;
            }
            boolean activeRadio = isRadioActive() && entry.sourceType == MediaSourceType.RADIO
                && radioState.getStationUuid()
                    .equals(entry.sourceId);
            drawQueueRow(
                queueLeft,
                rowTop,
                displayRow,
                activeRadio ? radioState.getStationName() : entry.displayTitle(),
                entry.addedBy,
                activeRadio ? "LIVE" : entry.displayDuration(),
                isPlaylistRowPlaying(sourceIndex, entry),
                mouseX,
                mouseY,
                false);
        }
        if (queueCount == 0) {
            drawUiString("Queue is empty", queueLeft + 8, rowTop + 10, 0xFF999999);
        }
        if (queueCount > QUEUE_MAX_VISIBLE_ROWS) {
            drawQueueScrollbar(queueLeft, rowTop, queueCount);
        }
    }

    static int queuePreviewSourceIndex(int position, int fromIndex, int targetIndex) {
        if (position == targetIndex) {
            return fromIndex;
        }
        if (fromIndex < targetIndex && position >= fromIndex && position < targetIndex) {
            return position + 1;
        }
        if (fromIndex > targetIndex && position > targetIndex && position <= fromIndex) {
            return position - 1;
        }
        return position;
    }

    void updateQueueDragScroll(int mouseX, int mouseY, long now) {
        int row = queueRowAt(mouseX, mouseY);
        if (!playlistDragMoved || !isPlaylistIndexDraggable(draggedPlaylistIndex)
            || (row != 0 && row != QUEUE_MAX_VISIBLE_ROWS - 1)) {
            queueDragScrollAt = 0L;
            return;
        }
        if (queueDragScrollAt == 0L) {
            queueDragScrollAt = now + 250L;
        } else if (now >= queueDragScrollAt) {
            queueScrollOffset = Math.max(0, Math.min(queueMaxScrollOffset(), queueScrollOffset + (row == 0 ? -1 : 1)));
            queueDragScrollAt = now + 250L;
        }
    }

    private int queueDropIndex(int mouseX, int mouseY) {
        if (!playlistDragMoved || !isPlaylistIndexDraggable(draggedPlaylistIndex)) {
            return -1;
        }
        int row = queueRowAt(mouseX, mouseY);
        if (row < 0 || playlist.isEmpty()) {
            return -1;
        }
        return Math.max(0, Math.min(playlist.size() - 1, queueIndexAtRow(row)));
    }

    private void drawQueueDragPreview(int mouseX, int mouseY) {
        if (!playlistDragMoved || draggedPlaylistEntry == null || !isPlaylistIndexDraggable(draggedPlaylistIndex)) {
            return;
        }
        int cardLeft = queuePanelLeft(panelLeft()) + 5 + mouseX - dragStartMouseX;
        int cardTop = mouseY - Math.floorMod(dragStartMouseY - panelTop() - QUEUE_LIST_TOP_OFFSET, ROW_HEIGHT);
        int cardRight = cardLeft + QUEUE_WIDTH - 11;
        int accent = queueDropIndex(mouseX, mouseY) >= 0 ? 0xFF79D38A : 0xFFAAAAAA;
        drawRect(cardLeft + 2, cardTop + 2, cardRight + 2, cardTop + ROW_HEIGHT + 1, 0xAA000000);
        drawRect(cardLeft, cardTop, cardRight, cardTop + ROW_HEIGHT - 1, 0xFF35443A);
        drawRect(cardLeft, cardTop, cardLeft + 2, cardTop + ROW_HEIGHT - 1, accent);
        drawUiString(
            truncateUi(draggedPlaylistEntry.displayTitle(), cardRight - cardLeft - 10),
            cardLeft + 5,
            cardTop + 3,
            0xFFFFFFFF);
        drawUiString(
            truncateUi(draggedPlaylistEntry.addedBy, cardRight - cardLeft - 10),
            cardLeft + 5,
            cardTop + 13,
            0xFFBACABB);
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
        drawUiString(
            String.valueOf(row + 1 + (radioRow ? 0 : queueScrollOffset)),
            queueLeft + 6,
            y + 7,
            active ? 0xFFC3F1C7 : 0xFF999999);
        int textLeft = queueLeft + 17;
        int removeLeft = queueLeft + QUEUE_WIDTH - QUEUE_BUTTON_WIDTH - 6;
        int textWidth = removeLeft - 4 - textLeft - 17;
        drawUiString(truncateUi(title, textWidth), textLeft, y + 3, active ? 0xFFE3F5E4 : 0xFFE6E6E6);
        drawUiString(truncateUi(artist, textWidth), textLeft, y + 13, active ? 0xFFB9D5BC : 0xFF999999);
        drawUiString(
            duration,
            removeLeft - uiTextWidth(fontRendererObj.getStringWidth(duration)) - 3,
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
        int trackHeight = QUEUE_MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight, QUEUE_MAX_VISIBLE_ROWS);
        int thumbTop = resultScrollbarThumbTop(
            resultCount,
            queueScrollOffset,
            listTop,
            trackHeight,
            thumbHeight,
            QUEUE_MAX_VISIBLE_ROWS);
        drawRect(trackLeft, listTop, trackLeft + 2, listTop + trackHeight, 0x66555555);
        drawRect(trackLeft, thumbTop, trackLeft + 2, thumbTop + thumbHeight, 0xFFDDDDDD);
    }

    private boolean isQueueScrollbarAt(int queueLeft, int listTop, int resultCount, int mouseX, int mouseY) {
        if (resultCount <= QUEUE_MAX_VISIBLE_ROWS) {
            return false;
        }
        int trackLeft = queueLeft + QUEUE_WIDTH - 4;
        int trackHeight = QUEUE_MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
        return isMouseOver(trackLeft - 1, listTop, 4, trackHeight, mouseX, mouseY);
    }

    private void updateQueueScrollbarScroll(int mouseY) {
        int resultCount = playlist.size() + (hasStandaloneRadioRow() ? 1 : 0);
        int listTop = panelTop() + QUEUE_LIST_TOP_OFFSET;
        int trackHeight = QUEUE_MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight, QUEUE_MAX_VISIBLE_ROWS);
        int maxOffset = Math.max(0, resultCount - QUEUE_MAX_VISIBLE_ROWS);
        int maxThumbTop = Math.max(1, trackHeight - thumbHeight);
        int desiredThumbTop = Math.max(listTop, Math.min(listTop + maxThumbTop, mouseY - queueScrollbarDragOffset));
        queueScrollOffset = (desiredThumbTop - listTop) * maxOffset / maxThumbTop;
        queueScrollOffset = Math.max(0, Math.min(queueScrollOffset, queueMaxScrollOffset()));
    }

    private List<SearchResult> displayedSearchResults() {
        if (searchField == null) {
            return new ArrayList<SearchResult>(searchResults);
        }
        if (!isEmptySearchQuery() && autoSearchAt < 0L) {
            return FavoriteResultComposer
                .composeSongs(HorizonRadioClient.getFavoriteSongs(), searchResults, searchField.getText());
        }
        return FavoriteResultComposer
            .composeSongs(HorizonRadioClient.getFavoriteSongs(), Collections.<SearchResult>emptyList());
    }

    private List<RadioStationResult> displayedRadioResults() {
        if (!isEmptySearchQuery() && autoSearchAt < 0L) {
            return FavoriteResultComposer
                .composeRadios(HorizonRadioClient.getFavoriteRadios(), radioResults, searchField.getText());
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
        int listTop = top + radioListTopOffset(resultsLoading);
        if (results.isEmpty()) {
            String emptyMessage = resultsLoading ? "Loading stations..."
                : (radioError.length() > 0 ? radioError : "No radio stations available");
            drawUiCenteredString(emptyMessage, contentLeft + CONTENT_WIDTH / 2, listTop + 20, 0xFF888888);
            return;
        }
        for (int row = 0; row < RADIO_MAX_VISIBLE_ROWS && radioScrollOffset + row < results.size(); row++) {
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
            int liveLeft = contentRight - 5 - uiTextWidth(fontRendererObj.getStringWidth("LIVE"));
            int textLeft = contentLeft + RESULT_GLYPH_LEFT_INSET + RESULT_GLYPH_AREA_WIDTH;
            int textWidth = liveLeft - 5 - textLeft;
            drawResultGlyph(
                HorizonRadioClient.isRadioFavorite(station.stationUuid),
                contentLeft,
                y,
                active ? 0xFFC3F1C7 : 0xFFB0B0B0);
            drawUiString(truncateUi(station.name, textWidth), textLeft, y + 3, textColor);
            drawUiString("LIVE", liveLeft, y + 3, active ? 0xFFC3F1C7 : 0xFF999999);
        }
        drawResultScrollbar(results.size(), radioScrollOffset, left, listTop);
    }

    private void drawResultList(List<SearchResult> results, int scrollOffset, int left, int listTop, int mouseX,
        int mouseY, String emptyMessage) {
        int contentLeft = contentLeft(left);
        int contentRight = contentRight(left);
        if (results.isEmpty()) {
            drawUiCenteredString(emptyMessage, contentLeft + CONTENT_WIDTH / 2, listTop + 20, 0xFF888888);
            return;
        }
        for (int row = 0; row < resultVisibleRows() && scrollOffset + row < results.size(); row++) {
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
            if (currentTab == PLAYLIST_DISCOVERY_TAB && playlistSearchMode) {
                int textLeft = contentLeft + 10;
                drawUiString(truncateUi(result.title, contentRight - textLeft - 22), textLeft, y + 3, 0xFFF2F2F2);
                String detail = result.channel + (result.channel.isEmpty() || result.duration.isEmpty() ? "" : " - ")
                    + result.duration;
                drawUiString(truncateUi(detail, contentRight - textLeft - 22), textLeft, y + 13, 0xFFAAAAAA);
                drawUiString(">", contentRight - 12, y + 7, 0xFFCCCCCC);
                continue;
            }
            int queueButtonLeft = queueButtonLeft(left);
            int durationLeft = queueButtonLeft - 5 - uiTextWidth(fontRendererObj.getStringWidth(result.duration));
            int textRight = durationLeft - 5;
            int resultTextLeft = contentLeft + RESULT_GLYPH_LEFT_INSET + RESULT_GLYPH_AREA_WIDTH;
            int textWidth = textRight - resultTextLeft;
            drawResultGlyph(
                HorizonRadioClient.isSongFavorite(result.videoId),
                contentLeft,
                y,
                active ? 0xFFC3F1C7 : 0xFFB0B0B0);
            drawUiString(truncateUi(result.title, textWidth), resultTextLeft, y + 3, active ? 0xFFE3F5E4 : 0xFFF2F2F2);
            drawUiString(
                result.duration,
                queueButtonLeft - uiTextWidth(fontRendererObj.getStringWidth(result.duration)) - 5,
                y + 3,
                0xFFB3B3B3);
            drawUiString(
                truncateUi(result.channel, textWidth),
                resultTextLeft,
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

    private void drawResultGlyph(boolean favorite, int contentLeft, int rowTop, int color) {
        int glyphAreaLeft = contentLeft + RESULT_GLYPH_LEFT_INSET;
        int glyphCenterX = glyphAreaLeft + RESULT_GLYPH_AREA_WIDTH / 2;
        int glyphTop = rowTop + (ROW_HEIGHT - RESULT_GLYPH_HEIGHT) / 2;
        drawUiCenteredString(searchResultGlyph(favorite), glyphCenterX, glyphTop, color);
    }

    private void drawPlaylistTab(int left, int top, int mouseX, int mouseY) {
        int listTop = playlistListTop(top);
        drawUiString(
            "Queue (" + playlist.size() + " von " + HorizonRadioClient.queueLimit() + ")",
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
            drawUiCenteredString("Playlist is empty", left + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            drawUiCenteredString("Search and add songs!", left + PANEL_WIDTH / 2, listTop + 35, 0xFF666666);
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
            drawUiString((index + 1) + ".", left + 15, y + 8, 0xFFAAAAAA);
            boolean canRemove = true;
            int titleWidth = queueButtonLeft(left) - 5 - (left + 35);
            drawUiString(truncateUi(entry.displayTitle(), titleWidth), left + 35, y + 4, 0xFFFFFFFF);
            drawUiString("by " + entry.addedBy, left + 35, y + 14, 0xFF888888);
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

    private void drawMediaStatus(int left, int top) {
        String message = HorizonRadioClient.mediaStatusMessage();
        if (message.isEmpty()) return;
        int x = nowPlayingContentLeft(left);
        int right = nowPlayingContentRight(left);
        int y = top + 39;
        drawRect(x, y, right, y + 10, 0xFF402A25);
        drawUiString(truncateUi(message, right - x - 18), x + 3, y + 1, 0xFFFFB09A);
        drawUiString("x", right - 8, y + 1, 0xFFFFB09A);
    }

    private void drawNowPlaying(int left, int y) {
        drawRect(
            left + NOW_PLAYING_PANEL_INSET,
            y,
            left + PANEL_WIDTH - NOW_PLAYING_PANEL_INSET,
            y + NOW_PLAYING_PANEL_HEIGHT,
            0xFF181818);
        drawPanelBoxBorder(
            left + NOW_PLAYING_PANEL_INSET,
            y,
            left + PANEL_WIDTH - NOW_PLAYING_PANEL_INSET,
            y + NOW_PLAYING_PANEL_HEIGHT);
        int contentLeft = nowPlayingContentLeft(left);
        int contentRight = nowPlayingContentRight(left);
        int contentWidth = contentRight - contentLeft;
        int contentTop = nowPlayingContentTop(y);
        boolean radioActive = isRadioActive();
        boolean pausedRadio = !radioActive && isPausedRadio();
        if (radioActive || pausedRadio) {
            String radioLabel = radioNowPlayingDisplayLabel(nowPlaying, radioActive || pausedRadio);
            drawUiString(truncateUi(radioLabel, contentWidth), contentLeft, contentTop, 0xFFA8D6AB);
        }
        if (!shouldDrawPlaybackProgress(radioActive, pausedRadio)) {
            return;
        }
        if (!isRadioActive() && hasRadioStatus()) {
            drawUiString(
                truncateUiWithPrefix("Radio: ", radioStatus(), contentWidth),
                contentLeft,
                contentTop,
                0xFFFF7777);
            return;
        }
        if (nowPlaying == null) {
            drawUiString("Nothing playing", contentLeft, contentTop, 0xFF666666);
            return;
        }
        drawUiString(truncateUiWithPrefix("\u266A ", nowPlaying, contentWidth), contentLeft, contentTop, 0xFFFFFFFF);
        String artist = currentArtistLabel();
        if (artist.length() > 0) {
            drawUiString(truncateUi(artist, contentWidth), contentLeft, contentTop + 10, 0xFFA6A6A6);
        }
        int barLeft = timeBarLeft(left);
        int barWidth = timeBarWidth();
        int barTop = timeBarTopFromNowPlaying(y);
        float displayedProgress = seeking ? seekProgress : playbackProgress;
        long totalMillis = DurationParser.parseMillisStrict(currentDuration);
        long elapsedMillis = totalMillis < 0L ? 0L : Math.min(totalMillis, (long) (totalMillis * displayedProgress));
        int timeLabelTop = barTop + (TIME_BAR_HEIGHT - uiTextWidth(fontRendererObj.FONT_HEIGHT)) / 2;
        drawUiString(formatTime(elapsedMillis), contentLeft, timeLabelTop, 0xFFE0E0E0);
        String totalTime = totalMillis < 0L ? "--:--" : formatTime(totalMillis);
        drawUiString(
            totalTime,
            contentRight - uiTextWidth(fontRendererObj.getStringWidth(totalTime)),
            timeLabelTop,
            0xFFE0E0E0);
        drawRect(barLeft, barTop, barLeft + barWidth, barTop + TIME_BAR_HEIGHT, TIME_BAR_TRACK_COLOR);
        drawRect(
            barLeft,
            barTop,
            barLeft + timeBarFillWidth(barWidth, displayedProgress),
            barTop + TIME_BAR_HEIGHT,
            TIME_BAR_PROGRESS_COLOR);
    }

    private void addControlButtons(int panelLeft, int panelTop) {
        int controlLeft = panelLeft + (PANEL_WIDTH - controlGroupWidth() - 10 - CONTROL_BUTTON_WIDTH) / 2;
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
            ICON_FAVORITE);
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
        int controlLeft = left + (PANEL_WIDTH - groupWidth - 10 - CONTROL_BUTTON_WIDTH) / 2;
        int controlTop = controlTop(nowPlayingTop);
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
            updateChartControlVisibility();
        } else if (button.id == BUTTON_PLAYLIST_TAB) {
            currentTab = PLAYLIST_TAB;
            updateChartControlVisibility();
        } else if (button.id == BUTTON_PLAYLIST_DISCOVERY_TAB) {
            currentTab = PLAYLIST_DISCOVERY_TAB;
            updateChartControlVisibility();
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
    public void updateScreen() {
        super.updateScreen();
        updateAutoSearch(System.currentTimeMillis());
    }

    void updateAutoSearch(long now) {
        GuiTextField field = currentTab == PLAYLIST_DISCOVERY_TAB && playlistUrlField != null
            && playlistUrlField.isFocused()
            && !searchField.isFocused() ? playlistUrlField : searchField;
        if (field == null) {
            return;
        }
        String text = field.getText();
        if (!usesSharedSearchField() && currentTab != PLAYLIST_DISCOVERY_TAB) {
            observedSearchText = text;
            autoSearchAt = -1L;
            return;
        }
        if (!text.equals(observedSearchText)) {
            observedSearchText = text;
            if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                HorizonRadioClient.cancelPendingPlaylistDiscovery();
                playlistLoading = false;
                playlistResultsRevealPending = false;
            }
            if (currentTab == SEARCH_TAB) {
                HorizonRadioClient.cancelPendingSongSearch();
                searchResults.clear();
                searchLoading = false;
                searchResultsRevealPending = false;
                searchError = "";
                searchScrollOffset = 0;
            }
            if (text.trim()
                .isEmpty()) {
                autoSearchAt = -1L;
            } else {
                autoSearchAt = HorizonRadioClient.uiSettings().autoSearch
                    ? now + HorizonRadioClient.uiSettings().searchDelay
                    : -1L;
            }
        }
        if (autoSearchAt >= 0L && now >= autoSearchAt) {
            if (currentTab == PLAYLIST_DISCOVERY_TAB && playlistLoading) {
                return;
            }
            autoSearchAt = -1L;
            if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                if (field == playlistUrlField && looksLikePlaylistUrl(text.trim())) performPlaylistImport();
                else performSearch();
            } else {
                performSearch();
            }
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
                updateAutoSearch(System.currentTimeMillis());
                return;
            }
        }
        if (currentTab == PLAYLIST_DISCOVERY_TAB && playlistUrlField.textboxKeyTyped(typedChar, keyCode)) {
            updateAutoSearch(System.currentTimeMillis());
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
        if (button == 0 && !HorizonRadioClient.mediaStatusMessage()
            .isEmpty()
            && isMouseOver(
                nowPlayingContentRight(panelLeft()) - 12,
                nowPlayingTop(panelTop()) + 39,
                12,
                10,
                mouseX,
                mouseY)) {
            HorizonRadioClient.dismissMediaError();
            return;
        }
        if (button == 0 && handleQueueClick(mouseX, mouseY)) {
            return;
        }
        if (button == 0 && currentTab == RADIO_TAB) {
            List<RadioStationResult> results = displayedRadioResults();
            int listTop = panelTop() + radioListTopOffset(isRadioResultsLoading());
            if (isResultScrollbarAt(panelLeft(), listTop, results.size(), mouseX, mouseY)) {
                int trackHeight = RADIO_MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
                int thumbHeight = resultScrollbarThumbHeight(results.size(), trackHeight, RADIO_MAX_VISIBLE_ROWS);
                int thumbTop = resultScrollbarThumbTop(
                    results.size(),
                    radioScrollOffset,
                    listTop,
                    trackHeight,
                    thumbHeight,
                    RADIO_MAX_VISIBLE_ROWS);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, listTop);
            if (row >= 0 && row < results.size() - radioScrollOffset) {
                RadioStationResult station = results.get(radioScrollOffset + row);
                int glyphLeft = contentLeft(panelLeft()) + RESULT_GLYPH_LEFT_INSET;
                if (mouseX >= glyphLeft && mouseX < glyphLeft + RESULT_GLYPH_AREA_WIDTH) {
                    HorizonRadioClient.toggleRadioFavorite(station);
                    radioScrollOffset = Math
                        .min(radioScrollOffset, Math.max(0, displayedRadioResults().size() - RADIO_MAX_VISIBLE_ROWS));
                    updateFavoriteState();
                    return;
                }
                HorizonRadioClient.sendSelectRadio(station.stationUuid, station.name);
                return;
            }
        }
        if (button == 0 && isSongResultTab(currentTab)
            && !(currentTab == PLAYLIST_DISCOVERY_TAB && isPlaylistResultsLoading())) {
            boolean charts = currentTab == CHARTS_TAB;
            boolean playlistDiscovery = currentTab == PLAYLIST_DISCOVERY_TAB;
            if ((charts || (playlistDiscovery && !playlistSearchMode))
                && isChartsBulkButtonAt(panelLeft(), panelTop(), mouseX, mouseY)) {
                performBulkAdd();
                return;
            }
            List<SearchResult> results = charts ? chartResults
                : (playlistDiscovery ? displayedPlaylistResults() : displayedSearchResults());
            int scrollOffset = charts ? chartScrollOffset
                : (playlistDiscovery ? playlistScrollOffset : searchScrollOffset);
            int listTop = playlistDiscovery ? playlistDiscoveryListTop(panelTop()) : resultListTop(panelTop());
            if (isResultScrollbarAt(panelLeft(), listTop, results.size(), mouseX, mouseY)) {
                int visibleRows = resultVisibleRows();
                int trackHeight = visibleRows * ROW_HEIGHT - 2;
                int thumbHeight = resultScrollbarThumbHeight(results.size(), trackHeight, visibleRows);
                int thumbTop = resultScrollbarThumbTop(
                    results.size(),
                    scrollOffset,
                    listTop,
                    trackHeight,
                    thumbHeight,
                    visibleRows);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, listTop);
            if (row >= 0 && row < results.size() - scrollOffset) {
                SearchResult result = results.get(scrollOffset + row);
                if (playlistDiscovery && playlistSearchMode) {
                    String url = "https://www.youtube.com/playlist?list=" + result.videoId;
                    searchField.setText(url);
                    playlistUrlField.setText(url);
                    observedSearchText = url;
                    autoSearchAt = -1L;
                    HorizonRadioClient.sendPlaylistImport(url);
                    return;
                }
                int rowTop = listTop + row * ROW_HEIGHT;
                int glyphLeft = contentLeft(panelLeft()) + RESULT_GLYPH_LEFT_INSET;
                if (mouseX >= glyphLeft && mouseX < glyphLeft + RESULT_GLYPH_AREA_WIDTH) {
                    HorizonRadioClient.toggleSongFavorite(result);
                    searchScrollOffset = Math.min(
                        searchScrollOffset,
                        Math.max(0, displayedSearchResults().size() - SEARCH_MAX_VISIBLE_ROWS));
                    updateFavoriteState();
                    return;
                }
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
        GuiTextField field = usesSharedSearchField() ? searchField
            : currentTab == PLAYLIST_DISCOVERY_TAB ? playlistUrlField : null;
        if (field != null) {
            if (button == 0
                && isMouseOver(field.xPosition, field.yPosition, field.width, field.height, mouseX, mouseY)) {
                field.setText("");
                if (currentTab == PLAYLIST_DISCOVERY_TAB && playlistUrlField != null) playlistUrlField.setText("");
                field.setFocused(true);
                updateAutoSearch(System.currentTimeMillis());
                return;
            }
            field.mouseClicked(mouseX, mouseY, button);
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleQueueClick(int mouseX, int mouseY) {
        int left = queuePanelLeft(panelLeft());
        int listTop = panelTop() + QUEUE_LIST_TOP_OFFSET;
        int queueCount = playlist.size() + (hasStandaloneRadioRow() ? 1 : 0);
        if (queueCount > QUEUE_MAX_VISIBLE_ROWS && isQueueScrollbarAt(left, listTop, queueCount, mouseX, mouseY)) {
            int trackHeight = QUEUE_MAX_VISIBLE_ROWS * ROW_HEIGHT - 1;
            int thumbHeight = resultScrollbarThumbHeight(queueCount, trackHeight, QUEUE_MAX_VISIBLE_ROWS);
            int thumbTop = resultScrollbarThumbTop(
                queueCount,
                queueScrollOffset,
                listTop,
                trackHeight,
                thumbHeight,
                QUEUE_MAX_VISIBLE_ROWS);
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
            if (isActiveRadioQueueRow(row)) {
                HorizonRadioClient.sendRemove(radioState.getStationUuid());
            } else {
                int playlistIndex = queueIndexAtRow(row);
                if (playlistIndex >= 0 && playlistIndex < playlist.size()) {
                    HorizonRadioClient.sendRemove(playlist.get(playlistIndex).sourceId);
                }
            }
            return true;
        }
        if (isActiveRadioQueueRow(row)) {
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
                queueScrollOffset = scroll(
                    queueScrollOffset,
                    playlist.size() + (hasStandaloneRadioRow() ? 1 : 0),
                    wheel,
                    QUEUE_MAX_VISIBLE_ROWS);
            } else if (currentTab == CHARTS_TAB) {
                chartScrollOffset = scroll(chartScrollOffset, chartResults.size(), wheel);
            } else if (currentTab == SEARCH_TAB) {
                searchScrollOffset = scroll(
                    searchScrollOffset,
                    displayedSearchResults().size(),
                    wheel,
                    SEARCH_MAX_VISIBLE_ROWS);
            } else if (currentTab == PLAYLIST_DISCOVERY_TAB) {
                playlistScrollOffset = scroll(playlistScrollOffset, displayedPlaylistResults().size(), wheel);
            } else if (currentTab == RADIO_TAB) {
                radioScrollOffset = scroll(
                    radioScrollOffset,
                    displayedRadioResults().size(),
                    wheel,
                    RADIO_MAX_VISIBLE_ROWS);
            } else {
                queueScrollOffset = scroll(
                    queueScrollOffset,
                    playlist.size() + (hasStandaloneRadioRow() ? 1 : 0),
                    wheel,
                    QUEUE_MAX_VISIBLE_ROWS);
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
            if (Math.abs(mouseX - dragStartMouseX) >= 3 || Math.abs(mouseY - dragStartMouseY) >= 3) {
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
            int targetIndex = playlistDragMoved ? queueDropIndex(mouseX, mouseY) : playlistIndexAt(mouseX, mouseY);
            PlaylistEntry clickedEntry = draggedPlaylistEntry;
            boolean shouldPlay = !playlistDragMoved && targetIndex == fromIndex && clickedEntry != null;
            boolean shouldStartFromDrop = playlistDragMoved && isPlaylistIndexDraggable(fromIndex)
                && targetIndex == 0
                && clickedEntry != null
                && clickedEntry.isFinite()
                && (fromIndex != 0 || isRadioActive());
            boolean shouldSendReorder = playlistDragMoved && isPlaylistIndexDraggable(fromIndex)
                && targetIndex >= 0
                && targetIndex != fromIndex
                && isPlaylistDropAllowed(targetIndex);
            draggedPlaylistIndex = -1;
            draggedPlaylistEntry = null;
            playlistDragMoved = false;
            queueDragScrollAt = 0L;
            if (shouldStartFromDrop) {
                HorizonRadioClient.sendPlayNow(clickedEntry.sourceId, clickedEntry.durationMs);
            } else if (shouldSendReorder) {
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
        autoSearchAt = -1L;
        observedSearchText = searchField.getText();
        String query = searchField.getText()
            .trim();
        if (currentTab == PLAYLIST_DISCOVERY_TAB) {
            if (query.length() > 0 && looksLikePlaylistUrl(query)) {
                if (playlistUrlField != null) {
                    playlistUrlField.setText(query);
                    performPlaylistImport();
                }
            } else if (!query.isEmpty()) {
                HorizonRadioClient.sendPlaylistSearch(query);
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
        autoSearchAt = -1L;
        observedSearchText = playlistUrlField.getText();
        if (playlistLoading) {
            return;
        }
        HorizonRadioClient.sendPlaylistImport(playlistUrlField.getText());
    }

    private void openCharts() {
        currentTab = CHARTS_TAB;
        updateChartRefreshButtonState();
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
        chartSearchStarted = true;
        chartResults.clear();
        chartScrollOffset = 0;
        beginChartLoading();
        HorizonRadioClient.sendChartsRequest(chartRegionCode, false);
    }

    public void openRadio() {
        currentTab = RADIO_TAB;
        updateChartControlVisibility();
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
        chartSearchStarted = chartSearchStarted || !chartResults.isEmpty();
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
        updateQueueClearButtonVisibility();
    }

    public void beginChartLoading() {
        chartResultsRevealPending = false;
        chartLoading = true;
        chartError = "";
        chartProgress = 0.02f;
        chartStartedAt = System.currentTimeMillis();
        updateChartRefreshButtonState();
    }

    public void beginPlaylistSearch() {
        beginPlaylistLoading();
        playlistSearchMode = true;
    }

    public void updatePlaylistSearchResults(List<com.horizonradio.core.model.PlaylistSearchResult> results) {
        List<SearchResult> rows = new ArrayList<SearchResult>();
        if (results != null) for (com.horizonradio.core.model.PlaylistSearchResult result : results) {
            rows.add(new SearchResult(result.id, result.title, result.author, result.videoCount, ""));
        }
        updatePlaylistResults(rows, "Playlists");
        playlistSearchMode = true;
        updateChartControlVisibility();
    }

    public void beginPlaylistLoading() {
        playlistSearchMode = false;
        playlistSearchStarted = true;
        playlistResultsRevealPending = false;
        playlistResults.clear();
        playlistScrollOffset = 0;
        playlistLoading = true;
        playlistError = "";
        playlistProgress = 0.02f;
        playlistStartedAt = System.currentTimeMillis();
        updateChartControlVisibility();
    }

    public void updatePlaylistResults(List<SearchResult> results) {
        updatePlaylistResults(results, "");
    }

    public void updatePlaylistResults(List<SearchResult> results, String title) {
        playlistSearchMode = false;
        playlistTitle = title == null ? "" : title;
        boolean requestWasLoading = playlistLoading;
        playlistResults = results == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(results);
        playlistScrollOffset = 0;
        playlistLoading = false;
        playlistError = "";
        playlistProgress = 1.0f;
        schedulePlaylistResultsReveal(requestWasLoading);
        playlistSearchStarted = playlistSearchStarted || !playlistResults.isEmpty();
        updateChartControlVisibility();
    }

    public void showPlaylistError(String message) {
        playlistLoading = false;
        playlistResultsRevealPending = false;
        playlistResults.clear();
        playlistScrollOffset = 0;
        playlistError = message == null ? "" : message;
        playlistProgress = 1.0f;
        updateChartControlVisibility();
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
        return radioLoading ? RADIO_LIST_TOP_WITH_PROGRESS_OFFSET : RADIO_LIST_TOP_OFFSET;
    }

    static int radioSearchControlYOffset() {
        return RADIO_SEARCH_CONTROL_Y_OFFSET;
    }

    static boolean shouldShowSongModeButtons(int tab) {
        return tab != RADIO_TAB;
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
        updateChartControlVisibility();
    }

    private void updateChartControlVisibility() {
        if (observedSearchTab != currentTab) {
            if (searchField != null) {
                if (currentTab == RADIO_TAB) {
                    musicSearchText = searchField.getText();
                    searchField.setText(radioSearchText);
                    radioScrollOffset = 0;
                } else if (observedSearchTab == RADIO_TAB) {
                    radioSearchText = searchField.getText();
                    searchField.setText(musicSearchText);
                }
            }
            observedSearchTab = currentTab;
            GuiTextField field = currentTab == PLAYLIST_DISCOVERY_TAB && playlistUrlField != null
                && playlistUrlField.isFocused()
                && !searchField.isFocused() ? playlistUrlField : searchField;
            observedSearchText = field == null ? "" : field.getText();
            autoSearchAt = -1L;
        }
        boolean showChartControls = currentTab == CHARTS_TAB && hasVisibleChartResults();
        boolean showPlaylistControls = currentTab == PLAYLIST_DISCOVERY_TAB && !playlistSearchMode
            && hasVisiblePlaylistResults();
        setVisible(refreshChartsButton, showChartControls);
        setVisible(bulkAddButton, showChartControls || showPlaylistControls);
    }

    private boolean hasVisibleChartResults() {
        return !chartResults.isEmpty() && !isChartResultsLoading();
    }

    private boolean hasVisiblePlaylistResults() {
        return playlistSearchStarted && !playlistResults.isEmpty() && !isPlaylistResultsLoading();
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
        if (draggedPlaylistIndex >= playlist.size()
            || (draggedPlaylistIndex >= 0 && playlist.get(draggedPlaylistIndex) != draggedPlaylistEntry)) {
            draggedPlaylistIndex = -1;
            draggedPlaylistEntry = null;
            playlistDragMoved = false;
        }
        updateQueueClearButtonVisibility();
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
        return inQueue || pending ? "-" : "+";
    }

    static String searchResultGlyph(boolean favorite) {
        return favorite ? FAVORITE_GLYPH : "\u266B";
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

    String getPlaylistTitle() {
        return playlistTitle;
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
        return mouseY >= listTop && row >= 0 && row < resultVisibleRows() ? row : -1;
    }

    private int resultVisibleRows() {
        if (currentTab == RADIO_TAB) {
            return RADIO_MAX_VISIBLE_ROWS;
        }
        return currentTab == SEARCH_TAB ? SEARCH_MAX_VISIBLE_ROWS : MAX_VISIBLE_ROWS;
    }

    private int queueRowAt(int mouseX, int mouseY) {
        int left = queuePanelLeft(panelLeft());
        if (mouseX < left + 3 || mouseX > queuePanelRight(panelLeft()) - 3) {
            return -1;
        }
        int listTop = panelTop() + QUEUE_LIST_TOP_OFFSET;
        int row = (mouseY - listTop) / ROW_HEIGHT;
        return mouseY >= listTop && row >= 0 && row < QUEUE_MAX_VISIBLE_ROWS ? row : -1;
    }

    private int queueIndexAtRow(int row) {
        return queueScrollOffset + row - (hasStandaloneRadioRow() ? 1 : 0);
    }

    boolean hasStandaloneRadioRow() {
        if (!isRadioActive()) return false;
        for (PlaylistEntry entry : playlist) {
            if (entry != null && entry.sourceType == MediaSourceType.RADIO
                && radioState.getStationUuid()
                    .equals(entry.sourceId)) {
                return false;
            }
        }
        return true;
    }

    private boolean isActiveRadioQueueRow(int row) {
        if (hasStandaloneRadioRow()) return row == 0;
        int index = queueIndexAtRow(row);
        return isRadioActive() && index >= 0
            && index < playlist.size()
            && playlist.get(index).sourceType == MediaSourceType.RADIO
            && radioState.getStationUuid()
                .equals(playlist.get(index).sourceId);
    }

    private int queueMaxScrollOffset() {
        int visiblePlaylistRows = QUEUE_MAX_VISIBLE_ROWS - (hasStandaloneRadioRow() ? 1 : 0);
        return Math.max(0, playlist.size() - Math.max(1, visiblePlaylistRows));
    }

    private boolean isTimeBarAt(int mouseX, int mouseY) {
        if (isRadioActive()) {
            return false;
        }
        int left = timeBarLeft(panelLeft());
        int right = left + timeBarWidth();
        int top = timeBarTop(panelTop()) - 2;
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= top + TIME_BAR_HEIGHT + 4;
    }

    private float seekProgressAt(int mouseX) {
        int left = timeBarLeft(panelLeft());
        int width = timeBarWidth();
        return Math.max(0.0f, Math.min(1.0f, (float) (mouseX - left) / (float) Math.max(1, width - 1)));
    }

    private int timeBarLeft(int left) {
        return left + TIME_BAR_SIDE_SPACE;
    }

    private int timeBarWidth() {
        return PANEL_WIDTH - 2 * TIME_BAR_SIDE_SPACE;
    }

    static int timeBarFillWidth(int barWidth, float progress) {
        float clampedProgress = Math.max(0.0f, Math.min(1.0f, progress));
        return (int) (Math.max(0, barWidth) * clampedProgress);
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
        String currentArtist = HorizonRadioClient.getCurrentSongArtist();
        if (currentArtist.length() > 0) {
            return currentArtist;
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

    private int nowPlayingContentLeft(int panelLeft) {
        return panelLeft + NOW_PLAYING_PANEL_INSET + NOW_PLAYING_CONTENT_MARGIN;
    }

    private int nowPlayingContentRight(int panelLeft) {
        return panelLeft + PANEL_WIDTH - NOW_PLAYING_PANEL_INSET - NOW_PLAYING_CONTENT_MARGIN;
    }

    private int nowPlayingContentTop(int nowPlayingTop) {
        return nowPlayingTop + NOW_PLAYING_CONTENT_MARGIN + 3;
    }

    private int nowPlayingContentBottom(int nowPlayingTop) {
        return nowPlayingTop + NOW_PLAYING_PANEL_HEIGHT - NOW_PLAYING_CONTENT_MARGIN;
    }

    private int timeBarTop(int top) {
        return timeBarTopFromNowPlaying(nowPlayingTop(top));
    }

    private int timeBarTopFromNowPlaying(int nowPlayingTop) {
        return nowPlayingTop + 33;
    }

    private int controlTop(int nowPlayingTop) {
        return nowPlayingContentBottom(nowPlayingTop) - CONTROL_BUTTON_HEIGHT;
    }

    private int controlGroupWidth() {
        return CONTROL_BUTTON_COUNT * CONTROL_BUTTON_WIDTH + (CONTROL_BUTTON_COUNT - 1) * CONTROL_BUTTON_GAP;
    }

    static boolean shouldShowQueueClearButton(int playlistSize, boolean radioActive) {
        return playlistSize > 0 || radioActive;
    }

    private void updateQueueClearButtonVisibility() {
        if (queueClearButton == null) {
            return;
        }
        boolean visible = shouldShowQueueClearButton(playlist.size(), isRadioActive());
        queueClearButton.visible = visible;
        queueClearButton.enabled = visible;
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
        int visibleRows = resultVisibleRows();
        if (resultCount <= visibleRows) {
            return;
        }
        int trackLeft = contentRight(panelLeft) - RESULT_SCROLLBAR_LEFT_OFFSET;
        int trackHeight = visibleRows * ROW_HEIGHT - 2;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight, visibleRows);
        int thumbTop = resultScrollbarThumbTop(
            resultCount,
            scrollOffset,
            listTop,
            trackHeight,
            thumbHeight,
            visibleRows);
        drawRect(trackLeft, listTop, trackLeft + RESULT_SCROLLBAR_WIDTH, listTop + trackHeight, 0x66555555);
        drawRect(trackLeft, thumbTop, trackLeft + RESULT_SCROLLBAR_WIDTH, thumbTop + thumbHeight, 0xFFDDDDDD);
    }

    private boolean isResultScrollbarAt(int panelLeft, int listTop, int resultCount, int mouseX, int mouseY) {
        int visibleRows = resultVisibleRows();
        if (resultCount <= visibleRows) {
            return false;
        }
        int trackLeft = contentRight(panelLeft) - RESULT_SCROLLBAR_LEFT_OFFSET;
        int trackHeight = visibleRows * ROW_HEIGHT - 2;
        return isMouseOver(trackLeft, listTop, RESULT_SCROLLBAR_WIDTH, trackHeight, mouseX, mouseY);
    }

    private int resultScrollbarThumbHeight(int resultCount, int trackHeight) {
        return resultScrollbarThumbHeight(resultCount, trackHeight, MAX_VISIBLE_ROWS);
    }

    private int resultScrollbarThumbHeight(int resultCount, int trackHeight, int visibleRows) {
        return Math.max(RESULT_SCROLLBAR_MIN_THUMB_HEIGHT, trackHeight * visibleRows / resultCount);
    }

    private int resultScrollbarThumbTop(int resultCount, int scrollOffset, int listTop, int trackHeight,
        int thumbHeight) {
        return resultScrollbarThumbTop(resultCount, scrollOffset, listTop, trackHeight, thumbHeight, MAX_VISIBLE_ROWS);
    }

    private int resultScrollbarThumbTop(int resultCount, int scrollOffset, int listTop, int trackHeight,
        int thumbHeight, int visibleRows) {
        int maxOffset = Math.max(1, resultCount - visibleRows);
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
        int visibleRows = resultVisibleRows();
        int trackHeight = visibleRows * ROW_HEIGHT - 2;
        int thumbHeight = resultScrollbarThumbHeight(resultCount, trackHeight, visibleRows);
        int maxOffset = Math.max(0, resultCount - visibleRows);
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
        drawTextButtonAbsolute(queueButtonLeft(panelLeft), top, label, hovered);
    }

    private void drawTextButtonAbsolute(int left, int top, String label, boolean hovered) {
        boolean queued = "-".equals(label);
        drawRect(left, top, left + QUEUE_BUTTON_WIDTH, top + QUEUE_BUTTON_HEIGHT, queued ? 0xFFA8D7AB : 0xFF858585);
        drawRect(
            left + 1,
            top + 1,
            left + QUEUE_BUTTON_WIDTH - 1,
            top + QUEUE_BUTTON_HEIGHT - 1,
            queued ? 0xFF365C3C : (hovered ? 0xFF555555 : 0xFF454545));
        drawUiCenteredString(label, left + QUEUE_BUTTON_WIDTH / 2, queueButtonTextTop(top), 0xFFFFFFFF);
    }

    private static final class ControlButton extends GuiButton {

        private ResourceLocation iconTexture;
        private boolean active;
        private boolean greenActive;
        private final int borderColor;
        private String label;
        private float labelScale = UI_TEXT_SCALE;

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
            int outer = !enabled ? 0xFF555555 : (active && greenActive ? 0xFFA8D7AB : 0xFF858585);
            int inner = !enabled ? 0xFF383838
                : (active && greenActive ? 0xFF365C3C : (hovered ? 0xFF505050 : 0xFF3A3A3A));
            drawRect(
                xPosition,
                yPosition,
                xPosition + width,
                yPosition + height,
                borderColor == SEARCH_BUTTON_BORDER_COLOR ? borderColor : outer);
            drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, inner);
            if (enabled) {
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                GL11.glColor4f(0.5F, 0.5F, 0.5F, 1.0F);
            }
            int labelColor = !enabled ? 0xFF777777 : (active && greenActive ? 0xFFDCF6DE : 0xFFFFFFFF);
            if (iconTexture == null) {
                if (labelScale == 1.0F) {
                    drawCenteredString(
                        minecraft.fontRenderer,
                        label,
                        xPosition + width / 2,
                        yPosition + (height - 8) / 2 + 1,
                        labelColor);
                } else {
                    GL11.glPushMatrix();
                    GL11.glTranslatef(xPosition + width / 2.0F, yPosition + height / 2.0F, 0.0F);
                    GL11.glScalef(labelScale, labelScale, 1.0F);
                    drawCenteredString(minecraft.fontRenderer, label, 0, -4, labelColor);
                    GL11.glPopMatrix();
                }
                return;
            }
            minecraft.getTextureManager()
                .bindTexture(iconTexture);
            if (id == BUTTON_SETTINGS) {
                // Use the supplied icon's alpha mask with the current white/disabled tint.
                GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
                try {
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_REPLACE);
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_PRIMARY_COLOR);
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
                    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
                    // Odd-sized icon gives equal pixel margins inside the 17px button.
                    Gui.func_152125_a(
                        xPosition + (width - 13) / 2,
                        yPosition + (height - 13) / 2,
                        0,
                        0,
                        CONTROL_ICON_TEXTURE_SIZE,
                        CONTROL_ICON_TEXTURE_SIZE,
                        13,
                        13,
                        CONTROL_ICON_TEXTURE_SIZE,
                        CONTROL_ICON_TEXTURE_SIZE);
                } finally {
                    GL11.glPopAttrib();
                }
                return;
            }
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

    static int searchProgressTopOffset(boolean radio) {
        return SEARCH_PROGRESS_Y_OFFSET + (radio ? RADIO_SEARCH_CONTROL_Y_OFFSET - SEARCH_CONTROL_Y_OFFSET : 0);
    }

    private void drawProgressBar(int left, int top, float progress) {
        int barLeft = left + SEARCH_FIELD_X_OFFSET - 1;
        int barRight = left + SEARCH_FIELD_X_OFFSET + SEARCH_FIELD_WIDTH + SEARCH_CONTROL_GAP + SEARCH_BUTTON_WIDTH;
        int barTop = top + searchProgressTopOffset(currentTab == RADIO_TAB);
        drawRect(barLeft, barTop, barRight, barTop + SEARCH_PROGRESS_HEIGHT, 0xFF454545);
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

    private void updateModeVisibility() {
        boolean showSongModes = shouldShowSongModeButtons(currentTab);
        setVisible(chartsTabButton, showSongModes);
        setVisible(searchTabButton, showSongModes);
        setVisible(playlistsTabButton, showSongModes);
        updateChartControlVisibility();

        int searchOffset = currentTab == RADIO_TAB ? RADIO_SEARCH_CONTROL_Y_OFFSET : SEARCH_CONTROL_Y_OFFSET;
        int top = panelTop();
        if (searchField != null) {
            searchField.yPosition = top + searchOffset;
        }
        if (playlistUrlField != null) {
            playlistUrlField.yPosition = top + searchOffset;
        }
        if (searchButton != null) {
            searchButton.yPosition = top + searchOffset - 1;
        }
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
        return index >= 0 && index < playlist.size() && !(index == 0 && nowPlaying != null && !hasStandaloneRadioRow());
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
        return top + CHART_LIST_TOP_OFFSET;
    }

    private int resultListTop(int top) {
        return currentTab == SEARCH_TAB ? top + searchListTopOffset(isSearchListLoading())
            : top + (currentTab == CHARTS_TAB ? CHART_LIST_TOP_OFFSET : CONTENT_LIST_TOP_OFFSET);
    }

    private boolean isPlaylistDropAllowed(int index) {
        return index >= 0 && index < playlist.size();
    }

    private static int scroll(int offset, int size, int wheel) {
        return scroll(offset, size, wheel, MAX_VISIBLE_ROWS);
    }

    private static int scroll(int offset, int size, int wheel, int visibleRows) {
        int direction = wheel > 0 ? -1 : 1;
        return Math.max(0, Math.min(offset + direction, Math.max(0, size - visibleRows)));
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

    private String truncateUi(String text, int maxWidth) {
        return truncate(text, (int) Math.ceil(maxWidth / UI_TEXT_SCALE));
    }

    private String truncateUiWithPrefix(String prefix, String text, int maxWidth) {
        int prefixWidth = uiTextWidth(fontRendererObj.getStringWidth(prefix));
        return prefix + truncateUi(text, Math.max(0, maxWidth - prefixWidth));
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
