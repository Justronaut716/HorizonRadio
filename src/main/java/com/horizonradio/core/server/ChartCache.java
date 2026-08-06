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
import java.util.List;
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
    private List<SearchResult> results = new ArrayList<SearchResult>();
    private long fetchedAt;

    public ChartCache(File configDirectory) {
        cacheFile = configDirectory == null ? null : new File(configDirectory, FILE_NAME);
        load();
    }

    public synchronized List<SearchResult> getResults() {
        return new ArrayList<SearchResult>(results);
    }

    synchronized boolean hasResults() {
        return !results.isEmpty();
    }

    public synchronized boolean isFresh() {
        return hasResults() && fetchedAt > 0L && System.currentTimeMillis() - fetchedAt < TTL_MILLIS;
    }

    synchronized void invalidate() {
        fetchedAt = 0L;
    }

    public synchronized void store(List<SearchResult> newResults) {
        if (newResults == null || newResults.isEmpty()) {
            return;
        }
        results = new ArrayList<SearchResult>(newResults);
        fetchedAt = System.currentTimeMillis();
        write();
    }

    private void load() {
        if (cacheFile == null || !cacheFile.isFile()) {
            return;
        }
        try (Reader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            CacheData data = gson.fromJson(reader, CacheData.class);
            if (data != null && data.results != null && data.fetchedAt > 0L) {
                results = new ArrayList<SearchResult>(data.results);
                fetchedAt = data.fetchedAt;
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
        data.fetchedAt = fetchedAt;
        data.results = results;
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

        private long fetchedAt;
        private List<SearchResult> results = Collections.emptyList();
    }
}
