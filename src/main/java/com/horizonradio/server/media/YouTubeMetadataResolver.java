package com.horizonradio.server.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves the small, application-facing subset of YouTube metadata through
 * finite InnerTube player and browse responses.
 */
public final class YouTubeMetadataResolver {

    private static final String PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String BROWSE_ENDPOINT = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String CLIENT_VERSION = "20.10.38";
    private static final String CLIENT_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
    private static final int TIMEOUT_MILLIS = 10000;
    private static final long MAX_PLAYER_BYTES = 512L * 1024L;
    private static final long MAX_BROWSE_BYTES = 1024L * 1024L;
    private static final int MAX_PLAYLIST_ENTRIES = 50;
    private static final int MAX_RAW_PLAYLIST_RENDERERS = 200;
    private static final int MAX_PLAYLIST_PAGES = 5;
    private static final int MAX_DURATION_IDS = 50;
    private static final long MAX_DURATION_SECONDS = 24L * 60L * 60L;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_CONTINUATION_LENGTH = 512;

    private final YouTubeMediaModels.HttpRequester http;
    private final Gson gson = new Gson();

    public YouTubeMetadataResolver() {
        this(new YouTubeMediaModels.UrlConnectionHttpRequester());
    }

    public YouTubeMetadataResolver(YouTubeMediaModels.HttpRequester http) {
        if (http == null) {
            throw new IllegalArgumentException("HTTP requester is required");
        }
        this.http = http;
    }

    /** Returns PlaylistImportService-compatible video JSON, or null for safe failures. */
    public String resolveVideoJson(String videoUrl) {
        try {
            String videoId = YouTubeUrlParser.parseVideoId(videoUrl);
            VideoMetadata metadata = fetchVideo(videoId);
            return metadata == null ? null : gson.toJson(metadata.toJson());
        } catch (IOException exception) {
            return null;
        }
    }

    /** Returns PlaylistImportService-compatible playlist JSON, or null for safe failures. */
    public String resolvePlaylistJson(String playlistUrl) {
        try {
            String playlistId = parsePlaylistId(playlistUrl);
            List<VideoMetadata> entries = new ArrayList<VideoMetadata>();
            Set<String> seenIds = new HashSet<String>();
            String continuation = null;
            int rawRendererCount = 0;
            for (int page = 0; page < MAX_PLAYLIST_PAGES && entries.size() < MAX_PLAYLIST_ENTRIES
                && rawRendererCount < MAX_RAW_PLAYLIST_RENDERERS; page++) {
                checkInterrupted();
                JsonObject response = continuation == null ? browsePlaylist(playlistId)
                    : browseContinuation(continuation);
                if (response == null) break;
                PlaylistItems items = collectPlaylistItems(
                    response,
                    seenIds,
                    MAX_PLAYLIST_ENTRIES - entries.size(),
                    MAX_RAW_PLAYLIST_RENDERERS - rawRendererCount);
                rawRendererCount += items.rawRendererCount;
                entries.addAll(items.entries);
                continuation = isSafeContinuation(items.continuation) ? items.continuation : null;
                if (continuation == null) break;
            }
            JsonObject output = new JsonObject();
            JsonArray outputEntries = new JsonArray();
            for (VideoMetadata entry : entries) outputEntries.add(entry.toJson());
            output.add("entries", outputEntries);
            return gson.toJson(output);
        } catch (IOException exception) {
            return null;
        }
    }

    /** Returns the legacy tab-separated duration output for at most fifty valid requested IDs. */
    public String resolveDurationOutput(List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return "";
        List<String> validIds = new ArrayList<String>();
        for (String videoId : videoIds) {
            if (validIds.size() >= MAX_DURATION_IDS) break;
            try {
                validIds.add(YouTubeUrlParser.requireVideoId(videoId == null ? null : videoId.trim()));
            } catch (IOException ignored) {
                // The existing caller expects absent invalid IDs, not an unsafe request.
            }
        }
        StringBuilder output = new StringBuilder();
        for (String videoId : validIds) {
            if (output.length() > 0) output.append('\n');
            String duration = "NA";
            try {
                checkInterrupted();
                VideoMetadata metadata = fetchVideo(videoId);
                if (metadata != null) duration = metadata.durationString;
            } catch (IOException ignored) {
                // Every valid requested ID retains one parseable output line.
            }
            output.append(videoId)
                .append('\t')
                .append(duration);
        }
        return output.toString();
    }

