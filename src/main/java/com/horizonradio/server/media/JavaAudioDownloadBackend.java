package com.horizonradio.server.media;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Downloads a freshly resolved YouTube stream through the Java decoder pipeline into an atomic WAV cache entry. */
public final class JavaAudioDownloadBackend implements YouTubeMediaModels.AudioDownloadBackend {

    private static final int TIMEOUT_MILLIS = 15000;
    private static final int PREFIX_BYTES = 44;
    private static final long RANGE_CHUNK_BYTES = 4L * 1024L * 1024L;
    // 192 MiB of 44.1 kHz stereo PCM covers the 15 minute default track limit with headroom.
    private static final long DEFAULT_MAXIMUM_BYTES = 192L * 1024L * 1024L;
    private static final String MEDIA_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final long RATE_LIMIT_BASE_MILLIS = 60000L;
    private static final long RATE_LIMIT_MAX_MILLIS = 600000L;
    private static final ConcurrentMap<Path, Object> DESTINATION_LOCKS = new ConcurrentHashMap<Path, Object>();
    private final YouTubeStreamResolver resolver;
    private final YouTubeMediaModels.HttpRequester requester;
    private final AudioDecoderRegistry registry;
    private final AudioFormatDetector detector = new AudioFormatDetector();
    private final long maximumBytes;
    private final LongSupplier clock;
    private final AtomicInteger consecutiveRateLimitFailures = new AtomicInteger();
    private final AtomicLong nextRateLimitRetryAtMillis = new AtomicLong();

    public JavaAudioDownloadBackend() {
        this(
            new YouTubeStreamResolver(),
            new YouTubeMediaModels.UrlConnectionHttpRequester(),
            new AudioDecoderRegistry(),
            DEFAULT_MAXIMUM_BYTES);
    }

