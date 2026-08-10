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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.horizonradio.core.model.DurationParser;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegion;
import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.core.server.MusicSearchFilter;

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
    private static final int TARGET_SEARCH_RESULTS = 10;
    private static final int MAX_SEARCH_PAGES = 3;
    private static final int MAX_RAW_SEARCH_RESULTS = 150;

    private static final Logger LOGGER = Logger.getLogger(YouTubeService.class.getName());

    private final Gson gson = new Gson();
    private final SearchPageRequester searchPageRequester;

    public YouTubeService() {
        searchPageRequester = null;
    }

    YouTubeService(SearchPageRequester searchPageRequester) {
        if (searchPageRequester == null) {
            throw new IllegalArgumentException("searchPageRequester must not be null");
        }
        this.searchPageRequester = searchPageRequester;
    }

    public CompletableFuture<List<SearchResult>> search(final String query) {
        return search(query, -1L);
    }

    public CompletableFuture<List<SearchResult>> search(final String query, final long maxTrackDurationMs) {
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<SearchResult>>() {

            @Override
            public List<SearchResult> get() {
                return searchPages(query, maxTrackDurationMs);
            }
        });
    }

    public CompletableFuture<List<SearchResult>> fetchGermanTopCharts() {
        return fetchTopCharts(ChartRegionCatalog.byCode("DE"));
    }

    public CompletableFuture<List<SearchResult>> fetchTopCharts(final ChartRegion region) {
        if (region == null) {
            throw new IllegalArgumentException("chart region must not be null");
        }
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<SearchResult>>() {

            @Override
            public List<SearchResult> get() {
                try {
                    return requestTopCharts(region);
                } catch (Exception exception) {
                    LOGGER.log(Level.WARNING, "YouTube " + region.getCode() + " charts request failed", exception);
                    return new ArrayList<SearchResult>();
                }
            }
        });
    }

    private List<SearchResult> searchPages(String query, long maxTrackDurationMs) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        Set<String> seenIds = new HashSet<String>();
        String continuation = null;
        String musicQuery = buildMusicSearchQuery(query);

        for (int page = 0; page < MAX_SEARCH_PAGES; page++) {
            SearchPage searchPage;
            try {
                searchPage = requestPage(musicQuery, continuation);
            } catch (Exception exception) {
                if (page == 0) {
                    LOGGER.log(Level.WARNING, "YouTube search failed for query: " + query, exception);
                    return new ArrayList<SearchResult>();
                }
                LOGGER.log(Level.WARNING, "YouTube search continuation failed for query: " + query, exception);
                return results;
            }
            if (searchPage == null) {
                return results;
            }

            for (SearchResult result : searchPage.getResults()) {
                if (result == null || result.getVideoId() == null
                    || result.getVideoId()
                        .length() == 0
                    || !seenIds.add(result.getVideoId())) {
                    continue;
                }
                if (maxTrackDurationMs > 0L && !isPlayableMusicResult(result, maxTrackDurationMs)) {
                    continue;
                }
                results.add(result);
                if (maxTrackDurationMs > 0L && results.size() >= TARGET_SEARCH_RESULTS) {
                    return results;
                }
                if (maxTrackDurationMs <= 0L && results.size() >= MAX_RAW_SEARCH_RESULTS) {
                    return results;
                }
            }

            continuation = searchPage.getContinuation();
            if (continuation == null || continuation.length() == 0) {
                return results;
            }
        }
        return results;
    }

    private static boolean isPlayableMusicResult(SearchResult result, long maxTrackDurationMs) {
        long durationMs = DurationParser.parseMillisStrict(result.getDuration());
        return MusicSearchFilter.isLikelyMusic(result) && durationMs >= 0L && durationMs < maxTrackDurationMs;
    }

    static String buildMusicSearchQuery(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() == 0) {
            return "music";
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim();
        String[] musicMarkers = { "music", "song", "songs", "audio", "remix", "remixes", "mix", "cover", "lyrics",
            "lyric", "soundtrack", "ost", "acoustic", "karaoke", "live", "concert", "dj", "band", "artist", "track",
            "tracks", "lied", "lieder", "musik" };
        String padded = " " + normalized + " ";
        for (String marker : musicMarkers) {
            if (padded.contains(" " + marker + " ")) {
                return trimmed;
            }
        }
        return trimmed + " music";
    }

    private SearchPage requestPage(String query, String continuation) throws IOException {
        if (searchPageRequester != null) {
            return searchPageRequester.request(query, continuation);
        }
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

            String requestBody = buildRequestBody(query, continuation);
            try (OutputStream output = connection.getOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(requestBody);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= 300) {
                closeQuietly(connection.getErrorStream());
                return new SearchPage(new ArrayList<SearchResult>(), "");
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
                return parseSearchPage(responseBody.toString());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<SearchResult> requestTopCharts(ChartRegion region) throws IOException {
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
                writer.write(buildChartsRequestBody(region));
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
                return parseTopCharts(responseBody.toString());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String buildChartsRequestBody(ChartRegion region) {
        if (region == null) {
            throw new IllegalArgumentException("chart region must not be null");
        }
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB_MUSIC_ANALYTICS");
        client.addProperty("clientVersion", "2.0");
        client.addProperty("hl", "en");
        client.addProperty(
            "gl",
            region.getApiCountryCode()
                .toUpperCase(java.util.Locale.ROOT));
        context.add("client", client);

        JsonObject body = new JsonObject();
        body.add("context", context);
        body.addProperty("browseId", CHARTS_BROWSE_ID);
        body.addProperty(
            "query",
            "perspective=CHART_DETAILS&chart_params_country_code=" + region.getApiCountryCode()
                + "&chart_params_chart_type=TRACKS&chart_params_period_type=WEEKLY");
        return new Gson().toJson(body);
    }

    public static List<SearchResult> parseTopCharts(String responseBody) {
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
            LOGGER.log(Level.WARNING, "Failed to parse YouTube charts response", exception);
        }
        return results;
    }

    public static List<SearchResult> parseGermanTopCharts(String responseBody) {
        return parseTopCharts(responseBody);
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

    private String buildRequestBody(String query, String continuation) {
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", CLIENT_VERSION);
        context.add("client", client);

        JsonObject body = new JsonObject();
        body.add("context", context);
        if (continuation == null || continuation.length() == 0) {
            body.addProperty("query", query == null ? "" : query);
        } else {
            body.addProperty("continuation", continuation);
        }
        return gson.toJson(body);
    }

    public static SearchPage parseSearchPage(String responseBody) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        Set<String> seenIds = new HashSet<String>();
        String[] continuation = new String[] { "" };
        try {
            JsonObject root = new Gson().fromJson(responseBody, JsonObject.class);
            JsonObject contents = getObject(root, "contents");
            JsonObject twoColumn = getObject(contents, "twoColumnSearchResultsRenderer");
            JsonObject primaryContents = getObject(twoColumn, "primaryContents");
            JsonObject sectionList = getObject(primaryContents, "sectionListRenderer");
            parseItems(getArray(sectionList, "contents"), results, seenIds, continuation);

            JsonArray commands = getArray(root, "onResponseReceivedCommands");
            if (commands != null) {
                for (JsonElement commandElement : commands) {
                    if (commandElement == null || !commandElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject command = commandElement.getAsJsonObject();
                    JsonObject appendAction = getObject(command, "appendContinuationItemsAction");
                    parseItems(getArray(appendAction, "continuationItems"), results, seenIds, continuation);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Failed to parse YouTube search response", exception);
            return new SearchPage(new ArrayList<SearchResult>(), "");
        }
        return new SearchPage(results, continuation[0]);
    }

    public static List<SearchResult> parseResults(String responseBody) {
        return parseSearchPage(responseBody).getResults();
    }

    private static void parseItems(JsonArray items, List<SearchResult> results, Set<String> seenIds,
        String[] continuation) {
        if (items == null) {
            return;
        }
        for (JsonElement itemElement : items) {
            if (itemElement == null || !itemElement.isJsonObject()) {
                continue;
            }
            JsonObject item = itemElement.getAsJsonObject();
            JsonObject video = getObject(item, "videoRenderer");
            if (video != null && results.size() < MAX_RESULTS) {
                addVideo(video, results, seenIds);
            }
            JsonObject itemSection = getObject(item, "itemSectionRenderer");
            if (itemSection != null) {
                parseItems(getArray(itemSection, "contents"), results, seenIds, continuation);
            }
            if (continuation[0].length() == 0) {
                String token = extractContinuation(item);
                if (token.length() > 0) {
                    continuation[0] = token;
                }
            }
        }
    }

    private static void addVideo(JsonObject video, List<SearchResult> results, Set<String> seenIds) {
        String videoId = getString(video, "videoId");
        if (videoId.length() == 0 || !seenIds.add(videoId)) {
            return;
        }

        String title = extractText(getObject(video, "title"));
        JsonObject channelObject = video.has("ownerText") ? getObject(video, "ownerText")
            : getObject(video, "longBylineText");
        String channel = extractText(channelObject);
        String duration = extractText(getObject(video, "lengthText"));
        String thumbnail = extractThumbnail(video);

        results.add(new SearchResult(videoId, title, channel, duration, thumbnail));
    }

    private static String extractContinuation(JsonObject item) {
        JsonObject renderer = getObject(item, "continuationItemRenderer");
        JsonObject endpoint = getObject(renderer, "continuationEndpoint");
        JsonObject command = getObject(endpoint, "continuationCommand");
        return getString(command, "token");
    }

    private static JsonObject getObject(JsonObject object, String memberName) {
        if (object == null) {
            return null;
        }
        JsonElement member = object.get(memberName);
        return member != null && member.isJsonObject() ? member.getAsJsonObject() : null;
    }

    private static JsonArray getArray(JsonObject object, String memberName) {
        if (object == null) {
            return null;
        }
        JsonElement member = object.get(memberName);
        return member != null && member.isJsonArray() ? member.getAsJsonArray() : null;
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
        if (object == null) {
            return "";
        }
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

    static final class SearchPage {

        private final List<SearchResult> results;
        private final String continuation;

        SearchPage(List<SearchResult> results, String continuation) {
            if (results == null) {
                throw new IllegalArgumentException("results must not be null");
            }
            this.results = new ArrayList<SearchResult>(results);
            this.continuation = continuation == null ? "" : continuation;
        }

        List<SearchResult> getResults() {
            return new ArrayList<SearchResult>(results);
        }

        String getContinuation() {
            return continuation;
        }
    }

    interface SearchPageRequester {

        SearchPage request(String query, String continuation) throws IOException;
    }
}
