package com.horizonradio.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
    private static final int CONTROL_BUTTON_COUNT = 5;
    private static final int CONTROL_ICON_SIZE = 16;
    private static final int CONTROL_ICON_TEXTURE_SIZE = 128;
    private static final int TAB_BUTTON_WIDTH = 60;
    private static final int TAB_BUTTON_HEIGHT = 20;
    private static final int TAB_BUTTON_Y = 5;
    private static final int CHARTS_TAB = 0;
    private static final int SEARCH_TAB = 1;
    private static final int PLAYLIST_TAB = 2;
    private static final int CHARTS_TAB_X = 10;
    private static final int SEARCH_TAB_X = 75;
    private static final int PLAYLIST_TAB_X = 140;
    private static final int BUTTON_SEARCH = 0;
    private static final int BUTTON_CHARTS_TAB = 1;
    private static final int BUTTON_PLAYLIST_TAB = 2;
    private static final int BUTTON_SEARCH_TAB = 9;
    private static final int BUTTON_REFRESH_CHARTS = 10;
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
    private static final int SEARCH_PROGRESS_Y_OFFSET = 55;
    private static final int SEARCH_PROGRESS_HEIGHT = 6;
    private static final int SEARCH_LIST_TOP_OFFSET = 68;
    private static final int CONTENT_HEADER_Y_OFFSET = 35;
    private static final int CONTENT_LIST_TOP_OFFSET = 55;
    private static final int RESULT_DURATION_COLUMN_WIDTH = 40;
    private static final int RESULT_SCROLLBAR_WIDTH = 3;
    private static final int RESULT_SCROLLBAR_LEFT_OFFSET = 6;
    private static final int RESULT_SCROLLBAR_MIN_THUMB_HEIGHT = 10;
    private static final int QUEUE_DISPLAY_LIMIT = 50;
    private static final long SEARCH_PROGRESS_ESTIMATE_MILLIS = 1000L;
    private static final int TIME_BAR_SIDE_SPACE = 38;

    private GuiTextField searchField;
    private GuiButton searchButton;
    private ControlButton refreshChartsButton;
    private HorizonRadioVolumeSlider volumeSlider;
    private List<SearchResult> chartResults = new ArrayList<SearchResult>();
    private List<SearchResult> searchResults = new ArrayList<SearchResult>();
    private List<PlaylistEntry> playlist = new ArrayList<PlaylistEntry>();
    private int currentTab;
    private int chartScrollOffset;
    private int searchScrollOffset;
    private int playlistScrollOffset;
    private String nowPlaying;
    private String currentDuration;
    private float playbackProgress;
    private float searchProgress;
    private boolean searchLoading;
    private long searchStartedAt;
    private float chartProgress;
    private boolean chartLoading;
    private long chartStartedAt;
    private boolean seeking;
    private float seekProgress;
    private ControlButton playbackButton;
    private ControlButton loopButton;
    private ControlButton shuffleButton;
    private int draggedPlaylistIndex = -1;
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
        playlist = HorizonRadioClient.getCachedPlaylist();
        nowPlaying = HorizonRadioClient.getCachedNowPlaying();
        playbackProgress = HorizonRadioClient.getCachedProgress();
        refreshCurrentDuration();

        searchField = new GuiTextField(fontRendererObj, panelLeft + 10, panelTop + 30, PANEL_WIDTH - 80, 20);
        searchField.setMaxStringLength(100);
        searchField.setFocused(false);

        buttonList.clear();
        searchButton = new GuiButton(BUTTON_SEARCH, panelLeft + PANEL_WIDTH - 60, panelTop + 30, 50, 20, "Search");
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
                "Playlist"));
        refreshChartsButton = new ControlButton(
            BUTTON_REFRESH_CHARTS,
            queueButtonLeft(panelLeft) - 25,
            panelTop + CONTENT_HEADER_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            QUEUE_BUTTON_HEIGHT,
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
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int left = panelLeft();
        int top = panelTop();
        drawRect(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101010);
        drawPanelBorder(left, top);
        drawCenteredString(fontRendererObj, "HorizonRadio - Music Player", width / 2, top - 15, 0xFFFFFFFF);

        if (currentTab == CHARTS_TAB) {
            updateChartProgress();
            drawChartsTab(left, top, mouseX, mouseY);
        } else if (currentTab == SEARCH_TAB) {
            updateSearchProgress();
            drawSearchTab(left, top, mouseX, mouseY);
        } else {
            drawPlaylistTab(left, top, mouseX, mouseY);
        }
        int nowPlayingTop = nowPlayingTop(top);
        drawNowPlaying(left, nowPlayingTop);
        drawControlCenter(left, nowPlayingTop);

        searchButton.visible = currentTab == SEARCH_TAB;
        refreshChartsButton.visible = currentTab == CHARTS_TAB;
        if (currentTab == SEARCH_TAB) {
            searchField.drawTextBox();
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
        drawString(
            fontRendererObj,
            "Top 50 Charts Germany (Weekly)",
            left + 10,
            top + CONTENT_HEADER_Y_OFFSET + 4,
            0xFFE0E0E0);
        drawResultList(
            chartResults,
            chartScrollOffset,
            left,
            top + CONTENT_LIST_TOP_OFFSET,
            mouseX,
            mouseY,
            chartLoading ? "Loading charts..." : "No charts available");
        if (!chartResults.isEmpty()) {
            drawQueueButtonAt(
                left,
                top + CONTENT_HEADER_Y_OFFSET,
                areAllChartsInQueue(),
                isChartsBulkButtonAt(left, top, mouseX, mouseY));
        }
    }

    private void drawSearchTab(int left, int top, int mouseX, int mouseY) {
        drawProgressBar(left, top, searchProgress);
        drawResultList(
            searchResults,
            searchScrollOffset,
            left,
            top + SEARCH_LIST_TOP_OFFSET,
            mouseX,
            mouseY,
            searchLoading ? "Searching..." : "Search for songs above");
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
            drawQueueButton(left, y, isInQueue(result.videoId), isQueueButtonAt(left, y, mouseX, mouseY));
        }
        drawResultScrollbar(results.size(), scrollOffset, left, listTop);
    }

    private void drawPlaylistTab(int left, int top, int mouseX, int mouseY) {
        int listTop = playlistListTop(top);
        drawString(
            fontRendererObj,
            "Queue (" + playlist.size() + " von " + QUEUE_DISPLAY_LIMIT + ")",
            left + 10,
            top + CONTENT_HEADER_Y_OFFSET + 4,
            0xFFE0E0E0);
        if (!playlist.isEmpty()) {
            drawTextButtonAt(
                left,
                top + CONTENT_HEADER_Y_OFFSET,
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
        for (int row = 0; row < MAX_VISIBLE_ROWS && playlistScrollOffset + row < playlist.size(); row++) {
            int index = playlistScrollOffset + row;
            int y = listTop + row * ROW_HEIGHT;
            PlaylistEntry entry = playlist.get(index);
            boolean hovered = mouseX >= left + 10 && mouseX <= left + PANEL_WIDTH - 10
                && mouseY >= y
                && mouseY < y + ROW_HEIGHT;
            boolean isPlaying = index == 0 && nowPlaying != null;
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
            drawString(fontRendererObj, truncate(entry.title, titleWidth), left + 35, y + 4, 0xFFFFFFFF);
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
                int dropRow = dropIndex - playlistScrollOffset;
                if (dropRow >= 0 && dropRow < MAX_VISIBLE_ROWS) {
                    int dropY = listTop + dropRow * ROW_HEIGHT;
                    drawRect(left + 8, dropY, left + PANEL_WIDTH - 8, dropY + 2, 0xFF55AAFF);
                }
            }
        }
        drawResultScrollbar(playlist.size(), playlistScrollOffset, left, listTop);
    }

    private void drawNowPlaying(int left, int y) {
        drawRect(left, y, left + PANEL_WIDTH, y + 25, 0x60000000);
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
        addButton(
            new ControlButton(
                5,
                controlLeft + CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP,
                controlTop,
                CONTROL_BUTTON_WIDTH,
                CONTROL_BUTTON_HEIGHT,
                ICON_PREVIOUS));
        playbackButton = new ControlButton(
            6,
            controlLeft + 2 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            HorizonRadioClient.isPaused() ? ICON_PLAY : ICON_PAUSE);
        addButton(playbackButton);
        addButton(
            new ControlButton(
                7,
                controlLeft + 3 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
                controlTop,
                CONTROL_BUTTON_WIDTH,
                CONTROL_BUTTON_HEIGHT,
                ICON_NEXT));
        loopButton = new ControlButton(
            8,
            controlLeft + 4 * (CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_GAP),
            controlTop,
            CONTROL_BUTTON_WIDTH,
            CONTROL_BUTTON_HEIGHT,
            ICON_LOOP);
        loopButton.setActive(HorizonRadioClient.isLooping());
        addButton(loopButton);
    }

    @SuppressWarnings("unchecked")
    private void addButton(GuiButton button) {
        buttonList.add(button);
    }

    private void drawControlCenter(int left, int nowPlayingTop) {
        int controlLeft = left + (PANEL_WIDTH - controlGroupWidth()) / 2;
        int controlTop = controlTop(nowPlayingTop);
        drawRect(
            controlLeft - 3,
            controlTop - 2,
            controlLeft + controlGroupWidth() + 3,
            controlTop + CONTROL_BUTTON_HEIGHT + 2,
            0xCC000000);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BUTTON_SEARCH) {
            performSearch();
        } else if (button.id == BUTTON_CHARTS_TAB) {
            openCharts();
        } else if (button.id == BUTTON_SEARCH_TAB) {
            currentTab = SEARCH_TAB;
        } else if (button.id == BUTTON_PLAYLIST_TAB) {
            currentTab = PLAYLIST_TAB;
        } else if (button.id == BUTTON_REFRESH_CHARTS) {
            beginChartLoading();
            HorizonRadioClient.sendChartsRequest(true);
        } else if (button.id == 6) {
            HorizonRadioClient.sendTogglePlayback();
        } else if (button.id == 7) {
            HorizonRadioClient.sendSkipTrack();
        } else if (button.id == 5) {
            HorizonRadioClient.sendPreviousTrack();
        } else if (button.id == 8) {
            HorizonRadioClient.sendToggleLoop();
        } else if (button.id == 4) {
            HorizonRadioClient.sendToggleShuffle();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (currentTab == SEARCH_TAB && searchField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (currentTab == SEARCH_TAB && keyCode == Keyboard.KEY_RETURN && searchField.isFocused()) {
            performSearch();
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
        if (button == 0 && isResultTab(currentTab)) {
            boolean charts = currentTab == CHARTS_TAB;
            if (charts && isChartsBulkButtonAt(panelLeft(), panelTop(), mouseX, mouseY)) {
                HorizonRadioClient.sendAddChartsToPlaylist(chartResults, areAllChartsInQueue());
                return;
            }
            List<SearchResult> results = charts ? chartResults : searchResults;
            int scrollOffset = charts ? chartScrollOffset : searchScrollOffset;
            if (isResultScrollbarAt(panelLeft(), resultListTop(panelTop()), results.size(), mouseX, mouseY)) {
                int trackHeight = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
                int thumbHeight = resultScrollbarThumbHeight(results.size(), trackHeight);
                int thumbTop = resultScrollbarThumbTop(
                    results.size(),
                    scrollOffset,
                    resultListTop(panelTop()),
                    trackHeight,
                    thumbHeight);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, resultListTop(panelTop()));
            if (row >= 0 && row < results.size() - scrollOffset) {
                SearchResult result = results.get(scrollOffset + row);
                int rowTop = resultListTop(panelTop()) + row * ROW_HEIGHT;
                if (isQueueButtonAt(panelLeft(), rowTop, mouseX, mouseY)) {
                    if (isInQueue(result.videoId)) {
                        HorizonRadioClient.sendRemove(result.videoId);
                    } else {
                        sendResultToQueue(result, charts);
                    }
                    return;
                }
                HorizonRadioClient.sendPlayNow(result.videoId, result.title, result.duration);
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
                    playlistScrollOffset,
                    playlistListTop(panelTop()),
                    trackHeight,
                    thumbHeight);
                resultScrollbarDragOffset = mouseY - thumbTop;
                draggingResultScrollbar = true;
                updateResultScrollbarScroll(mouseY);
                return;
            }
            int row = rowAt(mouseX, mouseY, playlistListTop(panelTop()));
            if (row >= 0 && row < playlist.size() - playlistScrollOffset) {
                PlaylistEntry entry = playlist.get(playlistScrollOffset + row);
                if (isMouseOver(
                    queueButtonLeft(panelLeft()),
                    queueButtonTop(playlistListTop(panelTop()) + row * ROW_HEIGHT),
                    QUEUE_BUTTON_WIDTH,
                    QUEUE_BUTTON_HEIGHT,
                    mouseX,
                    mouseY)) {
                    HorizonRadioClient.sendRemove(entry.videoId);
                    return;
                }
                int playlistIndex = playlistScrollOffset + row;
                if (isPlaylistIndexDraggable(playlistIndex)) {
                    draggedPlaylistIndex = playlistIndex;
                    playlistDragMoved = false;
                    dragStartMouseX = mouseX;
                    dragStartMouseY = mouseY;
                    return;
                }
            }
        }
        if (button == 0 && nowPlaying != null && isTimeBarAt(mouseX, mouseY)) {
            seeking = true;
            seekProgress = seekProgressAt(mouseX);
            return;
        }
        if (currentTab == SEARCH_TAB) {
            searchField.mouseClicked(mouseX, mouseY, button);
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
                searchScrollOffset = scroll(searchScrollOffset, searchResults.size(), wheel);
            } else {
                playlistScrollOffset = scroll(playlistScrollOffset, playlist.size(), wheel);
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
            boolean shouldSend = playlistDragMoved && targetIndex >= 0
                && targetIndex != fromIndex
                && isPlaylistDropAllowed(targetIndex);
            draggedPlaylistIndex = -1;
            playlistDragMoved = false;
            if (shouldSend) {
                HorizonRadioClient.sendReorder(fromIndex, targetIndex);
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
        if (query.length() == 0) {
            openCharts();
            return;
        }
        requestSearch(query);
    }

    private void openCharts() {
        currentTab = CHARTS_TAB;
        if (chartResults.isEmpty() && !HorizonRadioClient.isChartRequestPending()) {
            beginChartLoading();
            HorizonRadioClient.sendChartsRequest(false);
        }
    }

    private void requestSearch(String query) {
        searchResults.clear();
        searchScrollOffset = 0;
        currentTab = SEARCH_TAB;
        searchProgress = 0.02f;
        searchLoading = true;
        searchStartedAt = System.currentTimeMillis();
        if (looksLikePlaylistUrl(query)) {
            currentTab = PLAYLIST_TAB;
            HorizonRadioClient.sendImportPlaylist(query);
        } else if (looksLikeVideoUrl(query)) {
            currentTab = PLAYLIST_TAB;
            HorizonRadioClient.sendImportVideo(query);
        } else {
            HorizonRadioClient.sendSearch(query);
        }
    }

    public void updateSearchResults(List<SearchResult> results) {
        searchResults = results == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(results);
        searchScrollOffset = 0;
        searchLoading = false;
        searchProgress = 1.0f;
    }

    public void updateChartResults(List<SearchResult> results) {
        chartResults = results == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(results);
        chartScrollOffset = 0;
        chartLoading = false;
        chartProgress = 1.0f;
    }

    public void beginChartLoading() {
        chartLoading = true;
        chartProgress = 0.02f;
        chartStartedAt = System.currentTimeMillis();
    }

    public void updatePlaylist(List<PlaylistEntry> entries) {
        playlist = entries == null ? new ArrayList<PlaylistEntry>() : new ArrayList<PlaylistEntry>(entries);
        refreshCurrentDuration();
        playlistScrollOffset = Math.min(playlistScrollOffset, Math.max(0, playlist.size() - MAX_VISIBLE_ROWS));
        if (draggedPlaylistIndex >= playlist.size()) {
            draggedPlaylistIndex = -1;
            playlistDragMoved = false;
        }
    }

    boolean isInQueue(String videoId) {
        if (videoId == null) {
            return false;
        }
        for (PlaylistEntry entry : playlist) {
            if (videoId.equals(entry.videoId)) {
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
    }

    public void updatePlaybackPaused(boolean paused) {
        if (playbackButton != null) {
            playbackButton.setIcon(paused ? ICON_PLAY : ICON_PAUSE);
        }
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

    @Override
    public void onGuiClosed() {
        seeking = false;
        draggingResultScrollbar = false;
        draggedPlaylistIndex = -1;
        playlistDragMoved = false;
        clearActiveScreen(this);
    }

    static synchronized void setActiveScreen(HorizonRadioScreen screen) {
        activeScreen = screen;
    }

    static synchronized void clearActiveScreen(HorizonRadioScreen screen) {
        if (activeScreen == screen) {
            activeScreen = null;
        }
    }

    static synchronized HorizonRadioScreen getActiveScreen() {
        return activeScreen;
    }

    List<PlaylistEntry> getPlaylistSnapshot() {
        return new ArrayList<PlaylistEntry>(playlist);
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
            if (nowPlaying.equals(entry.title)) {
                currentDuration = entry.duration;
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
            panelTop + CONTENT_HEADER_Y_OFFSET,
            QUEUE_BUTTON_WIDTH,
            QUEUE_BUTTON_HEIGHT,
            mouseX,
            mouseY);
    }

    private boolean isPlaylistClearButtonAt(int panelLeft, int panelTop, int mouseX, int mouseY) {
        return !playlist.isEmpty() && isMouseOver(
            queueButtonLeft(panelLeft),
            panelTop + CONTENT_HEADER_Y_OFFSET,
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
            resultCount = searchResults.size();
            listTop = panelTop() + SEARCH_LIST_TOP_OFFSET;
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
        } else {
            playlistScrollOffset = offset;
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

        private ControlButton(int id, int x, int y, int width, int height, ResourceLocation iconTexture) {
            super(id, x, y, width, height, "");
            this.iconTexture = iconTexture;
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
            boolean hovered = mouseX >= xPosition && mouseX < xPosition + width
                && mouseY >= yPosition
                && mouseY < yPosition + height;
            int outer = active ? 0xFF6EAA6E : (hovered ? 0xFF777777 : 0xFF5F5F5F);
            int inner = active ? 0xFF456B45 : (hovered ? 0xFF666666 : 0xFF4A4A4A);
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0xFF111111);
            drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, outer);
            drawRect(xPosition + 3, yPosition + 3, xPosition + width - 3, yPosition + height - 4, inner);
            drawRect(xPosition + 2, yPosition + 2, xPosition + width - 2, yPosition + 3, 0xFF9A9A9A);
            minecraft.getTextureManager()
                .bindTexture(iconTexture);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
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
        float estimatedProgress = (float) elapsed / (float) SEARCH_PROGRESS_ESTIMATE_MILLIS;
        searchProgress = Math.min(0.9f, Math.max(searchProgress, estimatedProgress * 0.9f));
    }

    private void updateChartProgress() {
        if (!chartLoading) {
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - chartStartedAt);
        float estimatedProgress = (float) elapsed / (float) SEARCH_PROGRESS_ESTIMATE_MILLIS;
        chartProgress = Math.min(0.9f, Math.max(chartProgress, estimatedProgress * 0.9f));
    }

    private boolean isResultTab(int tab) {
        return tab == CHARTS_TAB || tab == SEARCH_TAB;
    }

    boolean isPlaylistTab() {
        return currentTab == PLAYLIST_TAB;
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

    private void sendResultToQueue(SearchResult result, boolean charts) {
        if (charts) {
            HorizonRadioClient.sendAddChartsToPlaylist(Collections.singletonList(result));
        } else {
            HorizonRadioClient.sendAdd(result.videoId, result.title, result.duration);
        }
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
        int index = row < 0 ? -1 : playlistScrollOffset + row;
        return index >= 0 && index < playlist.size() ? index : -1;
    }

    private boolean isPlaylistIndexDraggable(int index) {
        return index >= 0 && index < playlist.size() && !(index == 0 && nowPlaying != null);
    }

    private int playlistListTop(int top) {
        return top + CONTENT_LIST_TOP_OFFSET;
    }

    private int resultListTop(int top) {
        return currentTab == SEARCH_TAB ? top + SEARCH_LIST_TOP_OFFSET : top + CONTENT_LIST_TOP_OFFSET;
    }

    private boolean isPlaylistDropAllowed(int index) {
        return isPlaylistIndexDraggable(index);
    }

    private static int scroll(int offset, int size, int wheel) {
        int direction = wheel > 0 ? -1 : 1;
        return Math.max(0, Math.min(offset + direction, Math.max(0, size - MAX_VISIBLE_ROWS)));
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

    public static final class PlaylistEntry {

        public final String videoId;
        public final String title;
        public final String duration;
        public final String addedBy;

        public PlaylistEntry(String videoId, String title, String duration, String addedBy) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.addedBy = addedBy;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PlaylistEntry)) {
                return false;
            }
            PlaylistEntry that = (PlaylistEntry) other;
            return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
                && Objects.equals(duration, that.duration)
                && Objects.equals(addedBy, that.addedBy);
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoId, title, duration, addedBy);
        }
    }
}
