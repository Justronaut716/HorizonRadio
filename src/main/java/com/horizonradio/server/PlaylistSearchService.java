package com.horizonradio.server;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.horizonradio.core.model.PlaylistSearchResult;
import com.horizonradio.media.net.BoundedResponseReader;
import com.horizonradio.server.media.YouTubeMediaModels;

/** Playlist-only discovery supporting both classic and current YouTube result formats. */
public final class PlaylistSearchService {

    public List<PlaylistSearchResult> search(String query) throws IOException {
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", "2.20231219.04.00");
        JsonObject context = new JsonObject();
        context.add("client", client);
        JsonObject body = new JsonObject();
        body.add("context", context);
        body.addProperty("query", query);
        body.addProperty("params", "EgIQAw%3D%3D");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "Mozilla/5.0");
        try (YouTubeMediaModels.HttpResponse response = new YouTubeMediaModels.UrlConnectionHttpRequester().post(
            new URL("https://www.youtube.com/youtubei/v1/search"),
            headers,
            body.toString()
                .getBytes(StandardCharsets.UTF_8),
            15000,
            8L * 1024 * 1024,
            YouTubeMediaModels.RedirectPolicy.INNER_TUBE)) {
            return parse(
                BoundedResponseReader
                    .readUtf8(response.getInputStream(), response.getContentLength(), 8 * 1024 * 1024));
        }
    }

    public static List<PlaylistSearchResult> parse(String json) {
        List<PlaylistSearchResult> results = new ArrayList<PlaylistSearchResult>();
        collect(new JsonParser().parse(json), results, new HashSet<String>(), 0);
        return results;
    }

    private static void collect(JsonElement value, List<PlaylistSearchResult> results, Set<String> seen, int depth) {
        if (value == null || depth > 64 || results.size() >= 20) return;
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) collect(item, results, seen, depth + 1);
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonObject legacy = object(object, "playlistRenderer");
            JsonObject modern = object(object, "lockupViewModel");
            if (legacy != null) {
                add(
                    results,
                    seen,
                    string(legacy, "playlistId"),
                    text(legacy.get("title")),
                    text(legacy.get("shortBylineText")),
                    string(legacy, "videoCount"));
                return;
            }
            if (modern != null && "LOCKUP_CONTENT_TYPE_PLAYLIST".equals(string(modern, "contentType"))) {
                JsonObject metadata = object(object(modern, "metadata"), "lockupMetadataViewModel");
                add(
                    results,
                    seen,
                    string(modern, "contentId"),
                    text(metadata == null ? null : metadata.get("title")),
                    firstText(modern, "metadataParts"),
                    firstText(modern, "thumbnailBadgeViewModel"));
                return;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
                collect(entry.getValue(), results, seen, depth + 1);
        }
    }

    private static void add(List<PlaylistSearchResult> results, Set<String> seen, String id, String title,
        String author, String count) {
        if (id.matches("[A-Za-z0-9_-]{1,128}") && !title.isEmpty() && seen.add(id))
            results.add(new PlaylistSearchResult(id, title, author, count));
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key)
            && object.get(key)
                .isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key)
            && object.get(key)
                .isJsonPrimitive() ? object.get(key)
                    .getAsString() : "";
    }

    private static String text(JsonElement element) {
        if (element == null) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (!element.isJsonObject()) return "";
        JsonObject object = element.getAsJsonObject();
        if (object.has("content")) return string(object, "content");
        if (object.has("simpleText")) return string(object, "simpleText");
        StringBuilder result = new StringBuilder();
        if (object.has("runs") && object.get("runs")
            .isJsonArray())
            for (JsonElement run : object.getAsJsonArray("runs"))
                if (run.isJsonObject()) result.append(string(run.getAsJsonObject(), "text"));
        return result.toString();
    }

    private static String firstText(JsonElement value, String key) {
        if (value == null) return "";
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                String found = firstText(item, key);
                if (!found.isEmpty()) return found;
            }
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has(key)) {
                JsonElement target = object.get(key);
                if (target.isJsonArray() && target.getAsJsonArray()
                    .size() > 0)
                    target = target.getAsJsonArray()
                        .get(0);
                if (target.isJsonObject()) return text(
                    target.getAsJsonObject()
                        .get("text"));
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String found = firstText(entry.getValue(), key);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }
}
