package com.horizonradio.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.ChartRegionCatalog;

public class YouTubeServiceTest {

    @Test
    public void buildsCountrySpecificChartRequestBody() {
        JsonObject body = new Gson()
            .fromJson(YouTubeService.buildChartsRequestBody(ChartRegionCatalog.byCode("US")), JsonObject.class);

        assertEquals(
            "FEmusic_analytics_charts_home",
            body.get("browseId")
                .getAsString());
        assertTrue(
            body.get("query")
                .getAsString()
                .contains("chart_params_country_code=us"));
        assertEquals(
            "US",
            body.getAsJsonObject("context")
                .getAsJsonObject("client")
                .get("gl")
                .getAsString());
    }

    @Test
    public void buildsGlobalChartRequestBodyUsingCatalogApiValue() {
        JsonObject body = new Gson()
            .fromJson(YouTubeService.buildChartsRequestBody(ChartRegionCatalog.global()), JsonObject.class);

        assertTrue(
            body.get("query")
                .getAsString()
                .contains(
                    "chart_params_country_code=" + ChartRegionCatalog.global()
                        .getApiCountryCode()));
        assertEquals(
            ChartRegionCatalog.global()
                .getApiCountryCode()
                .toUpperCase(java.util.Locale.ROOT),
            body.getAsJsonObject("context")
                .getAsJsonObject("client")
                .get("gl")
                .getAsString());
    }

    @Test
    public void addsMusicContextToGenericSearchesWithoutDuplicatingIt() {
        assertEquals("funk music", YouTubeService.buildMusicSearchQuery(" funk "));
        assertEquals("lofi remix", YouTubeService.buildMusicSearchQuery("lofi remix"));
        assertEquals("music", YouTubeService.buildMusicSearchQuery(""));
    }

    @Test
    public void sendsMusicFocusedQueryToTheSearchProvider() throws Exception {
        RecordingRequester requester = new RecordingRequester(page(results("song"), ""));

        new YouTubeService(requester).search("funk")
            .get();

        assertEquals(Arrays.asList("funk music"), requester.queries());
    }

    @Test
    public void genericParserKeepsGermanCompatibilityWrapper() throws IOException {
        String json = readResource("/com/horizonradio/server/youtube-search-response.json");

        assertEquals(
            videoIds(YouTubeService.parseTopCharts(json)),
            videoIds(YouTubeService.parseGermanTopCharts(json)));
    }

    @Test
    public void parsesVideosAndContinuationFromInitialSearchPage() throws IOException {
        String json = readResource("/com/horizonradio/server/youtube-search-initial-with-continuation.json");

        YouTubeService.SearchPage page = YouTubeService.parseSearchPage(json);

        assertEquals(Arrays.asList("video-1", "video-2"), videoIds(page.getResults()));
        assertEquals("token-page-2", page.getContinuation());
        assertEquals(
            "2:30",
            page.getResults()
                .get(0)
                .getDuration());
    }

    @Test
    public void parsesContinuationItemsAndIgnoresPlaylistItems() throws IOException {
        String json = readResource("/com/horizonradio/server/youtube-search-continuation.json");

        YouTubeService.SearchPage page = YouTubeService.parseSearchPage(json);

        assertEquals(Arrays.asList("video-3", "video-4"), videoIds(page.getResults()));
        assertEquals("token-page-3", page.getContinuation());
    }

    @Test
    public void parseResultsKeepsTheExistingInitialPageResultContract() throws IOException {
        String json = readResource("/com/horizonradio/server/youtube-search-initial-with-continuation.json");

        assertEquals(
            videoIds(
                YouTubeService.parseSearchPage(json)
                    .getResults()),
            videoIds(YouTubeService.parseResults(json)));
    }

    @Test
    public void searchFollowsContinuationsDeduplicatesAndStopsAtThreePages() throws Exception {
        RecordingRequester requester = new RecordingRequester(
            page(results("duplicate", "valid-1"), "page-2"),
            page(results("duplicate", "valid-2"), "page-3"),
            page(results("valid-3"), "page-4"));

        List<SearchResult> results = new YouTubeService(requester).search("funk")
            .get();

        assertEquals(Arrays.<String>asList(null, "page-2", "page-3"), requester.continuations());
        assertEquals(Arrays.asList("duplicate", "valid-1", "valid-2", "valid-3"), videoIds(results));
    }

    @Test
    public void searchCapsRawCandidatesAtOneHundredFifty() throws Exception {
        RecordingRequester requester = new RecordingRequester(
            page(results("page-one", "page-one-extra"), "page-2"),
            page(manyResults("page-two-", 50), "page-3"),
            page(manyResults("page-three-", 100), "page-4"));

        List<SearchResult> results = new YouTubeService(requester).search("funk")
            .get();

        assertEquals(150, results.size());
        assertEquals(
            3,
            requester.continuations()
                .size());
        assertEquals(
            "page-3",
            requester.continuations()
                .get(2));
    }

    @Test
    public void durationAwareSearchStopsAfterTenPlayableResults() throws Exception {
        RecordingRequester requester = new RecordingRequester(
            page(manyResults("song-", 10), "page-2"),
            page(results("late-song"), "page-3"));

        List<SearchResult> results = new YouTubeService(requester).search("funk", 15L * 60L * 1000L)
            .get();

        assertEquals(10, results.size());
        assertEquals(
            1,
            requester.continuations()
                .size());
    }