    public JavaAudioDownloadBackend(YouTubeStreamResolver resolver, YouTubeMediaModels.HttpRequester requester,
        AudioDecoderRegistry registry, long maximumBytes) {
        this(resolver, requester, registry, maximumBytes, new LongSupplier() {

            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    JavaAudioDownloadBackend(YouTubeStreamResolver resolver, YouTubeMediaModels.HttpRequester requester,
        AudioDecoderRegistry registry, long maximumBytes, LongSupplier clock) {
        if (resolver == null || requester == null || registry == null || maximumBytes <= 0L || clock == null)
            throw new IllegalArgumentException("Download backend dependencies are required");
        this.resolver = resolver;
        this.requester = requester;
        this.registry = registry;
        this.maximumBytes = maximumBytes;
        this.clock = clock;
    }

    @Override
    public Path download(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
        throws IOException {
        if (token == null) throw new IllegalArgumentException("Cancellation token is required");
        if (destination == null) throw new IllegalArgumentException("WAV destination is required");
        Path lockPath = destination.toAbsolutePath()
            .normalize();
        Object lock = DESTINATION_LOCKS.get(lockPath);
        if (lock == null) {
            Object candidate = new Object();
            Object existing = DESTINATION_LOCKS.putIfAbsent(lockPath, candidate);
            lock = existing == null ? candidate : existing;
        }
        synchronized (lock) {
            return downloadLocked(videoId, destination, token);
        }
    }

    private Path downloadLocked(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
        throws IOException {
        checkRateLimited();
        try {
            Path result = downloadLockedOnce(videoId, destination, token);
            resetRateLimit();
            return result;
        } catch (IOException failure) {
            if (!containsHttp403(failure) || cancellationRequested(token)) {
                throw failure;
            }
            // A 403 means YouTube is rate-limiting this network. Re-mint under a fresh visitor id and
            // back off instead of retrying immediately: every extra request keeps the rate limit in place.
            resolver.invalidateVisitorCache();
            recordRateLimitFailure();
            throw failure;
        }
    }

    private void checkRateLimited() throws IOException {
        long now = clock.getAsLong();
        long retryAt = nextRateLimitRetryAtMillis.get();
        if (now >= retryAt) return;
        throw new IOException(
            "YouTube audio is rate-limited (HTTP 403); retrying in " + (retryAt - now + 999L) / 1000L + "s");
    }

    private void recordRateLimitFailure() {
        int failures = consecutiveRateLimitFailures.incrementAndGet();
        long backoff = Math.min(RATE_LIMIT_BASE_MILLIS << Math.min(failures - 1, 4), RATE_LIMIT_MAX_MILLIS);
        nextRateLimitRetryAtMillis.set(clock.getAsLong() + backoff);
    }

    private void resetRateLimit() {
        consecutiveRateLimitFailures.set(0);
        nextRateLimitRetryAtMillis.set(0L);
    }

    private Path downloadLockedOnce(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
        throws IOException {
        checkCancelled(token);
        YouTubeStreamResolver.ResolvedAudioCandidates resolved = resolver.resolveAudioCandidates(videoId);
        checkCancelled(token);
        Set<String> attemptedUrls = new HashSet<String>();
        List<IOException> failures = new ArrayList<IOException>();
        List<IOException> transportFailures = new ArrayList<IOException>();
        Path result = tryCandidates(
            destination,
            token,
            resolved.getPrimaryCandidates(),
            attemptedUrls,
            failures,
            transportFailures,
            true);
        if (result != null) return result;

        if (!transportFailures.isEmpty()) {
            checkCancelled(token);
            try {
                resolved = resolver.resolveAudioCandidates(videoId);
            } catch (IOException refreshFailure) {
                failures.add(refreshFailure);
                throw aggregateFailure(videoId, failures);
            }
            attemptedUrls.clear();
            transportFailures.clear();
            result = tryCandidates(
                destination,
                token,
                resolved.getPrimaryCandidates(),
                attemptedUrls,
                failures,
                transportFailures,
                false);
            if (result != null) return result;
        }

        List<YouTubeMediaModels.ResolvedAudioStream> alternatives;
        try {
            alternatives = resolved.resolveAlternativeCandidates();
        } catch (IOException alternativeFailure) {
            failures.add(alternativeFailure);
            throw aggregateFailure(videoId, failures);
        }
        result = tryCandidates(destination, token, alternatives, attemptedUrls, failures, transportFailures, true);
        if (result != null) return result;
        throw aggregateFailure(videoId, failures);
    }

    private static boolean containsHttp403(Throwable failure) {
        if (failure == null) {
            return false;
        }
        if (failure.getMessage() != null && failure.getMessage()
            .contains("status 403")) {
            return true;
        }
        if (containsHttp403(failure.getCause())) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (containsHttp403(suppressed)) {
                return true;
            }
        }
        return false;
    }

    private Path tryCandidates(Path destination, YouTubeMediaModels.CancellationToken token,
        List<YouTubeMediaModels.ResolvedAudioStream> candidates, Set<String> attemptedUrls, List<IOException> failures,
        List<IOException> transportFailures, boolean ranged) throws IOException {
        for (YouTubeMediaModels.ResolvedAudioStream stream : candidates) {
            checkCancelled(token);
            if (!attemptedUrls.add(
                stream.getUrl()
                    .toExternalForm()))
                continue;
            try {
                return downloadCandidate(stream, destination, token, ranged);
            } catch (CandidateTransportFailure failure) {
                failures.add(failure);
                transportFailures.add(failure);
            } catch (CandidateDecodeFailure failure) {
                failures.add(failure);
            }
        }
        return null;
    }

    private Path downloadCandidate(YouTubeMediaModels.ResolvedAudioStream stream, Path destination,
        YouTubeMediaModels.CancellationToken token, boolean ranged) throws IOException {
        checkCancelled(token);
        if (stream.getExpiresAtMillis() <= clock.getAsLong()) {
            throw new CandidateDecodeFailure("YouTube stream URL expired before download");
        }
        WavFileSink sink = null;
        MediaRangeInputStream rangeInput = null;
        long chunkBytes = Math.min(RANGE_CHUNK_BYTES, maximumBytes);
        Map<String, String> mediaHeaders = mediaHeaders(stream);
        YouTubeMediaModels.HttpResponse response;
        try {
            response = openInitialResponse(stream, mediaHeaders, chunkBytes, ranged);
        } catch (IOException failure) {
            throw new CandidateTransportFailure("Unable to open YouTube audio candidate", failure);
        }
        try (YouTubeMediaModels.HttpResponse responseResource = response) {
            if (responseResource.getStatusCode() < 200 || responseResource.getStatusCode() >= 300
                || !YouTubeStreamResolver.isSafeMediaUrl(responseResource.getUrl())) {
                throw new CandidateTransportFailure("Audio candidate response is not trusted");
            }
            InputStream compressed;
            long expectedLength;
            if (responseResource.getStatusCode() == 206) {
                try {
                    rangeInput = MediaRangeInputStream.open(
                        stream.getUrl(),
                        requester,
                        mediaHeaders,
                        TIMEOUT_MILLIS,
                        maximumBytes,
                        chunkBytes,
                        responseResource);
                } catch (IOException failure) {
                    throw new CandidateTransportFailure("Audio candidate range response is not trusted", failure);
                }
                compressed = rangeInput;
                expectedLength = rangeInput.getTotalBytes();
            } else {
                if (responseResource.getContentLength() < 0L || responseResource.getContentLength() > maximumBytes) {
                    throw new CandidateTransportFailure("Audio candidate response exceeds its finite limits");
                }
                compressed = new BoundedInputStream(
                    responseResource.getInputStream(),
                    responseResource.getContentLength());
                expectedLength = responseResource.getContentLength();
            }
            if (!YouTubeStreamResolver.isSafeMediaUrl(responseResource.getUrl()))
                throw new CandidateTransportFailure("Audio response redirected to an unsafe URL");
            CountingInputStream counted = new CountingInputStream(compressed);
            InputStream input = new CancellationInputStream(counted, token);
            byte[] prefix;
            MediaFormat detected;
            try {
                prefix = readPrefix(input, PREFIX_BYTES);
                detected = detector.detect(responseResource.getContentType(), prefix);
            } catch (IOException failure) {
                if (cancellationRequested(token)) throw failure;
                throw new CandidateDecodeFailure("Audio candidate could not be inspected", failure);
            }
            if (detected != stream.getFormat() || !registry.supports(detected)) {
                throw new CandidateDecodeFailure("Resolved stream format does not match the audio response");
            }
            sink = new WavFileSink(destination, maximumBytes);
            DeferredFinishPcmSink deferred = new DeferredFinishPcmSink(sink, token);
            try {
                registry.find(detected, new java.io.ByteArrayInputStream(prefix), input)
                    .decode(new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(prefix), input), deferred);
                if (!deferred.isFinishRequested() || counted.getBytesRead() != expectedLength) {
                    throw new MediaException("Audio response does not exactly match its declared body length");
                }
            } catch (IOException failure) {
                if (cancellationRequested(token) || deferred.hasDownstreamFailure()) throw failure;
                throw new CandidateDecodeFailure("Audio candidate could not be decoded", failure);
            }
            checkCancelled(token);
            deferred.commit();
            return destination;
        } catch (IOException exception) {
            if (sink != null) {
                try {
                    sink.abort();
                } catch (IOException abortFailure) {
                    exception.addSuppressed(abortFailure);
                }
            }
            throw exception;
        } finally {
            if (rangeInput != null) rangeInput.close();
        }
    }

    private static MediaException aggregateFailure(String videoId, List<IOException> failures) {
        MediaException aggregate = new MediaException("No usable YouTube audio candidate for " + videoId);
        for (IOException failure : failures) aggregate.addSuppressed(failure);
        return aggregate;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    private YouTubeMediaModels.HttpResponse openInitialResponse(YouTubeMediaModels.ResolvedAudioStream stream,
        Map<String, String> mediaHeaders, long chunkBytes, boolean ranged) throws IOException {
        Map<String, String> firstHeaders = new HashMap<String, String>(mediaHeaders);
        if (ranged) {
            firstHeaders.put("Range", rangeHeader(0L, chunkBytes - 1L));
        }
        return requester
            .get(stream.getUrl(), firstHeaders, TIMEOUT_MILLIS, maximumBytes, YouTubeMediaModels.RedirectPolicy.MEDIA);
    }

    private static byte[] readPrefix(InputStream input, int maximum) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[maximum];
        int count = input.read(buffer);
        if (count > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void checkCancelled(YouTubeMediaModels.CancellationToken token) throws MediaException {
        if (cancellationRequested(token)) throw new MediaException("YouTube audio download cancelled");
    }

    private static boolean cancellationRequested(YouTubeMediaModels.CancellationToken token) {
        return token.isCancelled() || Thread.currentThread()
            .isInterrupted();
    }

    private static Map<String, String> mediaHeaders(YouTubeMediaModels.ResolvedAudioStream stream) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept", "*/*");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Referer", "https://www.youtube.com/");
        headers.put("User-Agent", MEDIA_USER_AGENT);
        if (stream.getVisitorData()
            .length() > 0) {
            headers.put("X-Goog-Visitor-Id", stream.getVisitorData());
        }
        return headers;
    }

    private static String rangeHeader(long start, long end) {
        return "bytes=" + start + "-" + end;
    }

    private static final class CancellationInputStream extends FilterInputStream {

        private final YouTubeMediaModels.CancellationToken token;

        private CancellationInputStream(InputStream input, YouTubeMediaModels.CancellationToken token) {
            super(input);
            this.token = token;
        }

        @Override
        public int read() throws IOException {
            checkCancelled(token);
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(token);
            return super.read(bytes, offset, length);
        }
    }

    private static final class CountingInputStream extends FilterInputStream {

        private long bytesRead;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }

        private long getBytesRead() {
            return bytesRead;
        }
    }

    private static final class DeferredFinishPcmSink implements PcmSink {

        private final PcmSink downstream;
        private final YouTubeMediaModels.CancellationToken token;
        private boolean finishRequested;
        private boolean downstreamFailure;

        private DeferredFinishPcmSink(PcmSink downstream, YouTubeMediaModels.CancellationToken token) {
            this.downstream = downstream;
            this.token = token;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            checkCancelled(token);
            try {
                downstream.write(data, offset, length);
            } catch (IOException failure) {
                downstreamFailure = true;
                throw failure;
            }
        }

        @Override
        public void finish() throws IOException {
            checkCancelled(token);
            finishRequested = true;
        }

        @Override
        public void abort() throws IOException {
            downstream.abort();
        }

        @Override
        public void close() throws IOException {
            abort();
        }

        private boolean isFinishRequested() {
            return finishRequested;
        }

        private boolean hasDownstreamFailure() {
            return downstreamFailure;
        }

        private void commit() throws IOException {
            token.finish(downstream);
        }
    }

    private static final class CandidateTransportFailure extends IOException {

        private CandidateTransportFailure(String message) {
            super(message);
        }

        private CandidateTransportFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class CandidateDecodeFailure extends IOException {

        private CandidateDecodeFailure(String message) {
            super(message);
        }

        private CandidateDecodeFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
