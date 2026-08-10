package com.horizonradio.core.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.horizonradio.core.model.SearchResult;

/** Persistent seven-day cache for the completed weekly chart metadata. */
public final class ChartCache {

    static final long TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final String FILE_NAME = "horizonradio-charts.json";
    private static final Logger LOGGER = Logger.getLogger(ChartCache.class.getName());

    private final Gson gson = new Gson();
    private final File cacheFile;
    private final Map<String, CacheEntry> entries = new LinkedHashMap<String, CacheEntry>();

    public ChartCache(File configDirectory) {
        cacheFile = configDirectory == null ? null : new File(configDirectory, FILE_NAME);
        load();
    }

    public synchronized List<SearchResult> getResults() {
        return getResults("DE");
    }

    public synchronized List<SearchResult> getResults(String regionCode) {
        CacheEntry entry = entries.get(normalizeRegionCode(regionCode));
        return entry == null ? new ArrayList<SearchResult>() : new ArrayList<SearchResult>(entry.results);
    }

    public synchronized boolean isFresh() {
        return isFresh("DE");
    }

    public synchronized boolean isFresh(String regionCode) {
        CacheEntry entry = entries.get(normalizeRegionCode(regionCode));
        return entry != null && !entry.results.isEmpty()
            && entry.fetchedAt > 0L
            && System.currentTimeMillis() - entry.fetchedAt < TTL_MILLIS;
    }

    public synchronized void store(List<SearchResult> newResults) {
        store("DE", newResults);
    }

    public synchronized void store(String regionCode, List<SearchResult> newResults) {
        if (newResults == null || newResults.isEmpty()) {
            return;
        }
        CacheEntry entry = new CacheEntry();
        entry.results = new ArrayList<SearchResult>(newResults);
        entry.fetchedAt = System.currentTimeMillis();
        entries.put(normalizeRegionCode(regionCode), entry);
        write();
    }

    synchronized boolean hasResults() {
        return hasResults("DE");
    }

    public synchronized boolean hasResults(String regionCode) {
        return !getResults(regionCode).isEmpty();
    }

    synchronized void invalidate() {
        invalidate("DE");
    }

    public synchronized void invalidate(String regionCode) {
        CacheEntry entry = entries.get(normalizeRegionCode(regionCode));
        if (entry != null) {
            entry.fetchedAt = 0L;
        }
    }

    private void load() {
        if (cacheFile == null || !cacheFile.isFile()) {
            return;
        }
        try (Reader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            CacheData data = gson.fromJson(reader, CacheData.class);
            if (data == null) {
                return;
            }
            if (data.regions != null) {
                for (Map.Entry<String, CacheEntry> region : data.regions.entrySet()) {
                    CacheEntry entry = region.getValue();
                    if (entry != null && entry.results != null && entry.fetchedAt > 0L) {
                        CacheEntry copy = new CacheEntry();
                        copy.results = new ArrayList<SearchResult>(entry.results);
                        copy.fetchedAt = entry.fetchedAt;
                        entries.put(normalizeRegionCode(region.getKey()), copy);
                    }
                }
            } else if (data.results != null && data.fetchedAt > 0L) {
                CacheEntry legacy = new CacheEntry();
                legacy.results = new ArrayList<SearchResult>(data.results);
                legacy.fetchedAt = data.fetchedAt;
                entries.put("DE", legacy);
            }
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Could not load cached HorizonRadio charts", exception);
        }
    }

    private void write() {
        if (cacheFile == null) {
            return;
        }
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOGGER.warning("Could not create the HorizonRadio chart cache directory");
            return;
        }

        File temporaryFile = new File(cacheFile.getPath() + ".tmp");
        CacheData data = new CacheData();
        data.regions = new LinkedHashMap<String, CacheEntry>();
        for (Map.Entry<String, CacheEntry> entry : entries.entrySet()) {
            CacheEntry copy = new CacheEntry();
            copy.fetchedAt = entry.getValue().fetchedAt;
            copy.results = new ArrayList<SearchResult>(entry.getValue().results);
            data.regions.put(entry.getKey(), copy);
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temporaryFile), StandardCharsets.UTF_8)) {
            gson.toJson(data, writer);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not write cached HorizonRadio charts", exception);
            temporaryFile.delete();
            return;
        }

        try {
            try {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not replace cached HorizonRadio charts", exception);
            temporaryFile.delete();
        }
    }

    private static final class CacheData {

        private Map<String, CacheEntry> regions;
        private long fetchedAt;
        private List<SearchResult> results = Collections.emptyList();
    }

    private static final class CacheEntry {

        private long fetchedAt;
        private List<SearchResult> results = Collections.emptyList();
    }

    private static String normalizeRegionCode(String regionCode) {
        if (regionCode == null || regionCode.trim()
            .length() == 0) {
            throw new IllegalArgumentException("chart region code must not be empty");
        }
        return regionCode.trim()
            .toUpperCase(Locale.ROOT);
    }
}
