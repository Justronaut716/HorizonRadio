package com.horizonradio.client;

import static org.junit.Assert.*;

import org.junit.Test;

public class NotificationCenterTest {

    @Test
    public void queueAdditionWaitsForTitleAndIsAnnouncedOnlyOnce() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot loading = new NotificationCenter.Snapshot();
        loading.queue.put("song", null);
        center.observe(loading, 100L);
        assertNull(center.current(100L));
        NotificationCenter.Snapshot loaded = new NotificationCenter.Snapshot();
        loaded.queue.put("song", "Actual song title");
        center.observe(loaded, 2000L);
        assertEquals("Added to queue", center.current(2000L).title);
        assertEquals("Actual song title", center.current(2000L).detail);
        center.observe(loaded, 6100L);
        assertNull(center.current(6100L));
    }

    @Test
    public void removedUnresolvedAdditionsAndInitialQueueAreNotAnnouncedLater() {
        NotificationCenter center = new NotificationCenter();
        NotificationCenter.Snapshot loading = new NotificationCenter.Snapshot();
        loading.queue.put("song", null);
        center.observe(loading, 0L);
        NotificationCenter.Snapshot loaded = new NotificationCenter.Snapshot();
        loaded.queue.put("song", "Title");
        center.observe(loaded, 100L);
        assertNull(center.current(100L));
        center.clear();
        center.observe(new NotificationCenter.Snapshot(), 200L);
        center.observe(loading, 300L);
        center.observe(new NotificationCenter.Snapshot(), 400L);
        assertNull(center.current(400L));
        center.observe(new NotificationCenter.Snapshot(), 5000L);
        assertNull(center.current(5000L));
    }

    @Test
    public void positionPreviewWorksWithNotificationsDisabledAndExpires() {
        NotificationCenter center = new NotificationCenter();
        ClientUiSettings settings = new ClientUiSettings();
        settings.notifications = false;
        center.configure(settings);
        for (ClientUiSettings.Position position : ClientUiSettings.Position.values()) {
            settings.position = position;
            center.preview(100L);
            assertEquals("Notification preview", center.current(100L).title);
            assertEquals(position.label, center.current(100L).detail);
            assertNull(center.current(100L + NotificationCenter.DISPLAY_MILLIS));
        }
    }

    @Test
    public void changingPositionReplacesPreviewImmediatelyAndRestartsItsLifetime() {
        NotificationCenter center = new NotificationCenter();
        ClientUiSettings settings = new ClientUiSettings();
        center.configure(settings);
        center.post("queue-add", "Added to queue", "Song", 0L);
        center.preview(100L);
        settings.position = ClientUiSettings.Position.TOP_MIDDLE;
        center.configure(settings);
        center.preview(3500L);
        center.post("playback", "Now playing", "Song", 3600L);
        assertEquals("preview", center.current(4100L).key);
        assertEquals(settings.position.label, center.current(4100L).detail);
        assertEquals(3500L, center.current(4100L).startedAt);
        assertEquals("playback", center.current(7500L).key);
        assertEquals("queue-add", center.current(7600L).key);
    }

    @Test
    public void disconnectAlsoClearsPreview() {
        NotificationCenter center = new NotificationCenter();
        center.preview(100L);
        center.clear();
        assertNull(center.current(101L));
    }

    @Test
    public void initialSynchronizationDoesNotFloodThePlayer() {
        NotificationCenter center = new NotificationCenter();
        NotificationCenter.Snapshot initial = new NotificationCenter.Snapshot();
        initial.queue.put("song", "Song");
        initial.favorites.put("song", "Song");
        center.observe(initial, 100L);
        assertNull(center.current(100L));
    }

    @Test
    public void confirmedTrackChangeIsImmediateAndMetadataRefreshDoesNotDuplicateIt() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot playing = playing("song", "YouTube song");
        center.observe(playing, 100L);
        assertEquals("Now playing", center.current(100L).title);
        center.observe(playing("song", "Real title"), 200L);
        assertEquals("Real title", center.current(200L).detail);
        center.observe(playing("song", "Real title"), 300L);
        assertNull(center.current(4100L));
    }

    @Test
    public void radioAndPlaybackStopAreReported() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot radio = playing("radio", "Station");
        radio.radio = true;
        center.observe(radio, 100L);
        assertEquals("Radio playing", center.current(100L).title);
        center.observe(new NotificationCenter.Snapshot(), 1200L);
        assertEquals("Playback stopped", center.current(1200L).title);
        assertEquals("Station", center.current(1200L).detail);
    }

    @Test
    public void bulkQueueUpdatesAreSummarizedAndReorderIsDetected() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot queue = new NotificationCenter.Snapshot();
        queue.queue.put("a", "A");
        queue.queue.put("b", "B");
        center.observe(queue, 100L);
        assertEquals("2 entries added", center.current(100L).detail);
        NotificationCenter.Snapshot reordered = new NotificationCenter.Snapshot();
        reordered.queue.put("b", "B");
        reordered.queue.put("a", "A");
        center.observe(reordered, 4200L);
        assertEquals("Queue reordered", center.current(4200L).title);
        center.observe(new NotificationCenter.Snapshot(), 8300L);
        assertEquals("Queue cleared", center.current(8300L).title);
    }

    @Test
    public void volumeChangesWaitUntilTheSliderSettles() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot volume = new NotificationCenter.Snapshot();
        volume.volume = 70;
        center.observe(volume, 100L);
        center.observe(volume, 599L);
        assertNull(center.current(599L));
        center.observe(volume, 600L);
        assertEquals("70%", center.current(600L).detail);
    }

    @Test
    public void queueIsBoundedAndDisconnectClearsEverything() {
        NotificationCenter center = new NotificationCenter();
        for (int i = 0; i < 20; i++) center.post("event" + i, "Title", "Detail" + i, 100L);
        assertEquals("Detail15", center.current(100L).detail);
        center.clear();
        assertNull(center.current(101L));
    }

    @Test
    public void countdownChangesDoNotProduceRepeatedErrorPopups() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot error = new NotificationCenter.Snapshot();
        error.error = "YouTube rate limit - retrying in 30s";
        center.observe(error, 100L);
        assertEquals("error", center.current(100L).key);
        NotificationCenter.Snapshot countdown = new NotificationCenter.Snapshot();
        countdown.error = "YouTube rate limit - retrying in 25s";
        center.observe(countdown, 5100L);
        assertNull(center.current(5100L));
    }

    @Test
    public void favoritesAreReportedWithoutReplayingMetadataUpdates() {
        NotificationCenter center = new NotificationCenter();
        center.observe(new NotificationCenter.Snapshot(), 0L);
        NotificationCenter.Snapshot favorites = new NotificationCenter.Snapshot();
        favorites.favorites.put("radio:a", "Station");
        center.observe(favorites, 100L);
        assertEquals("Favorite added", center.current(100L).title);
        center.observe(new NotificationCenter.Snapshot(), 4200L);
        assertEquals("Favorite removed", center.current(4200L).title);
    }

    private static NotificationCenter.Snapshot playing(String id, String title) {
        NotificationCenter.Snapshot state = new NotificationCenter.Snapshot();
        state.trackKey = id;
        state.trackId = id;
        state.trackTitle = title;
        return state;
    }
}
