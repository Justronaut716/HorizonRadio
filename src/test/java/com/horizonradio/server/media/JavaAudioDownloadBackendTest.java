package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
        byte[] pcm = new byte[2000000];
        byte[] audio = wave(pcm);
        RangeFallbackHttp http = new RangeFallbackHttp(audio);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend(
            new YouTubeStreamResolver(http, new AudioDecoderRegistry(), () -> 1000000L),
            http,
            new AudioDecoderRegistry(),
            3L * 1024L * 1024L);
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
                throw new MediaException("HTTP request failed with status 403");
            }
            return new YouTubeMediaModels.HttpResponse(
                audioResponseUrl == null ? url : audioResponseUrl,
                200,
                "audio/wav",
                declaredAudioLength,
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
