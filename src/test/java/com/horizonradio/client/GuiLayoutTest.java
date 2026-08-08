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
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.gui.GuiButton;

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
    public void refreshButtonSendsOneForceRequestWhilePendingAndReenablesOnResults() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        try {
            screen.initialize();

            assertEquals(1, transport.chartRequestCount);
            assertEquals(0, transport.forceChartsRequestCount);
            HorizonRadioClient.updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>());
            assertFalse(HorizonRadioClient.isChartRequestPending());
            assertTrue(screen.refreshButton().enabled);

            screen.invokeRefreshAction();
            screen.invokeRefreshAction();

            assertEquals(2, transport.chartRequestCount);
            assertEquals(1, transport.forceChartsRequestCount);
            assertTrue(HorizonRadioClient.isChartRequestPending());
            assertFalse(screen.refreshButton().enabled);

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
    public void chartRequestRemainsPendingUntilTerminalResultsArrive() {
        HorizonRadioClient.sendChartsRequest(true);

        assertTrue(HorizonRadioClient.isChartRequestPending());
        assertFalse(
            HorizonRadioScreen.shouldEnableChartRefreshButton(false, HorizonRadioClient.isChartRequestPending()));

        HorizonRadioClient.updateChartResults(new ArrayList<HorizonRadioScreen.SearchResult>());

        assertFalse(HorizonRadioClient.isChartRequestPending());
        assertTrue(
            HorizonRadioScreen.shouldEnableChartRefreshButton(false, HorizonRadioClient.isChartRequestPending()));
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
    public void clientTransportExposesAllTemporaryNoopOperations() {
        HorizonRadioClient.sendSearch("lofi");
        HorizonRadioClient.sendChartsRequest();
        HorizonRadioClient.sendChartsRequest(true);
        HorizonRadioClient.sendAddChartsToPlaylist(new ArrayList<HorizonRadioScreen.SearchResult>());
        HorizonRadioClient.sendImportPlaylist("https://youtu.be/video?list=PLtest");
        HorizonRadioClient.sendImportVideo("https://youtu.be/video");
        HorizonRadioClient.sendAdd("abc", "Song", "3:21");
        HorizonRadioClient.sendPlayNow("abc", "Song", "3:21");
        HorizonRadioClient.sendRemove("abc");
        HorizonRadioClient.sendClearPlaylist();
        HorizonRadioClient.sendReady("abc");
        HorizonRadioClient.sendReorder(2, 1);
        HorizonRadioClient.sendSeek(0.75f);
        HorizonRadioClient.sendTogglePlayback();
        HorizonRadioClient.sendSkipTrack();
        HorizonRadioClient.sendPreviousTrack();
        HorizonRadioClient.sendToggleLoop();
        HorizonRadioClient.sendToggleShuffle();

        assertEquals("lofi", transport.searchQuery);
        assertTrue(transport.chartsRequest);
        assertTrue(transport.forceChartsRequest);
        assertEquals("https://youtu.be/video?list=PLtest", transport.importPlaylistUrl);
        assertEquals("https://youtu.be/video", transport.importVideoUrl);
        assertEquals("abc|Song|3:21", transport.addRequest);
        assertEquals("abc|Song|3:21", transport.playNowRequest);
        assertTrue(transport.addChartsRequest);
        assertEquals("abc", transport.removedVideoId);
        assertTrue(transport.clearPlaylist);
        assertEquals("abc", transport.readyVideoId);
        assertEquals("2|1", transport.reorderRequest);
        assertEquals(0.75f, transport.seekProgress, 0.0001f);
        assertTrue(transport.togglePlayback);
        assertTrue(transport.skipTrack);
        assertTrue(transport.previousTrack);
        assertTrue(transport.toggleLoop);
        assertTrue(transport.toggleShuffle);
    }

    @Test
    public void searchQueueButtonsTrackPlaylistAndIconsArePackaged() throws IOException {
        HorizonRadioScreen screen = new HorizonRadioScreen();
        List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
        entries.add(new HorizonRadioScreen.PlaylistEntry("queued", "Queued", "2:00", "Alice"));
        screen.updatePlaylist(entries);

        assertTrue(screen.isInQueue("queued"));
        assertFalse(screen.isInQueue("missing"));

        String[] iconNames = { "Shuffle.png", "Previous.png", "Play.png", "Next.png", "Repeat.png", "Pause.png" };
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
        TestScreen screen = resultScreen();

        screen.click(50, 62);

        assertEquals("video|Song|2:00", transport.playNowRequest);
        assertTrue(screen.isPlaylistTab());
        assertNull(transport.addRequest);
        assertFalse(transport.addChartsRequest);
    }

    @Test
    public void directSearchClickPlaysNowAndSwitchesToPlaylist() {
        TestScreen screen = new TestScreen();
        screen.setScreenSize(300, 285);
        screen.selectSearchTab();
        screen.updateSearchResults(singleResult());

        screen.click(50, 75);

        assertEquals("video|Song|2:00", transport.playNowRequest);
        assertTrue(screen.isPlaylistTab());
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
        assertTrue(screen.contains("HorizonRadioVolumeSlider"));
        assertTrue(screen.contains("boolean canRemove = true"));
        assertFalse(screen.contains("net." + "fabricmc"));

        assertTrue(keybinds.contains("Keyboard.KEY_N"));
        assertTrue(keybinds.contains("key.horizonradio.open_gui"));
        assertTrue(keybinds.contains("hasLoadedClientWorld"));
        assertTrue(keybinds.contains("minecraft.theWorld != null && minecraft.thePlayer != null"));
        assertTrue(proxy.contains("FMLCommonHandler.instance()"));
        assertTrue(proxy.contains(".bus()"));
        assertTrue(proxy.contains(".register(new ClientEvents())"));
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
        assertTrue(screen.contains("sendAddChartsToPlaylist"));
        assertTrue(screen.contains("beginChartLoading"));
        assertTrue(screen.contains("drawQueueButtonAt"));
        assertTrue(screen.contains("areAllChartsInQueue"));
        assertTrue(screen.contains("isChartsBulkButtonAt"));
        assertFalse(screen.contains("drawProgressBar(left, top, chartProgress)"));
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

    private static final class TestScreen extends HorizonRadioScreen {

        private void selectSearchTab() {
            actionPerformed(new GuiButton(9, 0, 0, "Search"));
        }

        private void setScreenSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private void click(int mouseX, int mouseY) {
            mouseClicked(mouseX, mouseY, 0);
        }

        private void initialize() {
            initGui();
        }

        private void invokeRefreshAction() {
            actionPerformed(refreshButton());
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
    }

    private static final class RecordingTransport implements HorizonRadioClient.ClientTransport {

        private String searchQuery;
        private boolean chartsRequest;
        private boolean forceChartsRequest;
        private int chartRequestCount;
        private int forceChartsRequestCount;
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

        @Override
        public void sendSearch(String query) {
            searchQuery = query;
        }

        @Override
        public void sendChartsRequest(boolean forceRefresh) {
            chartsRequest = true;
            forceChartsRequest = forceRefresh;
            chartRequestCount++;
            if (forceRefresh) {
                forceChartsRequestCount++;
            }
        }

        @Override
        public void sendImportPlaylist(String playlistUrl) {
            importPlaylistUrl = playlistUrl;
        }

        @Override
        public void sendImportVideo(String videoUrl) {
            importVideoUrl = videoUrl;
        }

        @Override
        public void sendAdd(String videoId, String title, String duration) {
            addRequest = videoId + "|" + title + "|" + duration;
        }

        @Override
        public void sendPlayNow(String videoId, String title, String duration) {
            playNowRequest = videoId + "|" + title + "|" + duration;
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
        public void sendReady(String videoId) {
            readyVideoId = videoId;
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
    }
}
