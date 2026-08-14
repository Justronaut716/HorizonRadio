package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class YouTubeStreamResolverTest {

    @Test
    public void selectsWebmOpusBeforeM4aAndRejectsVideoOnlyFormats() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":["
                + "{\"mimeType\":\"video/mp4; codecs=\\\"avc1.4d401f\\\"\",\"bitrate\":999999,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"},"
                + "{\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"bitrate\":256000,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"},"
                + "{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,\"url\":\"https://r2.googlevideo.com/videoplayback?expire=2000\"}]}}");
        YouTubeStreamResolver resolver = new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L);

        YouTubeMediaModels.ResolvedAudioStream stream = resolver.resolveAudio("dQw4w9WgXcQ");

        assertEquals(MediaFormat.WEBM_OPUS, stream.getFormat());
        assertEquals(
            "r1.googlevideo.com",
            stream.getUrl()
                .getHost());
        assertTrue(http.requestBody.contains("dQw4w9WgXcQ"));
        assertTrue(http.requestBody.contains("ANDROID"));
    }

    @Test
    public void prefersWebmOpusOverFragmentedM4aForTheStandaloneDecoder() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":["
                + "{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,"
                + "\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"},"
                + "{\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"bitrate\":96000,"
                + "\"url\":\"https://r2.googlevideo.com/videoplayback?expire=2000\"}]}}");

        YouTubeMediaModels.ResolvedAudioStream stream = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        assertEquals(MediaFormat.WEBM_OPUS, stream.getFormat());
        assertEquals(
            "r2.googlevideo.com",
            stream.getUrl()
                .getHost());
    }

    @Test
    public void exposesOrderedPrimaryCandidatesAndLazilyResolvesTheIosFallback() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":["
                + "{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,"
                + "\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"},"
                + "{\"mimeType\":\"audio/aac\",\"bitrate\":96000,"
                + "\"url\":\"https://r2.googlevideo.com/videoplayback?expire=2000\"}]}}");
        http.iosResponse = "{\"streamingData\":{\"adaptiveFormats\":["
            + "{\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"bitrate\":160000,"
            + "\"url\":\"https://r3.googlevideo.com/videoplayback?expire=2000\"}]}}";
        YouTubeStreamResolver resolver = new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L);

        YouTubeStreamResolver.ResolvedAudioCandidates resolved = resolver.resolveAudioCandidates("dQw4w9WgXcQ");
        List<YouTubeMediaModels.ResolvedAudioStream> primary = resolved.getPrimaryCandidates();

        assertEquals(2, primary.size());
        assertEquals(MediaFormat.M4A, primary.get(0).getFormat());
        assertEquals(MediaFormat.AAC, primary.get(1).getFormat());
        assertEquals(1, http.playerRequests);

        List<YouTubeMediaModels.ResolvedAudioStream> alternative = resolved.resolveAlternativeCandidates();

        assertEquals(1, alternative.size());
        assertEquals(MediaFormat.WEBM_OPUS, alternative.get(0).getFormat());
        assertEquals(2, http.playerRequests);
        assertEquals(1, resolved.resolveAlternativeCandidates().size());
        assertEquals(2, http.playerRequests);
    }

    @Test
    public void sendsCompleteAndroidPlayerContextAndClientHeaders() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":["
                + "{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,"
                + "\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"}]}}");

        new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        JsonObject request = new Gson().fromJson(http.requestBody, JsonObject.class);
        JsonObject client = request.getAsJsonObject("context")
            .getAsJsonObject("client");
        assertEquals(
            "ANDROID_VR",
            client.get("clientName")
                .getAsString());
        assertEquals(
            "1.65.10",
            client.get("clientVersion")
                .getAsString());
        assertEquals(
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            client.get("userAgent")
                .getAsString());
        assertEquals(
            "Oculus",
            client.get("deviceMake")
                .getAsString());
        assertEquals(
            "Quest 3",
            client.get("deviceModel")
                .getAsString());
        assertEquals(
            32,
            client.get("androidSdkVersion")
                .getAsInt());
        assertEquals(
            "Android",
            client.get("osName")
                .getAsString());
        assertEquals(
            "12L",
            client.get("osVersion")
                .getAsString());
        assertEquals(
            "en",
            client.get("hl")
                .getAsString());
        assertEquals(
            "HTML5_PREF_WANTS",
            request.getAsJsonObject("playbackContext")
                .getAsJsonObject("contentPlaybackContext")
                .get("html5Preference")
                .getAsString());
        assertTrue(
            request.get("contentCheckOk")
                .getAsBoolean());
        assertTrue(
            request.get("racyCheckOk")
                .getAsBoolean());
        assertEquals("28", http.requestHeaders.get("X-YouTube-Client-Name"));
        assertEquals("1.65.10", http.requestHeaders.get("X-YouTube-Client-Version"));
        assertEquals("test-visitor", http.requestHeaders.get("X-Goog-Visitor-Id"));
    }

    @Test
    public void usesVisitorBoundAndroidVrPlayerContextForDownloadableStreams() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":["
                + "{\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"bitrate\":128000,"
                + "\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"}]}}");
        http.visitorPage = "{\"VISITOR_DATA\":\"visitor-token\"}";

        YouTubeMediaModels.ResolvedAudioStream stream = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        JsonObject request = new Gson().fromJson(http.requestBody, JsonObject.class);
        JsonObject client = request.getAsJsonObject("context")
            .getAsJsonObject("client");
        assertEquals(
            "ANDROID_VR",
            client.get("clientName")
                .getAsString());
        assertEquals(
            "1.65.10",
            client.get("clientVersion")
                .getAsString());
        assertEquals("28", http.requestHeaders.get("X-YouTube-Client-Name"));
        assertEquals("1.65.10", http.requestHeaders.get("X-YouTube-Client-Version"));
        assertEquals("visitor-token", http.requestHeaders.get("X-Goog-Visitor-Id"));
        assertEquals(
            "HTML5_PREF_WANTS",
            request.getAsJsonObject("playbackContext")
                .getAsJsonObject("contentPlaybackContext")
                .get("html5Preference")
                .getAsString());
        assertEquals(1, http.watchRequests);
        assertEquals("visitor-token", stream.getVisitorData());
    }

    @Test
    public void fallsBackToIosPlayerWhenAndroidPlayerIsUnavailable() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm you are not a bot\"}}");
        http.iosResponse = "{\"streamingData\":{\"adaptiveFormats\":["
            + "{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,"
            + "\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"}]}}";

        YouTubeMediaModels.ResolvedAudioStream stream = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        assertEquals(MediaFormat.M4A, stream.getFormat());
        assertEquals(2, http.playerRequests);
        assertEquals("IOS", http.lastClientName);
    }

    @Test
    public void fallsBackToIosPlayerWhenVisitorPageIsBlocked() throws Exception {
        FakeHttp http = new FakeHttp(
            "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm you are not a bot\"}}");
        http.failVisitorPage = true;
        http.iosResponse = "{\"streamingData\":{\"adaptiveFormats\":["
            + "{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,"
            + "\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"}]}}";

        YouTubeMediaModels.ResolvedAudioStream stream = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        assertEquals(MediaFormat.M4A, stream.getFormat());
        assertEquals(2, http.playerRequests);
        assertEquals("IOS", http.lastClientName);
    }

    @Test
    public void deciphersSignatureAndNParametersFromBoundedFixtureTransforms() throws Exception {
        String cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D2000%26n%3Dold%26%256e%3Dabc%26sig%3Dold%26%2573ig%3Dolder"
            + "&s=abcdef&sp=sig";
        FakeHttp http = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mpeg; codecs=\\\"mp3\\\"\",\"bitrate\":128000,\"signatureCipher\":\""
                + cipher
                + "\"}]}}");
        YouTubeStreamResolver resolver = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L,
            "reverse",
            "reverse");

        YouTubeMediaModels.ResolvedAudioStream stream = resolver.resolveAudio("dQw4w9WgXcQ");

        assertEquals(
            "fedcba",
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "sig").get(0));
        assertEquals(
            "cba",
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "n").get(0));
        assertEquals(
            1,
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "sig").size());
        assertEquals(
            1,
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "n").size());
    }

    @Test
    public void rejectsExpiredAndUnsafeResolvedUrlsBeforeDownload() throws Exception {
        FakeHttp expired = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"url\":\"https://r1.googlevideo.com/videoplayback?expire=999\"}]}}");
        assertResolutionFails(new YouTubeStreamResolver(expired, new AudioDecoderRegistry(), () -> 1000000L));
        FakeHttp unsafe = new FakeHttp(
            "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"url\":\"https://evil.example/audio?expire=2000\"}]}}");
        assertResolutionFails(new YouTubeStreamResolver(unsafe, new AudioDecoderRegistry(), () -> 1000000L));
    }

    @Test
    public void defaultResolverFetchesPlayerJavaScriptAndAppliesExtractedPlans() throws Exception {
        String cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D2000%26n%3Dabcd&s=abcdef&sp=sig";
        String player = "{\"assets\":{\"js\":\"https://www.youtube.com/s/player/test/base.js\"},\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mpeg; codecs=\\\"mp3\\\"\",\"bitrate\":128000,\"signatureCipher\":\""
            + cipher
            + "\"}]}}";
        String script = "var T={r:function(a){a.reverse()},s:function(a,b){a.splice(0,b)},w:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b]=c}};"
            + "function sig(a){a=a.split(\"\");T.w(a,2);T.r(a);return a.join(\"\")};"
            + "function n(a){a=a.split(\"\");T.s(a,1);T.r(a);return a.join(\"\")};"
            + "function D(p){p.sig=sig(p.s);var t=p.get(\"n\");t&&(t=n(t));p.set(\"n\",t)}";
        PlayerAndScriptHttp http = new PlayerAndScriptHttp(player, script);

        YouTubeMediaModels.ResolvedAudioStream stream = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        assertEquals(
            "fedabc",
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "sig").get(0));
        assertEquals(
            "dcb",
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "n").get(0));
        assertEquals(
            1,
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "sig").size());
        assertEquals(
            1,
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "n").size());
        assertEquals(1, http.scriptRequests);
    }

    @Test
    public void defaultResolverDiscoversObfuscatedPlayerTransformsFromSignatureAndNCallSites() throws Exception {
        String cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D2000%26n%3Dabcd&s=abcdef&sp=sig";
        String player = "{\"assets\":{\"js\":\"https://www.youtube.com/s/player/test/base.js\"},\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mpeg; codecs=\\\"mp3\\\"\",\"bitrate\":128000,\"signatureCipher\":\""
            + cipher
            + "\"}]}}";
        String script = "var Qx={\"rv\":function(a){a.reverse()},'sl':function(a,b){a.splice(0,b)},sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b]=c}};"
            + "var aB=function(z){z=z.split(\"\");Qx[\"sw\"](z,2);Qx.rv(z);return z.join(\"\")};"
            + "function cD(q){q=q.split(\"\");Qx['sl'](q,1);Qx.rv(q);return q.join(\"\")};"
            + "function D(p){p.sig=aB(p.s);var t=p.get(\"n\");t&&(t=cD(t));p.set(\"n\",t)}";
        PlayerAndScriptHttp http = new PlayerAndScriptHttp(player, script);

        YouTubeMediaModels.ResolvedAudioStream stream = new YouTubeStreamResolver(
            http,
            new AudioDecoderRegistry(),
            () -> 1000000L).resolveAudio("dQw4w9WgXcQ");

        assertEquals(
            "fedabc",
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "sig").get(0));
        assertEquals(
            "dcb",
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "n").get(0));
        assertEquals(
            1,
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "sig").size());
        assertEquals(
            1,
            decodedQueryValues(
                stream.getUrl()
                    .getQuery(),
                "n").size());
        assertEquals(1, http.scriptRequests);
    }

    @Test
    public void defaultResolverRejectsAHelperThatMixesReverseWithAnUnsupportedMutation() throws Exception {
        String cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D2000%26n%3Dabcd&s=abcdef&sp=sig";
        String player = "{\"assets\":{\"js\":\"https://www.youtube.com/s/player/test/base.js\"},\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mpeg; codecs=\\\"mp3\\\"\",\"signatureCipher\":\""
            + cipher
            + "\"}]}}";
        String script = "var Q={r:function(a){a.reverse();a.push(\"x\")},s:function(a,b){a.splice(0,b)}};"
            + "var A=function(x){x=x.split(\"\");Q.r(x);return x.join(\"\")};"
            + "function B(x){x=x.split(\"\");Q.s(x,1);return x.join(\"\")};"
            + "function D(p){p.sig=A(p.s);var n=p.get(\"n\");n&&(n=B(n));p.set(\"n\",n)}";
        PlayerAndScriptHttp http = new PlayerAndScriptHttp(player, script);

        try {
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L).resolveAudio("dQw4w9WgXcQ");
            fail("Expected a mixed player helper to be rejected");
        } catch (MediaException expected) {
            assertEquals(1, http.scriptRequests);
        }
    }

    @Test
    public void defaultResolverRejectsAnExecutableArrowTransformDespiteStringTemplateAndCommentDecoys()
        throws Exception {
        String cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D2000%26n%3Dabcd&s=abcdef&sp=sig";
        String player = "{\"assets\":{\"js\":\"https://www.youtube.com/s/player/test/base.js\"},\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mpeg; codecs=\\\"mp3\\\"\",\"signatureCipher\":\""
            + cipher
            + "\"}]}}";
        String script = "var H={r:function(a){a.reverse()},s:function(a,b){a.splice(0,b)}};"
            + "var decoy=\"function A(x){x=x.split('');H.r(x);return x.join('')}\";"
            + "var template=`A(p.s);function ignored(x){return x}`;/* function ignoredToo(x){x.reverse()} */"
            + "var A=x=>x.split(\"\").sort().join(\"\");"
            + "function B(x){x=x.split(\"\");H.s(x,1);return x.join(\"\")};"
            + "function D(p){p.sig=A(p.s);var n=p.get(\"n\");n&&(n=B(n));p.set(\"n\",n)}";
        PlayerAndScriptHttp http = new PlayerAndScriptHttp(player, script);

        try {
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L).resolveAudio("dQw4w9WgXcQ");
            fail("Expected executable unsupported arrow transform to be rejected");
        } catch (MediaException expected) {
            assertEquals(1, http.scriptRequests);
        }
    }

    @Test
    public void rejectsCipherWhenTheFetchedPlayerScriptHasUnsupportedTransforms() throws Exception {
        String cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D2000%26n%3Dabcd&s=abcdef&sp=sig";
        String player = "{\"assets\":{\"js\":\"https://www.youtube.com/s/player/test/base.js\"},\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mpeg; codecs=\\\"mp3\\\"\",\"signatureCipher\":\""
            + cipher
            + "\"}]}}";
        PlayerAndScriptHttp http = new PlayerAndScriptHttp(
            player,
            "function sig(a){return a.split(\"\").sort().join(\"\")}");
        try {
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L).resolveAudio("dQw4w9WgXcQ");
            fail("Expected unsupported player JavaScript to be rejected");
        } catch (MediaException expected) {
            assertEquals(1, http.scriptRequests);
        }
    }

    @Test
    public void rejectsShortAndExtraPlayerJsonBodiesBeforeParsing() throws Exception {
        String player = "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000\"}]}}";
        assertPlayerBodyRejected(player, player.length() + 1L);
        assertPlayerBodyRejected(player + " ", player.length());
    }

    private static void assertResolutionFails(YouTubeStreamResolver resolver) throws Exception {
        try {
            resolver.resolveAudio("dQw4w9WgXcQ");
            fail("Expected resolver to reject invalid stream");
        } catch (MediaException expected) {
            // The resolver must fail before exposing an unsafe or expired URL.
        }
    }

    private static java.util.List<String> decodedQueryValues(String query, String expectedKey) throws Exception {
        java.util.List<String> values = new java.util.ArrayList<String>();
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = java.net.URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), "UTF-8");
            if (expectedKey.equals(key)) {
                values.add(java.net.URLDecoder.decode(equals < 0 ? "" : part.substring(equals + 1), "UTF-8"));
            }
        }
        return values;
    }

    private static void assertPlayerBodyRejected(String body, long declaredLength) throws Exception {
        FakeHttp http = new FakeHttp(body, declaredLength);
        try {
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L).resolveAudio("dQw4w9WgXcQ");
            fail("Expected mismatched player JSON body to be rejected");
        } catch (MediaException expected) {
            // Parsing may not begin until the declared body length is accounted for.
        }
        assertTrue(http.lastPlayerInput.closed);
    }

    private static final class FakeHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] response;
        private final long declaredLength;
        private String visitorPage = "{\"VISITOR_DATA\":\"test-visitor\"}";
        private String iosResponse;
        private boolean failVisitorPage;
        private int watchRequests;
        private int playerRequests;
        private String lastClientName = "";
        private String requestBody = "";
        private Map<String, String> requestHeaders = new HashMap<String, String>();
        private CloseTrackingInputStream lastPlayerInput;

        private FakeHttp(String response) {
            this(response, response.getBytes(StandardCharsets.UTF_8).length);
        }

        private FakeHttp(String response, long declaredLength) {
            this.response = response.getBytes(StandardCharsets.UTF_8);
            this.declaredLength = declaredLength;
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            requestBody = new String(body, StandardCharsets.UTF_8);
            playerRequests++;
            requestHeaders = headers == null ? new HashMap<String, String>() : new HashMap<String, String>(headers);
            JsonObject request = new Gson().fromJson(requestBody, JsonObject.class);
            lastClientName = request.getAsJsonObject("context")
                .getAsJsonObject("client")
                .get("clientName")
                .getAsString();
            byte[] playerResponse = "IOS".equals(lastClientName) && iosResponse != null
                ? iosResponse.getBytes(StandardCharsets.UTF_8)
                : response;
            long responseLength = "IOS".equals(lastClientName) && iosResponse != null ? playerResponse.length
                : declaredLength;
            lastPlayerInput = new CloseTrackingInputStream(playerResponse);
            return new YouTubeMediaModels.HttpResponse(url, 200, "application/json", responseLength, lastPlayerInput);
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) throws java.io.IOException {
            watchRequests++;
            if (failVisitorPage) throw new MediaException("YouTube watch page is blocked");
            byte[] page = visitorPage.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "text/html",
                page.length,
                new ByteArrayInputStream(page));
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }

    private static final class PlayerAndScriptHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] player;
        private final byte[] script;
        private int scriptRequests;

        private PlayerAndScriptHttp(String player, String script) {
            this.player = player.getBytes(StandardCharsets.UTF_8);
            this.script = script.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                player.length,
                new ByteArrayInputStream(player));
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) {
            if ("/watch".equals(url.getPath())) {
                byte[] visitor = "{\"VISITOR_DATA\":\"test-visitor\"}".getBytes(StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "text/html",
                    visitor.length,
                    new ByteArrayInputStream(visitor));
            }
            scriptRequests++;
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/javascript",
                script.length,
                new ByteArrayInputStream(script));
        }
    }
}
