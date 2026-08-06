package com.horizonradio.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.horizonradio.core.model.SearchResult;

/**
 * Performs YouTube search using YouTube's internal InnerTube API.
 * Replaces the companion service's youtube-search-api npm dependency.
 */
public class YouTubeService {

    private static final String INNERTUBE_URL = "https://www.youtube.com/youtubei/v1/search";
    private static final String CLIENT_VERSION = "2.20231219.04.00";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";
    private static final String CHARTS_URL = "https://charts.youtube.com/youtubei/v1/browse?alt=json";
    private static final String CHARTS_BROWSE_ID = "FEmusic_analytics_charts_home";
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    private static final int READ_TIMEOUT_MILLIS = 15000;
    // Fetch extra candidates because the server filters out videos at or above
    // the configured duration limit before sending results to the client.
    private static final int MAX_RESULTS = 50;

    private static final Logger LOGGER = Logger.getLogger(YouTubeService.class.getName());

    private final Gson gson = new Gson();

    public CompletableFuture<List<SearchResult>> search(final String query) {
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<SearchResult>>() {

            @Override
            public List<SearchResult> get() {
                try {
                    return request(query);
                } catch (Exception exception) {
                    LOGGER.log(Level.WARNING, "YouTube search failed for query: " + query, exception);
                    return new ArrayList<SearchResult>();
                }
            }
        });
    }

    public CompletableFuture<List<SearchResult>> fetchGermanTopCharts() {
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<SearchResult>>() {

            @Override
            public List<SearchResult> get() {
                try {
                    return requestGermanTopCharts();
                } catch (Exception exception) {
                    LOGGER.log(Level.WARNING, "YouTube German charts request failed", exception);
                    return new ArrayList<SearchResult>();
                }
            }
        });
    }

    private List<SearchResult> request(String query) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(INNERTUBE_URL)
                .toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);

            String requestBody = buildRequestBody(query);
            try (OutputStream output = connection.getOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(requestBody);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= 300) {
                closeQuietly(connection.getErrorStream());
                return new ArrayList<SearchResult>();
            }

            try (InputStream input = connection.getInputStream();
                Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(reader)) {
                StringBuilder responseBody = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = bufferedReader.read(buffer)) != -1) {
                    responseBody.append(buffer, 0, count);
                }
                return parseResults(responseBody.toString());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<SearchResult> requestGermanTopCharts() throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(CHARTS_URL)
                .toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);

            try (OutputStream output = connection.getOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(buildChartsRequestBody());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= 300) {
                closeQuietly(connection.getErrorStream());
                return new ArrayList<SearchResult>();
            }

            try (InputStream input = connection.getInputStream();
                Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(reader)) {
                StringBuilder responseBody = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = bufferedReader.read(buffer)) != -1) {
                    responseBody.append(buffer, 0, count);
                }
                return parseGermanTopCharts(responseBody.toString());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildChartsRequestBody() {
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB_MUSIC_ANALYTICS");
        client.addProperty("clientVersion", "2.0");
        client.addProperty("hl", "en");
        client.addProperty("gl", "DE");
        context.add("client", client);

        JsonObject body = new JsonObject();
        body.add("context", context);
        body.addProperty("browseId", CHARTS_BROWSE_ID);
        body.addProperty(
            "query",
            "perspective=CHART_DETAILS&chart_params_country_code=de"
                + "&chart_params_chart_type=TRACKS&chart_params_period_type=WEEKLY");
        return gson.toJson(body);
    }

    public static List<SearchResult> parseGermanTopCharts(String responseBody) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        Set<String> seenIds = new HashSet<String>();
        try {
            JsonObject root = new Gson().fromJson(responseBody, JsonObject.class);
            JsonArray sections = root.getAsJsonObject("contents")
                .getAsJsonObject("sectionListRenderer")
                .getAsJsonArray("contents");
            for (JsonElement section : sections) {
                JsonObject sectionObject = section.getAsJsonObject();
                JsonObject sectionRenderer = getObject(sectionObject, "musicAnalyticsSectionRenderer");
                JsonObject content = getObject(sectionRenderer, "content");
                JsonArray trackTypes = content == null ? null : content.getAsJsonArray("trackTypes");
                if (trackTypes == null) {
                    continue;
                }
                for (JsonElement trackTypeElement : trackTypes) {
                    JsonObject trackType = trackTypeElement.getAsJsonObject();
                    if (!"TOP_VIEWS_CHART".equals(getString(trackType, "listType"))
                        || !"CHART_PERIOD_TYPE_WEEKLY".equals(getString(trackType, "chartPeriodType"))) {
                        continue;
                    }
                    JsonArray trackViews = trackType.getAsJsonArray("trackViews");
                    if (trackViews == null) {
                        continue;
                    }
                    for (JsonElement trackElement : trackViews) {
                        if (results.size() >= 50 || !trackElement.isJsonObject()) {
                            break;
                        }
                        JsonObject track = trackElement.getAsJsonObject();
                        JsonObject metadata = getObject(track, "chartEntryMetadata");
                        int rank = metadata == null ? results.size() + 1 : getInt(metadata, "currentPosition");
                        String videoId = getString(track, "encryptedVideoId");
                        String title = getString(track, "name");
                        if (rank < 1 || rank > 50
                            || videoId.length() == 0
                            || title.length() == 0
                            || !seenIds.add(videoId)) {
                            continue;
                        }
                        results
                            .add(new SearchResult(videoId, title, extractArtists(track), "", extractThumbnail(track)));
                    }
                    return results;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Failed to parse YouTube German charts response", exception);
        }
        return results;
    }

    private static String extractArtists(JsonObject track) {
        JsonArray artists = track.getAsJsonArray("artists");
        if (artists == null) {
            return "";
        }
        List<String> names = new ArrayList<String>();
        for (JsonElement artist : artists) {
            if (artist.isJsonObject()) {
                String name = getString(artist.getAsJsonObject(), "name");
                if (name.length() > 0) {
                    names.add(name);
                }
            }
        }
        return join(names, " & ");
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }

    private String buildRequestBody(String query) {
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", CLIENT_VERSION);
        context.add("client", client);

        JsonObject body = new JsonObject();
        body.add("context", context);
        body.addProperty("query", query == null ? "" : query);
        return gson.toJson(body);
    }

    public static List<SearchResult> parseResults(String responseBody) {
        Gson parserGson = new Gson();
        try {
            JsonObject root = parserGson.fromJson(responseBody, JsonObject.class);
            JsonArray sections = root.getAsJsonObject("contents")
                .getAsJsonObject("twoColumnSearchResultsRenderer")
                .getAsJsonObject("primaryContents")
                .getAsJsonObject("sectionListRenderer")
                .getAsJsonArray("contents");

            List<SearchResult> results = new ArrayList<SearchResult>();
            for (JsonElement section : sections) {
                JsonObject sectionObject = section.getAsJsonObject();
                if (!sectionObject.has("itemSectionRenderer")) {
                    continue;
                }

                JsonArray items = sectionObject.getAsJsonObject("itemSectionRenderer")
                    .getAsJsonArray("contents");
                for (JsonElement item : items) {
                    JsonObject itemObject = item.getAsJsonObject();
                    if (!itemObject.has("videoRenderer")) {
                        continue;
                    }

                    JsonObject video = itemObject.getAsJsonObject("videoRenderer");
                    String videoId = getString(video, "videoId");
                    if (videoId.length() == 0) {
                        continue;
                    }

                    String title = extractText(getObject(video, "title"));
                    JsonObject channelObject = video.has("ownerText") ? getObject(video, "ownerText")
                        : getObject(video, "longBylineText");
                    String channel = extractText(channelObject);
                    String duration = extractText(getObject(video, "lengthText"));
                    String thumbnail = extractThumbnail(video);

                    results.add(new SearchResult(videoId, title, channel, duration, thumbnail));
                    if (results.size() >= MAX_RESULTS) {
                        return results;
                    }
                }
            }
            return results;
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Failed to parse YouTube search response", exception);
            return new ArrayList<SearchResult>();
        }
    }

    private static JsonObject getObject(JsonObject object, String memberName) {
        if (object == null) {
            return null;
        }
        JsonElement member = object.get(memberName);
        return member != null && member.isJsonObject() ? member.getAsJsonObject() : null;
    }

    private static int getInt(JsonObject object, String memberName) {
        try {
            JsonElement member = object.get(memberName);
            return member == null ? 0 : member.getAsInt();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String getString(JsonObject object, String memberName) {
        JsonElement member = object.get(memberName);
        return member != null && !member.isJsonNull() ? member.getAsString() : "";
    }

    private static String extractText(JsonObject textObject) {
        if (textObject == null) {
            return "";
        }
        if (textObject.has("simpleText")) {
            return getString(textObject, "simpleText");
        }
        if (textObject.has("runs")) {
            JsonArray runs = textObject.getAsJsonArray("runs");
            StringBuilder text = new StringBuilder();
            for (JsonElement run : runs) {
                if (run.isJsonObject()) {
                    text.append(getString(run.getAsJsonObject(), "text"));
                }
            }
            return text.toString();
        }
        return "";
    }

    private static String extractThumbnail(JsonObject video) {
        JsonObject thumbnail = getObject(video, "thumbnail");
        if (thumbnail == null || !thumbnail.has("thumbnails")) {
            return "";
        }
        JsonArray thumbnails = thumbnail.getAsJsonArray("thumbnails");
        if (thumbnails.size() == 0) {
            return "";
        }
        JsonElement lastThumbnail = thumbnails.get(thumbnails.size() - 1);
        return lastThumbnail.isJsonObject() ? getString(lastThumbnail.getAsJsonObject(), "url") : "";
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // The original HTTP failure is the useful result here.
        }
    }
}
