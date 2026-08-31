package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class JavaAudioDownloadBackendTest {

    @Test
    public void resolvesFreshFixtureAudioAndAtomicallyPublishesNormalizedWave() throws Exception {
        FakeHttp http = new FakeHttp(wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }));
        YouTubeStreamResolver resolver = new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            resolver,
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            byte[] output = Files.readAllBytes(destination);
            assertEquals("RIFF", new String(output, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(52, output.length);
            assertTrue(http.audioRequests == 1);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void fallsBackAfterFragmentedM4aRejectionAndPublishesOnlyTheUsableCandidate() throws Exception {
        CandidateFallbackHttp http = new CandidateFallbackHttp(
            playerResponse(
                "https://r1.googlevideo.com/fragmented?expire=2000000000",
                "https://r2.googlevideo.com/fallback?expire=2000000000"),
            null);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-candidate-fallback");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(1, http.playerRequests);
            assertEquals(2, http.audioRequests);
            assertEquals(52L, Files.size(destination));
            assertOnlyDestination(directory, destination);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void triesTheLazyAlternativeProfileAfterAllPrimaryMediaCandidatesFail() throws Exception {
        CandidateFallbackHttp http = new CandidateFallbackHttp(
            playerResponse("https://r1.googlevideo.com/fragmented?expire=2000000000", null),
            wavPlayerResponse("https://r2.googlevideo.com/fallback?expire=2000000000"));
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-profile-fallback");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(2, http.playerRequests);
            assertEquals(2, http.audioRequests);
            assertOnlyDestination(directory, destination);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void switchesToAlternativeProfileAfterForbiddenPrimaryCandidateWithoutRefreshingStaleUrls()
        throws Exception {
        ForbiddenPrimaryHttp http = new ForbiddenPrimaryHttp();
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-403-profile-fallback");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(
                "a forbidden primary URL must not trigger a stale-primary request storm",
                2,
                http.audioRequests);
            assertEquals("the alternative profile is resolved exactly once", 2, http.playerRequests);
            assertEquals(52L, Files.size(destination));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void reportsFiniteCandidateFailureWithoutPublishingPartialOutput() throws Exception {
        CandidateFallbackHttp http = new CandidateFallbackHttp(
            playerResponse(
                "https://r1.googlevideo.com/fragmented-one?expire=2000000000",
                "https://r2.googlevideo.com/fragmented-two?expire=2000000000"),
            null);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-candidate-failure");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("all unusable candidates should fail the download");
            } catch (MediaException exception) {
                assertTrue(exception.getSuppressed().length >= 2);
            }
            assertEquals(2, http.audioRequests);
            assertFalse(Files.exists(destination));
            assertDirectoryEmpty(directory);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void defaultDownloadBudgetCoversConfiguredSevenMinuteTracks() throws Exception {
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend();
        java.lang.reflect.Field budget = JavaAudioDownloadBackend.class.getDeclaredField("maximumBytes");
        budget.setAccessible(true);

        long normalizedSevenMinuteWaveBytes = 44L + 176400L * 7L * 60L;
        assertTrue(
            "default media budget is too small for a seven-minute normalized track",
            budget.getLong(backend) >= normalizedSevenMinuteWaveBytes);
    }

    @Test
    public void retriesATransientInitialMediaFailureBeforeRejectingTheTrack() throws Exception {
        FakeHttp http = new FakeHttp(wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }), true);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-retry");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(2, http.audioRequests);
            assertEquals(1, http.rangedAudioRequests);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void invalidatesTheCachedVisitorIdBeforeRetryingForbiddenMedia() throws Exception {
        Visitor403Http http = new Visitor403Http();
        AtomicLong now = new AtomicLong(1000000L);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L,
            now::get);
        Path directory = Files.createTempDirectory("horizonradio-download-visitor-403");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            // The first attempt burns the cached visitor id (every candidate 403s) and trips the rate-limit breaker.
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("a burned visitor id should fail the first attempt");
            } catch (MediaException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("dQw4w9WgXcQ"));
            }
            // While rate-limited, downloads fail fast without contacting YouTube at all.
            int requestsAfterFirstAttempt = http.watchRequests + http.audioRequests;
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("downloads must fail fast while rate-limited");
            } catch (IOException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("rate-limited"));
            }
            assertEquals(requestsAfterFirstAttempt, http.watchRequests + http.audioRequests);
            // After the backoff elapses, the retry resolves a fresh visitor id instead of reusing the burned one.
            now.addAndGet(60000L);
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(2, http.watchRequests);
            assertEquals("visitor-2", http.lastAudioVisitorId);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void failsFastWithoutRequestsWhileRateLimitedAfterConsecutiveForbiddenFailures() throws Exception {
        RateLimitHttp http = new RateLimitHttp();
        AtomicLong now = new AtomicLong(1000000L);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L,
            now::get);
        Path directory = Files.createTempDirectory("horizonradio-download-rate-limit");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            // First forbidden attempt: real requests are made and the breaker opens for the base backoff.
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("forbidden media should fail the download");
            } catch (MediaException expected) {
                // Every candidate was rejected with a 403.
            }
            assertEquals("one candidate per profile is enough to open the cooldown", 2, http.audioRequests);
            assertRateLimitedFastFailure(backend, destination, http);

            // After the base backoff elapses, a retry is allowed and fails again, doubling the backoff.
            now.addAndGet(60000L);
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("forbidden media should fail the download again");
            } catch (MediaException expected) {
                // The second consecutive forbidden attempt extends the cooldown.
            }
            assertRateLimitedFastFailure(backend, destination, http);

            // The doubled backoff elapses and a successful download closes the breaker again.
            now.addAndGet(120000L);
            http.failAudio = false;
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));

            // After a success the breaker is reset: a new failure restarts at the base backoff.
            http.failAudio = true;
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("forbidden media should fail the download again");
            } catch (MediaException expected) {
                // The reset breaker applies the base backoff instead of continuing the doubled schedule.
            }
            int requestsBeforeBaseBackoffRetry = http.audioRequests;
            now.addAndGet(60000L);
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("forbidden media should keep failing while YouTube returns 403");
            } catch (MediaException expected) {
                // A fresh attempt was made instead of a rate-limit fast failure: the backoff restarted at 60s.
            }
            assertTrue(http.audioRequests > requestsBeforeBaseBackoffRetry);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void serializesConcurrentDownloadsToAvoidAYouTubeRequestBurst() throws Exception {
        BlockingHttp http = new BlockingHttp();
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-serialized");
        Path firstDestination = directory.resolve("dQw4w9WgXcQ.wav");
        Path secondDestination = directory.resolve("a234567890_.wav");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Path> first = executor.submit(() -> backend.download("dQw4w9WgXcQ", firstDestination, () -> false));
            assertTrue(http.firstAudioEntered.await(1, TimeUnit.SECONDS));
            Future<Path> second = executor
                .submit(() -> backend.download("a234567890_", secondDestination, () -> false));

            assertFalse(
                "the second download must wait until the first has left the media gate",
                http.secondAudioEntered.await(200, TimeUnit.MILLISECONDS));
            http.releaseFirst.countDown();

            assertEquals(firstDestination, first.get(2, TimeUnit.SECONDS));
            assertEquals(secondDestination, second.get(2, TimeUnit.SECONDS));
            assertEquals(1, http.maximumConcurrentAudio.get());
        } finally {
            http.releaseFirst.countDown();
            executor.shutdownNow();
            Files.deleteIfExists(firstDestination);
            Files.deleteIfExists(secondDestination);
            Files.deleteIfExists(directory);
        }
    }

    private static void assertRateLimitedFastFailure(JavaAudioDownloadBackend backend, Path destination,
        RateLimitHttp http) throws Exception {
        int requestsBefore = http.watchRequests + http.audioRequests;
        try {
            backend.download("dQw4w9WgXcQ", destination, () -> false);
            fail("downloads must fail fast while rate-limited");
        } catch (IOException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("rate-limited"));
        }
        assertEquals(requestsBefore, http.watchRequests + http.audioRequests);
    }

    @Test
    public void downloadsAudioThroughABoundedRangeRequest() throws Exception {
        byte[] audio = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        RangeFallbackHttp http = new RangeFallbackHttp(audio);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-range");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(0, http.fullRequests);
            assertEquals(1, http.rangeRequests);
            assertTrue(http.lastRange.startsWith("bytes=0-"));
            assertEquals(52, Files.size(destination));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void downloadsAudioAcrossMultipleRangesBeforePublishingTheWave() throws Exception {
        byte[] pcm = new byte[8000000];
        byte[] audio = wave(pcm);
        RangeFallbackHttp http = new RangeFallbackHttp(audio);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            16L * 1024L * 1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-multi-range");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            assertEquals(0, http.fullRequests);
            assertEquals(2, http.rangeRequests);
            assertEquals(audio.length, Files.size(destination));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void cancellationLeavesNoCacheEntryOrTemporaryOutput() throws Exception {
        FakeHttp http = new FakeHttp(wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }));
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-cancel");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> true);
            } catch (MediaException expected) {
                // Cancellation is reported as a controlled download failure.
            }
            assertFalse(Files.exists(destination));
            assertEquals(0, http.audioRequests);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void rejectsAnUnsafeAudioRedirectBeforePublishingTheCacheEntry() throws Exception {
        FakeHttp http = new FakeHttp(
            wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }),
            new URL("https://evil.example/audio"));
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-redirect");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
            } catch (MediaException expected) {
                // Redirects may not escape the explicitly trusted media hosts.
            }
            assertFalse(Files.exists(destination));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void rejectsShortAndTrailingAudioBodiesBeforePublishingTheWave() throws Exception {
        assertBodyRejected(wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 }), 56L);
        byte[] valid = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        byte[] trailing = new byte[valid.length + 4];
        System.arraycopy(valid, 0, trailing, 0, valid.length);
        assertBodyRejected(trailing, trailing.length);
    }

    @Test
    public void cancellationDuringDecodeLeavesNoOutputAndAllowsTheNextOperationToPublish() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        byte[] first = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        byte[] second = wave(new byte[] { 9, 0, 8, 0, 7, 0, 6, 0 });
        CancelThenSucceedHttp http = new CancelThenSucceedHttp(first, second, cancelled);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-restart");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            try {
                backend.download("dQw4w9WgXcQ", destination, cancelled::get);
                fail("Expected cancellation during decode");
            } catch (MediaException expected) {
                // The cancellation becomes true after compressed bytes are read but before PCM publication.
            }
            assertFalse(Files.exists(destination));

            assertEquals(destination, backend.download("dQw4w9WgXcQ", destination, () -> false));
            byte[] published = Files.readAllBytes(destination);
            assertEquals(9, published[44]);
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    private static void assertBodyRejected(byte[] body, long declaredLength) throws Exception {
        FakeHttp http = new FakeHttp(body, null, declaredLength);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            1024L);
        Path directory = Files.createTempDirectory("horizonradio-download-length");
        Path destination = directory.resolve("dQw4w9WgXcQ.wav");
        try {
            try {
                backend.download("dQw4w9WgXcQ", destination, () -> false);
                fail("Expected malformed HTTP body length to be rejected");
            } catch (MediaException expected) {
                // No valid prefix may publish before the full declared body is accounted for.
            }
            assertFalse(Files.exists(destination));
        } finally {
            Files.deleteIfExists(destination);
            Files.deleteIfExists(directory);
        }
    }

    private static void assertOnlyDestination(Path directory, Path destination) throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path file : files) {
                count++;
                assertEquals(destination.getFileName(), file.getFileName());
            }
            assertEquals(1, count);
        }
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            assertFalse(
                files.iterator()
                    .hasNext());
        }
    }

    private static String playerResponse(String firstUrl, String secondUrl) {
        StringBuilder formats = new StringBuilder();
        formats.append("{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":128000,\"url\":\"")
            .append(firstUrl)
            .append("\"}");
        if (secondUrl != null) {
            formats.append(",")
                .append("{\"mimeType\":\"audio/wav\",\"bitrate\":64000,\"url\":\"")
                .append(secondUrl)
                .append("\"}");
        }
        return "{\"streamingData\":{\"adaptiveFormats\":[" + formats + "]}}";
    }

    private static String wavPlayerResponse(String url) {
        return "{\"streamingData\":{\"adaptiveFormats\":[" + "{\"mimeType\":\"audio/wav\",\"bitrate\":64000,\"url\":\""
            + url
            + "\"}]}}";
    }

    private static byte[] fragmentedM4a() {
        return join(
            box("ftyp", new byte[] { 'M', '4', 'A', ' ', 0, 0, 0, 0, 'i', 's', 'o', 'm' }),
            box("moov", box("mvex", new byte[0])));
    }

    private static byte[] box(String type, byte[] payload) {
        byte[] result = new byte[8 + payload.length];
        result[0] = (byte) (result.length >>> 24);
        result[1] = (byte) (result.length >>> 16);
        result[2] = (byte) (result.length >>> 8);
        result[3] = (byte) result.length;
        for (int index = 0; index < type.length(); index++) result[4 + index] = (byte) type.charAt(index);
        System.arraycopy(payload, 0, result, 8, payload.length);
        return result;
    }

    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static final class FakeHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] audio;
        private final URL audioResponseUrl;
        private final long declaredAudioLength;
        private boolean failFirstAudioRequest;
        private int audioRequests;
        private int rangedAudioRequests;

        private FakeHttp(byte[] audio) {
            this(audio, null, audio.length);
        }

        private FakeHttp(byte[] audio, URL audioResponseUrl) {
            this(audio, audioResponseUrl, audio.length);
        }

        private FakeHttp(byte[] audio, boolean failFirstAudioRequest) {
            this(audio, null, audio.length, failFirstAudioRequest);
        }

        private FakeHttp(byte[] audio, URL audioResponseUrl, long declaredAudioLength) {
            this(audio, audioResponseUrl, declaredAudioLength, false);
        }

        private FakeHttp(byte[] audio, URL audioResponseUrl, long declaredAudioLength, boolean failFirstAudioRequest) {
            this.audio = audio;
            this.audioResponseUrl = audioResponseUrl;
            this.declaredAudioLength = declaredAudioLength;
            this.failFirstAudioRequest = failFirstAudioRequest;
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            String json = "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/wav; codecs=\\\"1\\\"\",\"bitrate\":128000,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000000000\"}]}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) throws java.io.IOException {
            if ("/watch".equals(url.getPath())) {
                byte[] visitor = "{\"VISITOR_DATA\":\"test-visitor\"}".getBytes(StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "text/html",
                    visitor.length,
                    new ByteArrayInputStream(visitor));
            }
            audioRequests++;
            if (headers != null && headers.get("Range") != null) {
                rangedAudioRequests++;
            }
            if (failFirstAudioRequest && headers != null && headers.get("Range") != null) {
                failFirstAudioRequest = false;
                throw new IOException("temporary media connection failure");
            }
            return new YouTubeMediaModels.HttpResponse(
                audioResponseUrl == null ? url : audioResponseUrl,
                200,
                "audio/wav",
                declaredAudioLength,
                new ByteArrayInputStream(audio));
        }
    }

    private static final class CandidateFallbackHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] androidResponse;
        private final byte[] iosResponse;
        private final byte[] fragmented = fragmentedM4a();
        private final byte[] fallback = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        private int playerRequests;
        private int audioRequests;

        private CandidateFallbackHttp(String androidResponse, String iosResponse) {
            this.androidResponse = androidResponse.getBytes(StandardCharsets.UTF_8);
            this.iosResponse = iosResponse == null ? null : iosResponse.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            playerRequests++;
            boolean ios = new String(body, StandardCharsets.UTF_8).contains("\"clientName\":\"IOS\"");
            byte[] response = ios && iosResponse != null ? iosResponse : androidResponse;
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
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
            audioRequests++;
            boolean fragmentedResponse = url.getPath()
                .contains("fragmented");
            byte[] body = fragmentedResponse ? fragmented : fallback;
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                fragmentedResponse ? "audio/mp4" : "audio/wav",
                body.length,
                new ByteArrayInputStream(body));
        }
    }

    private static final class ForbiddenPrimaryHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] audio = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        private int playerRequests;
        private int audioRequests;

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            playerRequests++;
            boolean ios = new String(body, StandardCharsets.UTF_8).contains("\"clientName\":\"IOS\"");
            String response = ios ? wavPlayerResponse("https://r2.googlevideo.com/fallback?expire=2000000000")
                : playerResponse(
                    "https://r1.googlevideo.com/blocked-one?expire=2000000000",
                    "https://r1.googlevideo.com/blocked-two?expire=2000000000");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                bytes.length,
                new ByteArrayInputStream(bytes));
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) throws IOException {
            if ("/watch".equals(url.getPath())) {
                byte[] visitor = "{\"VISITOR_DATA\":\"test-visitor\"}".getBytes(StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "text/html",
                    visitor.length,
                    new ByteArrayInputStream(visitor));
            }
            audioRequests++;
            if (url.getPath()
                .contains("blocked")) {
                throw new YouTubeMediaModels.HttpStatusException(403);
            }
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "audio/wav",
                audio.length,
                new ByteArrayInputStream(audio));
        }
    }

    private static final class BlockingHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] audio = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        private final CountDownLatch firstAudioEntered = new CountDownLatch(1);
        private final CountDownLatch secondAudioEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicInteger concurrentAudio = new AtomicInteger();
        private final AtomicInteger maximumConcurrentAudio = new AtomicInteger();
        private final AtomicInteger audioRequests = new AtomicInteger();

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            String json = wavPlayerResponse("https://r1.googlevideo.com/serialized?expire=2000000000");
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) throws IOException {
            if ("/watch".equals(url.getPath())) {
                byte[] visitor = "{\"VISITOR_DATA\":\"test-visitor\"}".getBytes(StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "text/html",
                    visitor.length,
                    new ByteArrayInputStream(visitor));
            }
            int request = audioRequests.incrementAndGet();
            if (request == 1) {
                firstAudioEntered.countDown();
            } else {
                secondAudioEntered.countDown();
            }
            int concurrent = concurrentAudio.incrementAndGet();
            updateMaximum(concurrent);
            try {
                if (request == 1) {
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread()
                            .interrupt();
                        throw new IOException("test media request interrupted", exception);
                    }
                }
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "audio/wav",
                    audio.length,
                    new ByteArrayInputStream(audio));
            } finally {
                concurrentAudio.decrementAndGet();
            }
        }

        private void updateMaximum(int candidate) {
            while (true) {
                int current = maximumConcurrentAudio.get();
                if (candidate <= current || maximumConcurrentAudio.compareAndSet(current, candidate)) return;
            }
        }
    }

    private static final class Visitor403Http implements YouTubeMediaModels.HttpRequester {

        private final byte[] audio = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        private int watchRequests;
        private int audioRequests;
        private String lastAudioVisitorId = "";

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            String json = "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/wav; codecs=\\\"1\\\"\","
                + "\"bitrate\":128000,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000000000\"}]}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) throws java.io.IOException {
            if ("/watch".equals(url.getPath())) {
                watchRequests++;
                byte[] visitor = ("{\"VISITOR_DATA\":\"visitor-" + watchRequests + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "text/html",
                    visitor.length,
                    new ByteArrayInputStream(visitor));
            }
            audioRequests++;
            lastAudioVisitorId = headers == null ? "" : String.valueOf(headers.get("X-Goog-Visitor-Id"));
            if ("visitor-1".equals(lastAudioVisitorId)) {
                throw new YouTubeMediaModels.HttpStatusException(403);
            }
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "audio/wav",
                audio.length,
                new ByteArrayInputStream(audio));
        }
    }

    private static final class RateLimitHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] audio = wave(new byte[] { 1, 0, 2, 0, 3, 0, 4, 0 });
        private int watchRequests;
        private int audioRequests;
        private boolean failAudio = true;

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            String json = "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/wav; codecs=\\\"1\\\"\","
                + "\"bitrate\":128000,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000000000\"}]}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
        }

        @Override
        public YouTubeMediaModels.HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis,
            long maximumBytes) throws java.io.IOException {
            if ("/watch".equals(url.getPath())) {
                watchRequests++;
                byte[] visitor = "{\"VISITOR_DATA\":\"test-visitor\"}".getBytes(StandardCharsets.UTF_8);
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    200,
                    "text/html",
                    visitor.length,
                    new ByteArrayInputStream(visitor));
            }
            audioRequests++;
            if (failAudio) {
                throw new YouTubeMediaModels.HttpStatusException(403);
            }
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "audio/wav",
                audio.length,
                new ByteArrayInputStream(audio));
        }
    }

    private static final class CancelThenSucceedHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] first;
        private final byte[] second;
        private final AtomicBoolean cancelled;
        private int requests;

        private CancelThenSucceedHttp(byte[] first, byte[] second, AtomicBoolean cancelled) {
            this.first = first;
            this.second = second;
            this.cancelled = cancelled;
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            String json = "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/wav; codecs=\\\"1\\\"\",\"bitrate\":128000,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000000000\"}]}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
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
            requests++;
            byte[] body = requests == 1 ? first : second;
            java.io.InputStream input = requests == 1 ? new CancelAfterPcmReadInputStream(body, cancelled)
                : new ByteArrayInputStream(body);
            return new YouTubeMediaModels.HttpResponse(url, 200, "audio/wav", body.length, input);
        }
    }

    private static final class RangeFallbackHttp implements YouTubeMediaModels.HttpRequester {

        private final byte[] audio;
        private int fullRequests;
        private int rangeRequests;
        private String lastRange = "";

        private RangeFallbackHttp(byte[] audio) {
            this.audio = audio;
        }

        @Override
        public YouTubeMediaModels.HttpResponse post(URL url, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes) {
            String json = "{\"streamingData\":{\"adaptiveFormats\":[{\"mimeType\":\"audio/wav; codecs=\\\"1\\\"\","
                + "\"bitrate\":128000,\"url\":\"https://r1.googlevideo.com/videoplayback?expire=2000000000\"}]}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            return new YouTubeMediaModels.HttpResponse(
                url,
                200,
                "application/json",
                response.length,
                new ByteArrayInputStream(response));
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
            String range = headers == null ? null : headers.get("Range");
            if (range == null) {
                fullRequests++;
                return new YouTubeMediaModels.HttpResponse(
                    url,
                    403,
                    "text/plain",
                    0L,
                    new ByteArrayInputStream(new byte[0]));
            }
            rangeRequests++;
            lastRange = range;
            int separator = range.indexOf('-');
            long start = Long.parseLong(range.substring("bytes=".length(), separator));
            long requestedEnd = Long.parseLong(range.substring(separator + 1));
            int end = (int) Math.min(requestedEnd, audio.length - 1L);
            byte[] response = Arrays.copyOfRange(audio, (int) start, end + 1);
            return new YouTubeMediaModels.HttpResponse(
                url,
                206,
                "audio/wav",
                response.length,
                new ByteArrayInputStream(response),
                "bytes " + start + "-" + end + "/" + audio.length);
        }
    }

    private static final class CancelAfterPcmReadInputStream extends java.io.InputStream {

        private final byte[] bytes;
        private final AtomicBoolean cancelled;
        private int offset;

        private CancelAfterPcmReadInputStream(byte[] bytes, AtomicBoolean cancelled) {
            this.bytes = bytes;
            this.cancelled = cancelled;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (offset >= bytes.length) return -1;
            int count = Math.min(length, offset == 0 ? 44 : bytes.length - offset);
            System.arraycopy(bytes, offset, target, targetOffset, count);
            offset += count;
            if (offset > 44) cancelled.set(true);
            return count;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }
    }

    private static byte[] wave(byte[] pcm) {
        byte[] wav = new byte[44 + pcm.length];
        ascii(wav, 0, "RIFF");
        leInt(wav, 4, 36 + pcm.length);
        ascii(wav, 8, "WAVEfmt ");
        leInt(wav, 16, 16);
        leShort(wav, 20, 1);
        leShort(wav, 22, 2);
        leInt(wav, 24, 44100);
        leInt(wav, 28, 176400);
        leShort(wav, 32, 4);
        leShort(wav, 34, 16);
        ascii(wav, 36, "data");
        leInt(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) bytes[offset + i] = (byte) value.charAt(i);
    }

    private static void leShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void leInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
    }
}
