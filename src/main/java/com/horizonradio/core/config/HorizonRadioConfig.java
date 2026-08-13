package com.horizonradio.core.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/** The preserved JSON configuration for HorizonRadio. */
public final class HorizonRadioConfig {

    public static final int DEFAULT_MAX_PLAYLIST_SIZE = 50;
    public static final int DEFAULT_MAX_TRACK_DURATION_MINUTES = 15;
    public static final String DEFAULT_DOWNLOAD_DIR = "./horizonradio-downloads";
    public static final String DEFAULT_YOUTUBE_COOKIES_FROM_BROWSER = "";
    public static final String DEFAULT_YOUTUBE_COOKIES_FILE = "";
    public static final boolean DEFAULT_SERVER_DEBUG_CHAT = false;

    private final int maxPlaylistSize;
    private final int maxTrackDurationMinutes;
    private final String downloadDir;
    private final String youtubeCookiesFromBrowser;
    private final String youtubeCookiesFile;
    private final boolean serverDebugChat;

    private HorizonRadioConfig(int maxPlaylistSize, int maxTrackDurationMinutes, String downloadDir,
        String youtubeCookiesFromBrowser, String youtubeCookiesFile, boolean serverDebugChat) {
        this.maxPlaylistSize = maxPlaylistSize;
        this.maxTrackDurationMinutes = maxTrackDurationMinutes;
        this.downloadDir = downloadDir;
        this.youtubeCookiesFromBrowser = youtubeCookiesFromBrowser;
        this.youtubeCookiesFile = youtubeCookiesFile;
        this.serverDebugChat = serverDebugChat;
    }

    public static HorizonRadioConfig load(File configDirectory) {
        int maxPlaylistSize = DEFAULT_MAX_PLAYLIST_SIZE;
        int maxTrackDurationMinutes = DEFAULT_MAX_TRACK_DURATION_MINUTES;
        String downloadDir = DEFAULT_DOWNLOAD_DIR;
        String youtubeCookiesFromBrowser = DEFAULT_YOUTUBE_COOKIES_FROM_BROWSER;
        String youtubeCookiesFile = DEFAULT_YOUTUBE_COOKIES_FILE;
        boolean serverDebugChat = DEFAULT_SERVER_DEBUG_CHAT;

        if (configDirectory == null) {
            return new HorizonRadioConfig(
                maxPlaylistSize,
                maxTrackDurationMinutes,
                downloadDir,
                youtubeCookiesFromBrowser,
                youtubeCookiesFile,
                serverDebugChat);
        }

        File configFile = new File(configDirectory, "horizonradio.json");
        if (!configFile.isFile()) {
            return new HorizonRadioConfig(
                maxPlaylistSize,
                maxTrackDurationMinutes,
                downloadDir,
                youtubeCookiesFromBrowser,
                youtubeCookiesFile,
                serverDebugChat);
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(configFile), Charset.forName("UTF-8")));
            JsonObject object = new Gson().fromJson(reader, JsonObject.class);
            if (object != null) {
                if (object.has("maxPlaylistSize") && !object.get("maxPlaylistSize")
                    .isJsonNull()) {
                    maxPlaylistSize = object.get("maxPlaylistSize")
                        .getAsInt();
                }
                if (object.has("maxTrackDurationMinutes") && !object.get("maxTrackDurationMinutes")
                    .isJsonNull()) {
                    int configuredDuration = object.get("maxTrackDurationMinutes")
                        .getAsInt();
                    if (configuredDuration > 0) {
                        maxTrackDurationMinutes = configuredDuration;
                    }
                }
                if (object.has("downloadDir") && !object.get("downloadDir")
                    .isJsonNull()) {
                    downloadDir = object.get("downloadDir")
                        .getAsString();
                }
                if (object.has("youtubeCookiesFromBrowser") && !object.get("youtubeCookiesFromBrowser")
                    .isJsonNull()) {
                    youtubeCookiesFromBrowser = object.get("youtubeCookiesFromBrowser")
                        .getAsString();
                }
                if (object.has("youtubeCookiesFile") && !object.get("youtubeCookiesFile")
                    .isJsonNull()) {
                    youtubeCookiesFile = object.get("youtubeCookiesFile")
                        .getAsString();
                }
                if (object.has("serverDebugChat") && !object.get("serverDebugChat")
                    .isJsonNull()) {
                    serverDebugChat = object.get("serverDebugChat")
                        .getAsBoolean();
                }
            }
        } catch (IOException e) {
            // Keep the documented defaults when the optional file cannot be read.
        } catch (JsonParseException e) {
            // Keep the documented defaults when the optional file is malformed.
        } catch (RuntimeException e) {
            // Gson 2.2 may report an invalid primitive as another runtime exception.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Defaults or successfully parsed values are already available.
                }
            }
        }

        return new HorizonRadioConfig(
            maxPlaylistSize,
            maxTrackDurationMinutes,
            downloadDir,
            youtubeCookiesFromBrowser,
            youtubeCookiesFile,
            serverDebugChat);
    }

    public int getMaxPlaylistSize() {
        return maxPlaylistSize;
    }

    public int getMaxTrackDurationMinutes() {
        return maxTrackDurationMinutes;
    }

    public String getDownloadDir() {
        return downloadDir;
    }

    public String getYoutubeCookiesFromBrowser() {
        return youtubeCookiesFromBrowser;
    }

    public String getYoutubeCookiesFile() {
        return youtubeCookiesFile;
    }

    public boolean isServerDebugChat() {
        return serverDebugChat;
    }
}