    private VideoMetadata fetchVideo(String videoId) throws IOException {
        JsonObject player = postJson(PLAYER_ENDPOINT, playerRequest(videoId), MAX_PLAYER_BYTES);
        JsonObject playability = object(player, "playabilityStatus");
        if (playability == null || !"OK".equals(string(playability, "status"))) return null;
        JsonObject details = object(player, "videoDetails");
        if (details == null || bool(details, "isLiveContent")) return null;
        String id = string(details, "videoId");
        String title = string(details, "title");
        long duration = positiveDuration(string(details, "lengthSeconds"));
        try {
            id = YouTubeUrlParser.requireVideoId(id);
        } catch (IOException invalid) {
            return null;
        }
        return title.length() == 0 || duration <= 0L ? null : new VideoMetadata(id, title, duration);
    }

    private JsonObject browsePlaylist(String playlistId) throws IOException {
        JsonObject request = context();
        request.addProperty("browseId", "VL" + playlistId);
        return postJson(BROWSE_ENDPOINT, request, MAX_BROWSE_BYTES);
    }

    private JsonObject browseContinuation(String continuation) throws IOException {
        JsonObject request = context();
        request.addProperty("continuation", continuation);
        return postJson(BROWSE_ENDPOINT, request, MAX_BROWSE_BYTES);
    }

    private JsonObject postJson(String endpoint, JsonObject request, long maximumBytes) throws IOException {
        byte[] body = gson.toJson(request)
            .getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("User-Agent", CLIENT_USER_AGENT);
        headers.put("X-YouTube-Client-Name", "3");
        headers.put("X-YouTube-Client-Version", CLIENT_VERSION);
        try (YouTubeMediaModels.HttpResponse response = http.post(
            new URL(endpoint),
            headers,
            body,
            TIMEOUT_MILLIS,
            maximumBytes,
            YouTubeMediaModels.RedirectPolicy.INNER_TUBE)) {
            return new JsonParser().parse(readExactly(response, maximumBytes))
                .getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new MediaException("Invalid YouTube JSON response", exception);
        }
    }

