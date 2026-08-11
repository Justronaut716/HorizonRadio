package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class YouTubeMetadataResolverTest {

    @Test
    public void producesPlaylistImportCompatibleVideoJsonFromPlayerFixture() throws Exception {
        FixtureHttp http = new FixtureHttp();
        http.enqueue(player("dQw4w9WgXcQ", "Fixture song", "125"));

        String output = new YouTubeMetadataResolver(http).resolveVideoJson("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        JsonObject video = new JsonParser().parse(output).getAsJsonObject();
        assertEquals("dQw4w9WgXcQ", video.get("id").getAsString());
        assertEquals("Fixture song", video.get("title").getAsString());
        assertEquals(125L, video.get("duration").getAsLong());
        assertEquals("2:05", video.get("duration_string").getAsString());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", video.get("webpage_url").getAsString());
        assertEquals(1, http.closedInputs());
        assertTrue(http.lastPostBody.contains("dQw4w9WgXcQ"));
    }

    @Test
    public void returnsNullForUnavailableLiveOrMalformedVideoMetadata() throws Exception {
        FixtureHttp unavailable = new FixtureHttp();
        unavailable.enqueue("{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}}");
        assertNull(new YouTubeMetadataResolver(unavailable).resolveVideoJson("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(1, unavailable.closedInputs());

        FixtureHttp live = new FixtureHttp();
        live.enqueue("{\"videoDetails\":{\"videoId\":\"dQw4w9WgXcQ\",\"title\":\"Live\",\"lengthSeconds\":\"0\",\"isLiveContent\":true}}");
        assertNull(new YouTubeMetadataResolver(live).resolveVideoJson("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(1, live.closedInputs());

        assertNull(new YouTubeMetadataResolver(new FixtureHttp()).resolveVideoJson("https://example.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    public void followsBoundedPlaylistContinuationsDeduplicatesAndKeepsEntryShape() throws Exception {
        FixtureHttp http = new FixtureHttp();
        http.enqueue(playlistPage("PLfixture", new String[][] {
            { "dQw4w9WgXcQ", "First", "1:01" }, { "a234567890_", "Second", "2:02" }
        }, "next-token"));
        http.enqueue(continuationPage(new String[][] {
            { "a234567890_", "Duplicate", "2:02" }, { "b234567890_", "Third", "3:03" }
        }, null));

        String output = new YouTubeMetadataResolver(http).resolvePlaylistJson("https://www.youtube.com/playlist?list=PLfixture");

        JsonArray entries = new JsonParser().parse(output).getAsJsonObject().getAsJsonArray("entries");
        assertEquals(3, entries.size());
        assertEquals("dQw4w9WgXcQ", entries.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("1:01", entries.get(0).getAsJsonObject().get("duration_string").getAsString());
        assertEquals("b234567890_", entries.get(2).getAsJsonObject().get("id").getAsString());
        assertEquals(2, http.closedInputs());
        assertTrue(http.postBodies.get(1).contains("next-token"));
    }

    @Test
    public void stopsPlaylistAtConfiguredEntryCapAndRejectsUnsafeUrls() throws Exception {
        FixtureHttp http = new FixtureHttp();
        String[][] entries = new String[55][];
        for (int index = 0; index < entries.length; index++) {
            entries[index] = new String[] { String.format("a%010d", index), "Song " + index, "1:00" };
        }
        http.enqueue(playlistPage("PLfixture", entries, "would-not-be-requested"));

        String output = new YouTubeMetadataResolver(http).resolvePlaylistJson("https://www.youtube.com/watch?list=PLfixture");

        assertEquals(50, new JsonParser().parse(output).getAsJsonObject().getAsJsonArray("entries").size());
        assertEquals(1, http.postBodies.size());
        assertNull(new YouTubeMetadataResolver(new FixtureHttp()).resolvePlaylistJson("https://evil.example/?list=PLfixture"));
    }

    @Test
    public void continuesPastDuplicateRenderersUntilItFindsLaterDistinctPlaylistEntries() throws Exception {
        FixtureHttp http = new FixtureHttp();
        String[][] entries = new String[52][];
        for (int index = 0; index < 51; index++) {
            entries[index] = new String[] { "dQw4w9WgXcQ", "Duplicate " + index, "1:00" };
        }
        entries[51] = new String[] { "a234567890_", "Distinct after duplicates", "2:00" };
        http.enqueue(playlistPage("PLfixture", entries, null));

        JsonArray output = new JsonParser().parse(
            new YouTubeMetadataResolver(http).resolvePlaylistJson("https://www.youtube.com/playlist?list=PLfixture"))
            .getAsJsonObject().getAsJsonArray("entries");

        assertEquals(2, output.size());
        assertEquals("dQw4w9WgXcQ", output.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("a234567890_", output.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void enforcesTheRawRendererCapAcrossPlaylistContinuationPages() throws Exception {
        FixtureHttp http = new FixtureHttp();
        String[][] firstPage = new String[150][];
        String[][] secondPage = new String[51][];
        for (int index = 0; index < firstPage.length; index++) {
            firstPage[index] = new String[] { "dQw4w9WgXcQ", "First-page duplicate " + index, "1:00" };
        }
        for (int index = 0; index < 50; index++) {
            secondPage[index] = new String[] { "dQw4w9WgXcQ", "Second-page duplicate " + index, "1:00" };
        }
        secondPage[50] = new String[] { "a234567890_", "Past global renderer cap", "2:00" };
        http.enqueue(playlistPage("PLfixture", firstPage, "next-token"));
        http.enqueue(continuationPage(secondPage, null));

        JsonArray output = new JsonParser().parse(
            new YouTubeMetadataResolver(http).resolvePlaylistJson("https://www.youtube.com/playlist?list=PLfixture"))
            .getAsJsonObject().getAsJsonArray("entries");

        assertEquals(1, output.size());
        assertEquals("dQw4w9WgXcQ", output.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(2, http.postBodies.size());
    }

    @Test
    public void emitsOneDurationLinePerValidRequestedIdAndUsesNaForUnavailableOrAbsurdValues() throws Exception {
        FixtureHttp http = new FixtureHttp();
        http.enqueue(player("dQw4w9WgXcQ", "First", "65"));
        http.enqueue("{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\"}}");
        http.enqueue(player("b234567890_", "Absurd", "9999999"));

        String output = new YouTubeMetadataResolver(http).resolveDurationOutput(
            Arrays.asList("dQw4w9WgXcQ", "bad", "a234567890_", "b234567890_"));

        assertEquals("dQw4w9WgXcQ\t1:05\na234567890_\tNA\nb234567890_\tNA", output);
        assertEquals(3, http.closedInputs());
    }

    @Test
    public void rejectsMismatchedDeclaredLengthsAndClosesFixtureResponse() throws Exception {
        FixtureHttp http = new FixtureHttp();
        http.enqueue(player("dQw4w9WgXcQ", "Fixture song", "125"), 1L);

        assertNull(new YouTubeMetadataResolver(http).resolveVideoJson("https://youtu.be/dQw4w9WgXcQ"));

        assertEquals(1, http.closedInputs());
        assertTrue(http.lastInput.closed);
    }

    private static String player(String id, String title, String seconds) {
        return "{\"playabilityStatus\":{\"status\":\"OK\"},\"videoDetails\":{\"videoId\":\"" + id
            + "\",\"title\":\"" + title + "\",\"lengthSeconds\":\"" + seconds + "\",\"isLiveContent\":false}}";
    }

    private static String playlistPage(String playlistId, String[][] entries, String continuation) {
        return "{\"metadata\":{\"playlistMetadataRenderer\":{\"title\":\"Fixture Playlist\"}},\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"content\":{\"sectionListRenderer\":{\"contents\":[{\"itemSectionRenderer\":{\"contents\":[{\"playlistVideoListRenderer\":{\"contents\":"
            + renderers(entries, continuation) + "}}]}}]}}}}]}}}";
    }

    private static String continuationPage(String[][] entries, String continuation) {
        return "{\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":"
            + renderers(entries, continuation) + "}}]}";
    }

    private static String renderers(String[][] entries, String continuation) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < entries.length; index++) {
            if (index > 0) json.append(',');
            json.append("{\"playlistVideoRenderer\":{\"videoId\":\"").append(entries[index][0])
                .append("\",\"title\":{\"runs\":[{\"text\":\"").append(entries[index][1])
                .append("\"}]},\"lengthText\":{\"simpleText\":\"").append(entries[index][2]).append("\"}}}");
        }
        if (continuation != null) {
            if (entries.length > 0) json.append(',');
            json.append("{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"")
                .append(continuation).append("\"}}}}");
        }
        return json.append(']').toString();
    }

    private static final class FixtureHttp implements YouTubeMediaModels.HttpRequester {
        private final Deque<Fixture> responses = new ArrayDeque<Fixture>();
        private final List<String> postBodies = new java.util.ArrayList<String>();
        private String lastPostBody = "";
        private final List<CloseTrackingInputStream> inputs = new java.util.ArrayList<CloseTrackingInputStream>();
        private CloseTrackingInputStream lastInput;

        private void enqueue(String body) { enqueue(body, body.getBytes(StandardCharsets.UTF_8).length); }
        private void enqueue(String body, long declaredLength) { responses.addLast(new Fixture(body, declaredLength)); }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            lastPostBody = new String(body, StandardCharsets.UTF_8);
            postBodies.add(lastPostBody);
            Fixture fixture = responses.removeFirst();
            lastInput = new CloseTrackingInputStream(fixture.body);
            inputs.add(lastInput);
            return new YouTubeMediaModels.HttpResponse(url, 200, "application/json", fixture.declaredLength, lastInput);
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) {
            throw new AssertionError("Metadata resolver must use InnerTube POST fixtures only");
        }

        private int closedInputs() {
            int closed = 0;
            for (CloseTrackingInputStream input : inputs) if (input.closed) closed++;
            return closed;
        }
    }

    private static final class Fixture {
        private final byte[] body;
        private final long declaredLength;
        private Fixture(String body, long declaredLength) { this.body = body.getBytes(StandardCharsets.UTF_8); this.declaredLength = declaredLength; }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private CloseTrackingInputStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws java.io.IOException { closed = true; super.close(); }
    }
}