    @Test
    public void durationAwareSearchContinuesWhenFilteringLeavesFewerThanTenSongs() throws Exception {
        List<SearchResult> firstPage = new ArrayList<SearchResult>();
        firstPage.add(new SearchResult("podcast", "Daily Podcast", "channel", "2:30", ""));
        firstPage.addAll(manyResults("song-", 9));
        RecordingRequester requester = new RecordingRequester(
            page(firstPage, "page-2"),
            page(results("late-song"), "page-3"));

        List<SearchResult> results = new YouTubeService(requester).search("funk", 15L * 60L * 1000L)
            .get();

        assertEquals(10, results.size());
        assertEquals(
            2,
            requester.continuations()
                .size());
    }

    @Test
    public void continuationFailureReturnsCandidatesFromEarlierPages() throws Exception {
        RecordingRequester requester = new RecordingRequester(
            page(results("valid-1"), "page-2"),
            new IOException("page unavailable"));

        List<SearchResult> results = new YouTubeService(requester).search("funk")
            .get();

        assertEquals(Arrays.asList("valid-1"), videoIds(results));
        assertEquals(Arrays.<String>asList(null, "page-2"), requester.continuations());
    }

    @Test
    public void extractsContinuationAfterTheFiftiethVideo() {
        JsonArray items = new JsonArray();
        for (int index = 0; index < 50; index++) {
            JsonObject video = new JsonObject();
            video.addProperty("videoId", "video-" + index);
            video.addProperty("title", "Title");
            JsonObject renderer = new JsonObject();
            renderer.add("videoId", video.get("videoId"));
            renderer.add("title", new JsonObject());
            renderer.getAsJsonObject("title")
                .addProperty("simpleText", "Title");
            renderer.add("lengthText", new JsonObject());
            renderer.getAsJsonObject("lengthText")
                .addProperty("simpleText", "2:00");
            JsonObject item = new JsonObject();
            item.add("videoRenderer", renderer);
            items.add(item);
        }
        JsonObject continuationCommand = new JsonObject();
        continuationCommand.addProperty("token", "after-fifty");
        JsonObject continuationEndpoint = new JsonObject();
        continuationEndpoint.add("continuationCommand", continuationCommand);
        JsonObject continuationRenderer = new JsonObject();
        continuationRenderer.add("continuationEndpoint", continuationEndpoint);
        JsonObject continuationItem = new JsonObject();
        continuationItem.add("continuationItemRenderer", continuationRenderer);
        items.add(continuationItem);

        JsonObject itemSection = new JsonObject();
        itemSection.add("contents", items);
        JsonArray sections = new JsonArray();
        JsonObject itemSectionWrapper = new JsonObject();
        itemSectionWrapper.add("itemSectionRenderer", itemSection);
        sections.add(itemSectionWrapper);
        JsonObject sectionList = new JsonObject();
        sectionList.add("contents", sections);
        JsonObject primaryContents = new JsonObject();
        primaryContents.add("sectionListRenderer", sectionList);
        JsonObject twoColumn = new JsonObject();
        twoColumn.add("primaryContents", primaryContents);
        JsonObject contents = new JsonObject();
        contents.add("twoColumnSearchResultsRenderer", twoColumn);
        JsonObject root = new JsonObject();
        root.add("contents", contents);

        YouTubeService.SearchPage page = YouTubeService.parseSearchPage(root.toString());

        assertEquals(
            50,
            page.getResults()
                .size());
        assertEquals("after-fifty", page.getContinuation());
    }

    private static String readResource(String path) throws IOException {
        InputStream stream = YouTubeServiceTest.class.getResourceAsStream(path);
        assertNotNull(stream);
        Reader reader = new InputStreamReader(stream, Charset.forName("UTF-8"));
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[1024];
        int count;
        try {
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
            }
        } finally {
            reader.close();
        }
        return result.toString();
    }

    private static List<String> videoIds(List<SearchResult> values) {
        List<String> ids = new ArrayList<String>();
        for (SearchResult value : values) {
            ids.add(value.getVideoId());
        }
        return ids;
    }

    private static YouTubeService.SearchPage page(List<SearchResult> results, String continuation) {
        return new YouTubeService.SearchPage(results, continuation);
    }

    private static List<SearchResult> results(String... ids) {
        List<SearchResult> values = new ArrayList<SearchResult>();
        for (String id : ids) {
            values.add(new SearchResult(id, id, "channel", "2:30", ""));
        }
        return values;
    }

    private static List<SearchResult> manyResults(String prefix, int count) {
        List<SearchResult> values = new ArrayList<SearchResult>();
        for (int index = 0; index < count; index++) {
            values.add(new SearchResult(prefix + index, prefix + index, "channel", "2:30", ""));
        }
        return values;
    }

    private static final class RecordingRequester implements YouTubeService.SearchPageRequester {

        private final List<Object> responses;
        private final List<String> queries = new ArrayList<String>();
        private final List<String> continuations = new ArrayList<String>();

        private RecordingRequester(Object... responses) {
            this.responses = Arrays.asList(responses);
        }

        @Override
        public YouTubeService.SearchPage request(String query, String continuation) throws IOException {
            queries.add(query);
            continuations.add(continuation);
            Object response = responses.get(continuations.size() - 1);
            if (response instanceof IOException) {
                throw (IOException) response;
            }
            return (YouTubeService.SearchPage) response;
        }

        private List<String> continuations() {
            return continuations;
        }

        private List<String> queries() {
            return queries;
        }
    }
}