    private static String readExactly(YouTubeMediaModels.HttpResponse response, long maximumBytes) throws IOException {
        if (response == null || response.getContentLength() < 0L || response.getContentLength() > maximumBytes) {
            throw new MediaException("YouTube response does not declare a safe length");
        }
        InputStream input = response.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(response.getContentLength(), 8192L));
        byte[] buffer = new byte[4096];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > maximumBytes || total > response.getContentLength()) {
                throw new MediaException("YouTube response body exceeds its declared length");
            }
            output.write(buffer, 0, count);
        }
        if (total != response.getContentLength()) throw new MediaException("YouTube response body is truncated");
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static PlaylistItems collectPlaylistItems(JsonElement response, Set<String> seenIds, int entryLimit,
        int rawRendererLimit) {
        PlaylistItems result = new PlaylistItems(entryLimit, rawRendererLimit);
        collectPlaylistItems(response, result, seenIds, 0);
        return result;
    }

    private static void collectPlaylistItems(JsonElement value, PlaylistItems result, Set<String> seenIds, int depth) {
        if (value == null || value.isJsonNull()
            || depth > 64
            || result.entries.size() >= result.entryLimit
            || result.rawRendererCount >= result.rawRendererLimit) return;
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray())
                collectPlaylistItems(element, result, seenIds, depth + 1);
            return;
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        JsonObject renderer = object(object, "playlistVideoRenderer");
        if (renderer != null) {
            result.rawRendererCount++;
            VideoMetadata entry = playlistEntry(renderer);
            if (entry != null && seenIds.add(entry.id)) result.entries.add(entry);
            return;
        }
        JsonObject continuationItem = object(object, "continuationItemRenderer");
        if (continuationItem != null && result.continuation == null) {
            JsonObject endpoint = object(continuationItem, "continuationEndpoint");
            JsonObject command = endpoint == null ? null : object(endpoint, "continuationCommand");
            result.continuation = command == null ? null : string(command, "token");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            collectPlaylistItems(entry.getValue(), result, seenIds, depth + 1);
        }
    }

    private static VideoMetadata playlistEntry(JsonObject renderer) {
        try {
            String id = YouTubeUrlParser.requireVideoId(string(renderer, "videoId"));
            String title = text(object(renderer, "title"));
            String duration = text(object(renderer, "lengthText"));
            long seconds = parseFormattedDuration(duration);
            return title.length() == 0 || seconds <= 0L || seconds > MAX_DURATION_SECONDS ? null
                : new VideoMetadata(id, title, seconds);
        } catch (IOException invalid) {
            return null;
        }
    }

    private static JsonObject context() {
        JsonObject root = new JsonObject();
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "ANDROID");
        client.addProperty("clientVersion", CLIENT_VERSION);
        client.addProperty("userAgent", CLIENT_USER_AGENT);
        client.addProperty("osName", "Android");
        client.addProperty("osVersion", "11");
        client.addProperty("hl", "en");
        client.addProperty("gl", "US");
        context.add("client", client);
        root.add("context", context);
        return root;
    }

    private static JsonObject playerRequest(String videoId) {
        JsonObject request = context();
        request.addProperty("videoId", videoId);
        request.addProperty("contentCheckOk", true);
        request.addProperty("racyCheckOk", true);
        return request;
    }

    private static String parsePlaylistId(String value) throws IOException {
        if (value == null || value.length() == 0 || value.length() > MAX_URL_LENGTH)
            throw new MediaException("Invalid YouTube playlist URL");
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || !YouTubeUrlParser.isYouTubeHost(uri.getHost())) {
                throw new MediaException("URL is not a safe YouTube playlist URL");
            }
            if (queryValue(uri.getRawQuery(), "redirect") != null)
                throw new MediaException("YouTube URL contains an unsafe redirect");
            String id = queryValue(uri.getRawQuery(), "list");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
                throw new MediaException("Invalid YouTube playlist ID");
            return id;
        } catch (MediaException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MediaException("Invalid YouTube playlist URL", exception);
        }
    }

    private static String queryValue(String query, String name) throws IOException {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String key = decode(separator < 0 ? part : part.substring(0, separator));
            if (name.equals(key)) return decode(separator < 0 ? "" : part.substring(separator + 1));
        }
        return null;
    }

    private static String decode(String value) throws IOException {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new MediaException("UTF-8 is unavailable", exception);
        }
    }

    private static boolean isSafeContinuation(String continuation) {
        return continuation != null && continuation.length() > 0
            && continuation.length() <= MAX_CONTINUATION_LENGTH
            && continuation.matches("[A-Za-z0-9._~=-]+");
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString()
            .trim() : "";
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String text(JsonObject object) {
        if (object == null) return "";
        String simple = string(object, "simpleText");
        if (simple.length() > 0) return simple;
        JsonElement runs = object.get("runs");
        if (runs == null || !runs.isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement run : runs.getAsJsonArray())
            if (run.isJsonObject()) text.append(string(run.getAsJsonObject(), "text"));
        return text.toString()
            .trim();
    }

    private static long positiveDuration(String seconds) {
        try {
            long duration = Long.parseLong(seconds);
            return duration > 0L && duration <= MAX_DURATION_SECONDS ? duration : -1L;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static long parseFormattedDuration(String duration) {
        if (duration == null || !duration.matches("[0-9]{1,3}:[0-5][0-9](?::[0-5][0-9])?")) return -1L;
        String[] parts = duration.split(":");
        try {
            long seconds = 0L;
            for (String part : parts) seconds = seconds * 60L + Long.parseLong(part);
            return seconds;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remaining = seconds % 60L;
        return hours > 0L ? hours + ":" + twoDigits(minutes) + ":" + twoDigits(remaining)
            : minutes + ":" + twoDigits(remaining);
    }

    private static String twoDigits(long value) {
        return value < 10L ? "0" + value : String.valueOf(value);
    }

    private static void checkInterrupted() throws MediaException {
        if (Thread.currentThread()
            .isInterrupted()) throw new MediaException("YouTube metadata lookup cancelled");
    }

    private static final class PlaylistItems {

        private final List<VideoMetadata> entries = new ArrayList<VideoMetadata>();
        private final int entryLimit;
        private int rawRendererCount;
        private String continuation;

        private final int rawRendererLimit;

        private PlaylistItems(int entryLimit, int rawRendererLimit) {
            this.entryLimit = entryLimit;
            this.rawRendererLimit = rawRendererLimit;
        }
    }

    private static final class VideoMetadata {

        private final String id;
        private final String title;
        private final long duration;
        private final String durationString;

        private VideoMetadata(String id, String title, long duration) {
            this.id = id;
            this.title = title;
            this.duration = duration;
            this.durationString = formatDuration(duration);
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("title", title);
            json.addProperty("duration", duration);
            json.addProperty("duration_string", durationString);
            json.addProperty("webpage_url", "https://www.youtube.com/watch?v=" + id);
            return json;
        }
    }
}
