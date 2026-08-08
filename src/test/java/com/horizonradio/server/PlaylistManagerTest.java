package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.EnumChatFormatting;

import org.junit.Test;

public class PlaylistManagerTest {

    @Test
    public void cachedChartsAreTerminalOnlyForFreshNonForceRequests() {
        assertTrue(PlaylistManager.shouldServeCachedCharts(true, false, true));
        assertFalse(PlaylistManager.shouldServeCachedCharts(true, true, true));
        assertFalse(PlaylistManager.shouldServeCachedCharts(true, false, false));
        assertFalse(PlaylistManager.shouldServeCachedCharts(false, false, true));
    }

    @Test
    public void freshNonForceChartRequestSendsCachedResultsWithoutRefreshActions() {
        RecordingChartActions actions = process(false, false, true, true);

        assertEquals(list("results"), actions.events);
    }

    @Test
    public void staleNonForceChartRequestWaitsForRefreshBeforeSendingResults() {
        RecordingChartActions actions = process(false, false, true, false);

        assertEquals(list("chat:YELLOW:Loading German YouTube Music Top 50...", "waiter", "refresh"), actions.events);
    }

    @Test
    public void forceChartRequestWithCacheWaitsForRefreshBeforeSendingResults() {
        RecordingChartActions actions = process(true, true, true, true);

        assertEquals(
            list("chat:YELLOW:Refreshing German YouTube Music Top 50...", "waiter", "refresh"),
            actions.events);
    }

    @Test
    public void unauthorizedForceChartRequestSendsCachedResultsAndDenialWithoutRefreshActions() {
        RecordingChartActions actions = process(true, false, true, false);

        assertEquals(list("results", "chat:RED:Only server operators can refresh the charts."), actions.events);
    }

    private static RecordingChartActions process(boolean forceRefresh, boolean operator, boolean hasCachedCharts,
        boolean cacheFresh) {
        RecordingChartActions actions = new RecordingChartActions();
        PlaylistManager.processChartRequest(forceRefresh, operator, hasCachedCharts, cacheFresh, actions);
        return actions;
    }

    private static List<String> list(String... events) {
        List<String> result = new ArrayList<String>();
        for (String event : events) {
            result.add(event);
        }
        return result;
    }

    private static final class RecordingChartActions implements PlaylistManager.ChartRequestActions {

        private final List<String> events = new ArrayList<String>();

        @Override
        public void sendChartResults() {
            events.add("results");
        }

        @Override
        public void sendChat(EnumChatFormatting color, String message) {
            events.add("chat:" + color.name() + ":" + message);
        }

        @Override
        public void registerWaiter() {
            events.add("waiter");
        }

        @Override
        public void refresh() {
            events.add("refresh");
        }
    }
}
