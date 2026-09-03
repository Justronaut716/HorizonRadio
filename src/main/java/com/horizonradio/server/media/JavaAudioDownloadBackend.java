package com.horizonradio.server.media;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Downloads a freshly resolved YouTube stream through the Java decoder pipeline into an atomic WAV cache entry. */
public final class JavaAudioDownloadBackend implements YouTubeMediaModels.AudioDownloadBackend {

    private static final int TIMEOUT_MILLIS = 15000;
    private static final int PREFIX_BYTES = 44;
    // One range request must cover a whole default (7 min) track so the common case is exactly one
    // HTTP request to googlevideo (yt-dlp parity). The server clamps a range that exceeds the file,
    // so this costs nothing for smaller tracks; a dropped transfer still resumes from its offset.
    private static final long RANGE_CHUNK_BYTES = 16L * 1024L * 1024L;
    // 192 MiB of 44.1 kHz stereo PCM covers the 15 minute default track limit with headroom.
    private static final long DEFAULT_MAXIMUM_BYTES = 192L * 1024L * 1024L;
    private static final String MEDIA_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final long RATE_LIMIT_BASE_MILLIS = 60000L;
    private static final long RATE_LIMIT_MAX_MILLIS = 600000L;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_PENDING_TRANSFERS = 8;
    private static final ConcurrentMap<Path, Object> DESTINATION_LOCKS = new ConcurrentHashMap<Path, Object>();
    private final YouTubeStreamResolver resolver;
    private final YouTubeMediaModels.HttpRequester requester;
    private final AudioDecoderRegistry registry;
    private final AudioFormatDetector detector = new AudioFormatDetector();
    private final long maximumBytes;
    private final LongSupplier clock;
    private final Semaphore downloadPermit = new Semaphore(1, true);
    private final AtomicInteger consecutiveRateLimitFailures = new AtomicInteger();
    private final AtomicLong nextRateLimitRetryAtMillis = new AtomicLong();
    // In-progress media bodies kept on disk so a rate-limited (403) download can resume where it
    // stopped instead of re-downloading. Keyed by video id.
    private final ConcurrentMap<String, MediaTransfer> pendingTransfers = new ConcurrentHashMap<String, MediaTransfer>();
    private static final Logger LOGGER = Logger.getLogger(JavaAudioDownloadBackend.class.getName());

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
        acquireDownloadPermit(token);
        try {
            // A waiting prefetch may have crossed into the cooldown while the active download was running.
            checkRateLimited();
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
        } finally {
            downloadPermit.release();
        }
    }

    private void acquireDownloadPermit(YouTubeMediaModels.CancellationToken token) throws IOException {
        while (true) {
            checkCancelled(token);
            try {
                if (downloadPermit.tryAcquire(1L, TimeUnit.SECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw new MediaException("YouTube audio download cancelled", interrupted);
            }
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
        LOGGER.log(
            Level.WARNING,
            "YouTube media edge returned HTTP 403; pausing media downloads for about {0}s (failure {1})",
            new Object[] { Long.toString(backoff / 1000L), Integer.toString(failures) });
    }

    private void resetRateLimit() {
        consecutiveRateLimitFailures.set(0);
        nextRateLimitRetryAtMillis.set(0L);
    }

    @Override
    public long nextRateLimitRetryAtMillis() {
        return nextRateLimitRetryAtMillis.get();
    }

    private Path downloadLockedOnce(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
        throws IOException {
        checkCancelled(token);
        YouTubeStreamResolver.ResolvedAudioCandidates resolved = resolver.resolveAudioCandidates(videoId);
        checkCancelled(token);
        Path resumed = resumePendingTransfer(videoId, destination, token);
        if (resumed != null) return resumed;
        Set<String> attemptedUrls = new HashSet<String>();
        List<IOException> failures = new ArrayList<IOException>();
        List<IOException> transportFailures = new ArrayList<IOException>();
        Path result = null;
        boolean primaryRateLimited = false;
        try {
            result = tryCandidates(
                videoId,
                destination,
                token,
                resolved.getPrimaryCandidates(),
                attemptedUrls,
                failures,
                transportFailures,
                true,
                true);
        } catch (RateLimitedCandidateFailure rateLimited) {
            primaryRateLimited = true;
        }
        if (result != null) return result;

        if (!primaryRateLimited && !transportFailures.isEmpty()) {
            checkCancelled(token);
            try {
                resolved = resolver.resolveAudioCandidates(videoId);
            } catch (IOException refreshFailure) {
                failures.add(refreshFailure);
                throw aggregateFailure(videoId, failures);
            }
            attemptedUrls.clear();
            transportFailures.clear();
            try {
                result = tryCandidates(
                    videoId,
                    destination,
                    token,
                    resolved.getPrimaryCandidates(),
                    attemptedUrls,
                    failures,
                    transportFailures,
                    false,
                    true);
            } catch (RateLimitedCandidateFailure rateLimited) {
                primaryRateLimited = true;
            }
            if (result != null) return result;
        }

        List<YouTubeMediaModels.ResolvedAudioStream> alternatives;
        try {
            alternatives = resolved.resolveAlternativeCandidates();
        } catch (IOException alternativeFailure) {
            failures.add(alternativeFailure);
            throw aggregateFailure(videoId, failures);
        }
        if (primaryRateLimited) {
            // A different client profile can mint a usable URL with the same textual address. Its request
            // still carries a different client context, so do not let the rejected primary URL suppress it.
            attemptedUrls.clear();
        }
        result = tryCandidates(
            videoId,
            destination,
            token,
            alternatives,
            attemptedUrls,
            failures,
            transportFailures,
            true,
            false);
        if (result != null) return result;
        throw aggregateFailure(videoId, failures);
    }

    private static boolean containsHttp403(Throwable failure) {
        if (failure == null) {
            return false;
        }
        if (failure instanceof YouTubeMediaModels.HttpStatusException
            && ((YouTubeMediaModels.HttpStatusException) failure).getStatusCode() == 403) {
            return true;
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

    private static CandidateTransportFailure transportFailure(String message, IOException failure) {
        if (isHttp403Status(failure)) {
            return new RateLimitedCandidateFailure(message, failure);
        }
        return new CandidateTransportFailure(message, failure);
    }

    private static boolean isHttp403Status(Throwable failure) {
        if (failure == null) return false;
        if (failure instanceof YouTubeMediaModels.HttpStatusException
            && ((YouTubeMediaModels.HttpStatusException) failure).getStatusCode() == 403) {
            return true;
        }
        if (failure.getMessage() != null && failure.getMessage()
            .contains("status 403")) {
            return true;
        }
        if (isHttp403Status(failure.getCause())) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (isHttp403Status(suppressed)) return true;
        }
        return false;
    }

    private Path tryCandidates(String videoId, Path destination, YouTubeMediaModels.CancellationToken token,
        List<YouTubeMediaModels.ResolvedAudioStream> candidates, Set<String> attemptedUrls, List<IOException> failures,
        List<IOException> transportFailures, boolean ranged, boolean stopOnRateLimit) throws IOException {
        for (YouTubeMediaModels.ResolvedAudioStream stream : candidates) {
            checkCancelled(token);
            if (!attemptedUrls.add(
                stream.getUrl()
                    .toExternalForm()))
                continue;
            try {
                return downloadCandidate(videoId, stream, destination, token, ranged, null);
            } catch (RateLimitedCandidateFailure failure) {
                failures.add(failure);
                transportFailures.add(failure);
                if (stopOnRateLimit) throw failure;
            } catch (CandidateTransportFailure failure) {
                failures.add(failure);
                transportFailures.add(failure);
            } catch (CandidateDecodeFailure failure) {
                failures.add(failure);
            }
        }
        return null;
    }

    private Path downloadCandidate(String videoId, YouTubeMediaModels.ResolvedAudioStream stream, Path destination,
        YouTubeMediaModels.CancellationToken token, boolean ranged, MediaTransfer pending) throws IOException {
        checkCancelled(token);
        if (stream.getExpiresAtMillis() <= clock.getAsLong()) {
            throw new CandidateDecodeFailure("YouTube stream URL expired before download");
        }
        WavFileSink sink = null;
        Path mediaTemp = pending != null ? pending.tempFile : null;
        String contentType = pending != null ? pending.contentType : "";
        long startOfAttempt = 0L;
        try {
            if (mediaTemp == null) {
                Path parent = destination.toAbsolutePath().getParent();
                String filePrefix = destination.getFileName().toString();
                mediaTemp = Files.createTempFile(parent, filePrefix, ".media.part");
            }
            startOfAttempt = Files.size(mediaTemp);

            // Phase 1: stream the media body to the scratch file in bounded ranges. Every 206 slice is
            // verified against the requested offset before it is appended, so a stale or shifted slice can
            // never corrupt the partial body that a later download attempt will resume from.
            TransferResult transfer = transferMediaBody(stream, mediaTemp, ranged, startOfAttempt, contentType, token);

            // Phase 2: decode the stored body and publish the cache entry atomically. The network stream is
            // fully closed by now, so a rate-limit window can no longer interleave between compressed reads
            // and PCM publication.
            byte[] prefixBytes;
            try (FileInputStream prefixInput = new FileInputStream(mediaTemp.toFile())) {
                prefixBytes = readPrefix(prefixInput, PREFIX_BYTES);
            }
            MediaFormat detected = detector.detect(transfer.contentType, prefixBytes);
            if (detected != stream.getFormat() || !registry.supports(detected)) {
                throw new CandidateDecodeFailure("Resolved stream format does not match the audio response");
            }
            checkCancelled(token);
            sink = new WavFileSink(destination, maximumBytes);
            DeferredFinishPcmSink deferred = new DeferredFinishPcmSink(sink, token);
            CountingInputStream counted;
            try (FileInputStream fileInput = new FileInputStream(mediaTemp.toFile())) {
                skipPrefixBytes(fileInput, prefixBytes.length);
                counted = new CountingInputStream(new BoundedInputStream(fileInput, transfer.declaredBytes - prefixBytes.length));
                InputStream input = new CancellationInputStream(counted, token);
                try {
                    registry.find(detected, new java.io.ByteArrayInputStream(prefixBytes), input)
                        .decode(
                            new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(prefixBytes), input),
                            deferred);
                    if (!deferred.isFinishRequested()
                        || prefixBytes.length + counted.getBytesRead() != transfer.declaredBytes) {
                        throw new MediaException("Audio response does not exactly match its declared body length");
                    }
                } catch (IOException failure) {
                    if (cancellationRequested(token) || deferred.hasDownstreamFailure()) throw failure;
                    if (isHttp403Status(failure)) {
                        throw new RateLimitedCandidateFailure("Audio candidate returned HTTP 403 (status 403)", failure);
                    }
                    throw new CandidateDecodeFailure("Audio candidate could not be decoded", failure);
                }
            }
            checkCancelled(token);
            deferred.commit();
            MediaTransfer leftover = pendingTransfers.remove(videoId);
            if (leftover != null && !leftover.tempFile.equals(mediaTemp)) {
                deleteQuietly(leftover.tempFile);
            }
            deleteQuietly(mediaTemp);
            return destination;
        } catch (IOException failure) {
            if (sink != null) {
                try {
                    sink.abort();
                } catch (IOException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            long transferSize = startOfAttempt;
            try {
                transferSize = mediaTemp == null ? 0L : Files.size(mediaTemp);
            } catch (IOException ignored) {
                // The scratch file is gone; there is nothing to resume from.
            }
            if (isHttp403Status(failure) && transferSize > startOfAttempt && transferSize <= maximumBytes) {
                // The rate-limit window opened mid-transfer: keep the verified prefix so the next attempt
                // resumes with one Range request instead of re-downloading and re-burning the IP budget.
                savePending(videoId, stream, contentType, mediaTemp, transferSize);
            } else {
                MediaTransfer current = pendingTransfers.get(videoId);
                if (current != null && current.tempFile.equals(mediaTemp)) {
                    // This attempt owned the pending body (a rejected or exhausted resume): the verified
                    // prefix can no longer be trusted, so the record and the file go together.
                    pendingTransfers.remove(videoId, current);
                }
                // A pending from an earlier attempt belongs to a different scratch file and stays valid.
                deleteQuietly(mediaTemp);
            }
            throw failure;
        }
    }

    private TransferResult transferMediaBody(YouTubeMediaModels.ResolvedAudioStream stream, Path mediaTemp,
        boolean ranged, long offset, String contentType,
        YouTubeMediaModels.CancellationToken token) throws IOException {
        long total = -1L;
        long chunkBytes = Math.min(RANGE_CHUNK_BYTES, maximumBytes);
        Map<String, String> baseHeaders = mediaHeaders(stream);
        while (true) {
            checkCancelled(token);
            Map<String, String> headers = new HashMap<String, String>(baseHeaders);
            if (ranged || offset > 0L) {
                long chunkEnd = Math.min(offset + chunkBytes - 1L, maximumBytes - 1L);
                headers.put("Range", rangeHeader(offset, chunkEnd));
            }
            YouTubeMediaModels.HttpResponse response;
            try {
                response = requester.get(
                    stream.getUrl(),
                    headers,
                    TIMEOUT_MILLIS,
                    maximumBytes,
                    YouTubeMediaModels.RedirectPolicy.MEDIA);
            } catch (IOException failure) {
                throw transportFailure("Unable to open YouTube audio candidate", failure);
            }
            long declared;
            boolean rangedResponse = false;
            long beforeResponse = offset;
            try (YouTubeMediaModels.HttpResponse responseResource = response) {
                if (responseResource.getStatusCode() == 403) {
                    throw new RateLimitedCandidateFailure(
                        "YouTube audio candidate returned HTTP 403 (status 403)",
                        new YouTubeMediaModels.HttpStatusException(403));
                }
                if (responseResource.getStatusCode() < 200 || responseResource.getStatusCode() >= 300
                    || !YouTubeStreamResolver.isSafeMediaUrl(responseResource.getUrl())) {
                    throw new CandidateTransportFailure("Audio candidate response is not trusted");
                }
                if (contentType.length() == 0L && responseResource.getContentType() != null) {
                    contentType = responseResource.getContentType();
                }
                declared = responseResource.getContentLength();
                if (responseResource.getStatusCode() == 206) {
                    long[] range = parseContentRange(responseResource.getContentRange());
                    if (range == null || range[0] != offset || range[1] < range[0] || range[1] >= range[2]
                        || range[2] > maximumBytes || declared != range[1] - range[0] + 1L) {
                        throw new CandidateTransportFailure("Audio candidate range response is not trusted");
                    }
                    total = range[2];
                    rangedResponse = true;
                } else {
                    if (declared < 0L || declared > maximumBytes) {
                        throw new CandidateTransportFailure("Audio candidate response exceeds its finite limits");
                    }
                    if (offset > 0L) {
                        // The server ignored the Range header: its complete body supersedes the partial file.
                        Files.write(mediaTemp, new byte[0]);
                        offset = 0L;
                    }
                    if (declared <= 0L) {
                        throw new CandidateTransportFailure("Audio candidate body is empty");
                    }
                    total = declared;
                }
                long bodyLimit = rangedResponse ? Math.min(declared, total - offset) : declared;
                try (
                    BoundedInputStream body = new BoundedInputStream(responseResource.getInputStream(), bodyLimit);
                    FileOutputStream output = new FileOutputStream(mediaTemp.toFile(), true)) {
                    byte[] buffer = new byte[READ_BUFFER_BYTES];
                    while (offset < total) {
                        checkCancelled(token);
                        int count = body.read(buffer);
                        if (count < 0) break;
                        output.write(buffer, 0, count);
                        offset += count;
                    }
                }
            }
            if (offset >= total) {
                return new TransferResult(total, contentType);
            }
            if (!rangedResponse || offset <= beforeResponse) {
                // A complete body is one-shot and a stalled range cannot loop safely: hand the partial body
                // to the decoder, which verifies the exact declared length before anything is published.
                return new TransferResult(total, contentType);
            }
            // A 206 slice ended early (a rate-limit window closing the socket, or a transport reset). The
            // prefix already on disk is verified and consistent, so the next iteration resumes it from the
            // new offset instead of restarting the transfer.
        }
    }

    private Path resumePendingTransfer(String videoId, Path destination, YouTubeMediaModels.CancellationToken token)
        throws IOException {
        MediaTransfer transfer = pendingTransfers.get(videoId);
        if (transfer == null) return null;
        if (transfer.expiresAtMillis <= clock.getAsLong()) {
            discardPending(videoId, transfer);
            return null;
        }
        long offset;
        try {
            offset = Files.size(transfer.tempFile);
        } catch (IOException failure) {
            discardPending(videoId, transfer);
            return null;
        }
        if (offset <= 0L || offset > maximumBytes) {
            discardPending(videoId, transfer);
            return null;
        }
        YouTubeMediaModels.ResolvedAudioStream stream;
        try {
            stream = new YouTubeMediaModels.ResolvedAudioStream(
                new URL(transfer.url),
                transfer.format,
                0,
                transfer.expiresAtMillis,
                transfer.visitorData);
        } catch (IOException failure) {
            discardPending(videoId, transfer);
            return null;
        }
        try {
            return downloadCandidate(videoId, stream, destination, token, true, transfer);
        } catch (IOException failure) {
            // downloadCandidate kept or discarded the pending body itself; a fresh candidate may retry next.
            return null;
        }
    }

    private void savePending(String videoId, YouTubeMediaModels.ResolvedAudioStream stream, String contentType,
        Path mediaTemp, long offset) {
        if (offset <= 0L || offset > maximumBytes || mediaTemp == null) {
            // No verified progress on this attempt: an existing pending from an earlier attempt is left
            // untouched, and there is nothing new to store.
            return;
        }
        String url = stream.getUrl().toExternalForm();
        MediaTransfer existing = pendingTransfers.get(videoId);
        if (existing != null && existing.url.equals(url)) {
            long existingSize;
            try {
                existingSize = Files.size(existing.tempFile);
            } catch (IOException failure) {
                existingSize = -1L;
            }
            if (existingSize >= offset) {
                // An earlier attempt already stored at least this much of the same URL: keep the larger
                // prefix and drop the smaller copy.
                deleteQuietly(mediaTemp);
                return;
            }
        }
        MediaTransfer replaced = pendingTransfers.put(videoId, new MediaTransfer(
            url,
            stream.getVisitorData(),
            contentType,
            stream.getFormat(),
            stream.getExpiresAtMillis(),
            mediaTemp,
            clock.getAsLong()));
        if (replaced != null && !replaced.tempFile.equals(mediaTemp)) {
            deleteQuietly(replaced.tempFile);
        }
        evictPendingTransfers();
        LOGGER.log(
            Level.INFO,
            "Kept {0} bytes of {1} on disk to resume after the YouTube rate-limit window clears",
            new Object[] { Long.toString(offset), videoId });
    }

    private void evictPendingTransfers() {
        List<Map.Entry<String, MediaTransfer>> entries =
            new ArrayList<Map.Entry<String, MediaTransfer>>(pendingTransfers.entrySet());
        while (entries.size() > MAX_PENDING_TRANSFERS) {
            Map.Entry<String, MediaTransfer> oldest = entries.get(0);
            for (Map.Entry<String, MediaTransfer> entry : entries) {
                if (entry.getValue().createdAtMillis < oldest.getValue().createdAtMillis) oldest = entry;
            }
            entries.remove(oldest);
            if (pendingTransfers.remove(oldest.getKey(), oldest.getValue())) {
                deleteQuietly(oldest.getValue().tempFile);
            }
        }
    }

    private void discardPending(String videoId, MediaTransfer transfer) {
        if (pendingTransfers.remove(videoId, transfer)) {
            deleteQuietly(transfer.tempFile);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A vanished scratch file is not an error.
        }
    }

    private static long[] parseContentRange(String contentRange) {
        if (contentRange == null) return null;
        String trimmed = contentRange.trim();
        if (trimmed.length() < 6 || !trimmed.regionMatches(true, 0, "bytes", 0, 5)) return null;
        int firstDash = trimmed.indexOf('-', 5);
        if (firstDash < 5) return null;
        int totalSlash = trimmed.indexOf('/', firstDash + 1);
        if (totalSlash < 0) return null;
        try {
            long start = Long.parseLong(trimmed.substring(5, firstDash).trim());
            long end = Long.parseLong(trimmed.substring(firstDash + 1, totalSlash).trim());
            long total = Long.parseLong(trimmed.substring(totalSlash + 1).trim());
            if (start < 0L || end < start || total <= 0L) return null;
            return new long[] { start, end, total };
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static final class TransferResult {

        final long declaredBytes;
        final String contentType;

        TransferResult(long declaredBytes, String contentType) {
            this.declaredBytes = declaredBytes;
            this.contentType = contentType;
        }
    }

    private static final class MediaTransfer {

        final String url;
        final String visitorData;
        final String contentType;
        final MediaFormat format;
        final long expiresAtMillis;
        final Path tempFile;
        final long createdAtMillis;

        MediaTransfer(String url, String visitorData, String contentType, MediaFormat format,
            long expiresAtMillis, Path tempFile, long createdAtMillis) {
            this.url = url;
            this.visitorData = visitorData;
            this.contentType = contentType;
            this.format = format;
            this.expiresAtMillis = expiresAtMillis;
            this.tempFile = tempFile;
            this.createdAtMillis = createdAtMillis;
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

    private static void skipPrefixBytes(InputStream input, long count) throws IOException {
        long skipped = 0L;
        byte[] scratch = new byte[4096];
        while (skipped < count) {
            int requested = (int) Math.min(scratch.length, count - skipped);
            int read = input.read(scratch, 0, requested);
            if (read < 0) break;
            skipped += read;
        }
    }

    private static byte[] readPrefix(InputStream input, int maximum) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[maximum];
        while (output.size() < maximum) {
            int count = input.read(buffer, output.size(), buffer.length - output.size());
            if (count < 0) break;
            output.write(buffer, 0, count);
        }
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

    private static class CandidateTransportFailure extends IOException {

        private CandidateTransportFailure(String message) {
            super(message);
        }

        private CandidateTransportFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class RateLimitedCandidateFailure extends CandidateTransportFailure {

        private RateLimitedCandidateFailure(String message) {
            super(message);
        }

        private RateLimitedCandidateFailure(String message, Throwable cause) {
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
