package com.horizonradio.core.server;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.horizonradio.core.model.SearchResult;

/** Parses metadata returned by the embedded Java YouTube resolver for playlist imports. */
public final class PlaylistImportService {

    static final int MAX_IMPORT_ENTRIES = 50;

    private PlaylistImportService() {}

    public static boolean isPlaylistUrl(String value) {
        if (value == null || value.trim()
            .length() == 0) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!isYouTubeHost(uri)) {
                return false;
            }
            String query = uri.getRawQuery();
            if (query == null) {
                return false;
            }
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("list=") && parameter.length() > 5) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static boolean isVideoUrl(String value) {
        if (value == null || value.trim()
            .length() == 0) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!isYouTubeHost(uri)) {
                return false;
            }
            String query = uri.getRawQuery();
            if (query != null) {
                for (String parameter : query.split("&")) {
                    if (parameter.startsWith("list=") && parameter.length() > 5) {
                        return false;
                    }
                }
            }
            if ("youtu.be".equalsIgnoreCase(uri.getHost())) {
                return uri.getPath() != null && uri.getPath()
                    .length() > 1;
            }
            if (query != null) {
                for (String parameter : query.split("&")) {
                    if (parameter.startsWith("v=") && parameter.length() > 2) {
                        return true;
                    }
                }
            }
            String path = uri.getPath();
            return path != null && (path.startsWith("/shorts/") || path.startsWith("/live/"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static List<SearchResult> parse(String json) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        Set<String> seenIds = new HashSet<String>();
        if (json == null || json.trim()
            .length() == 0) {
            return results;
        }
        try {
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            JsonElement entriesElement = root == null ? null : root.get("entries");
            if (entriesElement == null || !entriesElement.isJsonArray()) {
                return results;
            }
            JsonArray entries = entriesElement.getAsJsonArray();
            for (JsonElement element : entries) {
                if (results.size() >= MAX_IMPORT_ENTRIES) {
                    break;
                }
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String videoId = getString(entry, "id");
                if (videoId.length() == 0) {
                    videoId = videoIdFromUrl(getString(entry, "url"));
                }
                String title = getString(entry, "title");
                String duration = getString(entry, "duration_string");
                if (duration.length() == 0) {
                    duration = formatDuration(entry.get("duration"));
                }
                if (videoId.length() == 0 || title.length() == 0 || duration.length() == 0 || !seenIds.add(videoId)) {
                    continue;
                }
                results.add(new SearchResult(videoId, title, "", duration, ""));
            }
        } catch (RuntimeException ignored) {
            // Import failures are reported by the caller as an empty import.
        }
        return results;
    }

    public static SearchResult parseVideo(String json) {
        if (json == null || json.trim()
            .length() == 0) {
            return null;
        }
        try {
            JsonObject video = new Gson().fromJson(json, JsonObject.class);
            if (video == null) {
                return null;
            }
            String videoId = getString(video, "id");
            if (videoId.length() == 0) {
                videoId = videoIdFromUrl(getString(video, "webpage_url"));
            }
            String title = getString(video, "title");
            String duration = getString(video, "duration_string");
            if (duration.length() == 0) {
                duration = formatDuration(video.get("duration"));
            }
            return videoId.length() == 0 || title.length() == 0 || duration.length() == 0 ? null
                : new SearchResult(videoId, title, "", duration, "");
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static Map<String, String> parseDurationOutput(String output) {
        Map<String, String> durations = new HashMap<String, String>();
        if (output == null) {
            return durations;
        }
        for (String line : output.split("\\r?\\n")) {
            int separator = line.indexOf('\t');
            int separatorLength = 1;
            if (separator < 0) {
                separator = line.indexOf("\\t");
                if (separator < 0) {
                    continue;
                }
                separatorLength = 2;
            }
            String videoId = line.substring(0, separator)
                .trim();
            String duration = line.substring(separator + separatorLength)
                .trim();
            if (videoId.length() > 0 && duration.length() > 0 && !"NA".equalsIgnoreCase(duration)) {
                durations.put(videoId, duration);
            }
        }
        return durations;
    }

    private static boolean isYouTubeHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ENGLISH);
        return host.equals("youtube.com") || host.equals("www.youtube.com")
            || host.equals("m.youtube.com")
            || host.equals("music.youtube.com")
            || host.equals("youtu.be");
    }

    private static String getString(JsonObject object, String memberName) {
        JsonElement member = object.get(memberName);
        return member != null && member.isJsonPrimitive() ? member.getAsString() : "";
    }

    private static String videoIdFromUrl(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        int queryIndex = value.indexOf("v=");
        if (queryIndex >= 0) {
            String id = value.substring(queryIndex + 2);
            int separator = id.indexOf('&');
            return separator >= 0 ? id.substring(0, separator) : id;
        }
        return value.indexOf('/') >= 0 ? "" : value;
    }

    private static String formatDuration(JsonElement durationElement) {
        if (durationElement == null || !durationElement.isJsonPrimitive()) {
            return "";
        }
        try {
            long totalSeconds = durationElement.getAsLong();
            if (totalSeconds < 0L) {
                return "";
            }
            long hours = totalSeconds / 3600L;
            long minutes = (totalSeconds % 3600L) / 60L;
            long seconds = totalSeconds % 60L;
            if (hours > 0L) {
                return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
            }
            return minutes + ":" + twoDigits(seconds);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String twoDigits(long value) {
        return value < 10L ? "0" + value : String.valueOf(value);
    }
}
