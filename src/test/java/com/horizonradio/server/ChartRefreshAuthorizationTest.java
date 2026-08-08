package com.horizonradio.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChartRefreshAuthorizationTest {

    @Test
    public void onlyOperatorsMayForceRefreshCharts() {
        assertFalse(PlaylistManager.canRefreshCharts(true, false));
        assertTrue(PlaylistManager.canRefreshCharts(true, true));
    }

    @Test
    public void normalChartLoadingDoesNotRequireOperatorAccess() {
        assertTrue(PlaylistManager.canRefreshCharts(false, false));
        assertTrue(PlaylistManager.canRefreshCharts(false, true));
    }
}
