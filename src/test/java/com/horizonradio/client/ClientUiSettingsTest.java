package com.horizonradio.client;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;

public class ClientUiSettingsTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultsUseTopMiddleAndThreeHundredMillisecondSearch() {
        ClientUiSettings settings = ClientUiSettings.fromJson(null);
        assertEquals(300, settings.searchDelay);
        assertEquals(ClientUiSettings.Position.TOP_MIDDLE, settings.position);
        assertTrue(settings.autoSearch);
        assertTrue(settings.allows("playback"));
        assertTrue(settings.allows("search-radio"));
    }

    @Test
    public void preferencesSurviveOtherConfigurationWritesAndReload() throws Exception {
        File directory = temporary.newFolder();
        HorizonRadioClientConfig config = HorizonRadioClientConfig.load(directory);
        ClientUiSettings settings = config.getUiSettings();
        settings.enabled.remove(ClientUiSettings.Category.SEARCH);
        settings.position = ClientUiSettings.Position.BOTTOM_LEFT;
        settings.searchDelay = 1200;
        settings.songResults = 25;
        settings.radioResults = 75;
        settings.autoSearch = false;
        config.save(0.5f);
        HorizonRadioClientConfig reloaded = HorizonRadioClientConfig.load(directory);
        reloaded.save(0.7f);
        ClientUiSettings actual = HorizonRadioClientConfig.load(directory)
            .getUiSettings();
        assertEquals(ClientUiSettings.Position.BOTTOM_LEFT, actual.position);
        assertEquals(1200, actual.searchDelay);
        assertEquals(25, actual.songResults);
        assertEquals(75, actual.radioResults);
        assertFalse(actual.autoSearch);
        assertFalse(actual.allows("search-songs"));
        assertFalse(actual.allows("search-radio"));
        assertTrue(actual.allows("queue-add"));
    }

    @Test
    public void invalidFieldsUseDefaultsAndDelaysAreBounded() {
        JsonObject object = new JsonObject();
        object.addProperty("position", "unknown");
        object.addProperty("notifications", "invalid");
        object.addProperty("searchDelay", -10);
        ClientUiSettings settings = ClientUiSettings.fromJson(object);
        assertEquals(100, settings.searchDelay);
        assertTrue(settings.notifications);
        assertEquals(ClientUiSettings.Position.TOP_MIDDLE, settings.position);
        object.addProperty("searchDelay", 99999);
        assertEquals(3000, ClientUiSettings.fromJson(object).searchDelay);
    }

    @Test
    public void disablingCategoryRemovesVisibleAndWaitingNotices() {
        NotificationCenter center = new NotificationCenter();
        ClientUiSettings settings = new ClientUiSettings();
        center.post("queue-add", "Added", "A", 0L);
        assertNotNull(center.current(0L));
        center.post("queue-remove", "Removed", "B", 100L);
        settings.enabled.remove(ClientUiSettings.Category.QUEUE);
        center.configure(settings);
        assertNull(center.current(100L));
        center.post("queue-add", "Added", "C", 200L);
        assertNull(center.current(200L));
        center.post("playback", "Playing", "Song", 300L);
        assertNotNull(center.current(300L));
        settings.notifications = false;
        center.configure(settings);
        assertNull(center.current(300L));
    }
}
