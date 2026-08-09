package com.horizonradio.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.horizonradio.core.model.SearchResult;

public class ChartCacheTest {

    @Test
    public void persistsWeeklyChartsAndCanBeInvalidated() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-chart-cache")
            .toFile();
        try {
            List<SearchResult> results = Arrays.asList(new SearchResult("id", "Song", "Channel", "3:21", "thumb"));

            ChartCache cache = new ChartCache(directory);
            cache.store(results);
            ChartCache reloaded = new ChartCache(directory);

            assertEquals(results, reloaded.getResults());
            assertTrue(reloaded.isFresh());
            reloaded.invalidate();
            assertFalse(reloaded.isFresh());
            assertEquals(results, reloaded.getResults());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void storesRegionsIndependently() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-chart-cache-regions")
            .toFile();
        try {
            ChartCache cache = new ChartCache(directory);
            List<SearchResult> german = Arrays.asList(new SearchResult("de-id", "DE", "", "2:00", ""));
            List<SearchResult> global = Arrays.asList(new SearchResult("global-id", "Global", "", "2:00", ""));

            cache.store("DE", german);
            cache.store("GLOBAL", global);

            assertEquals(german, cache.getResults("DE"));
            assertEquals(global, cache.getResults("GLOBAL"));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void loadsLegacySingleRegionCacheAsGerman() throws IOException {
        File directory = Files.createTempDirectory("horizonradio-chart-cache-legacy")
            .toFile();
        try {
            File cacheFile = new File(directory, "horizonradio-charts.json");
            Files.write(
                cacheFile.toPath(),
                ("{\"fetchedAt\":1,\"results\":[{\"videoId\":\"legacy\","
                    + "\"title\":\"Legacy\",\"channel\":\"Channel\","
                    + "\"duration\":\"2:00\",\"thumbnail\":\"\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

            ChartCache cache = new ChartCache(directory);

            assertEquals(
                "legacy",
                cache.getResults("DE")
                    .get(0)
                    .getVideoId());
            assertTrue(
                cache.getResults("GLOBAL")
                    .isEmpty());
        } finally {
            deleteRecursively(directory);
        }
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
}
