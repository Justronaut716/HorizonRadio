package com.horizonradio.server.media;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Downloads a freshly resolved YouTube stream through the Java decoder pipeline into an atomic WAV cache entry. */
public final class JavaAudioDownloadBackend implements YouTubeMediaModels.AudioDownloadBackend {

    private static final int TIMEOUT_MILLIS = 15000;
    private static final int PREFIX_BYTES = 44;
    private static final long RANGE_CHUNK_BYTES = 1024L * 1024L;
    private static final String MEDIA_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final ConcurrentMap<Path, Object> DESTINATION_LOCKS = new ConcurrentHashMap<Path, Object>();
    private final YouTubeStreamResolver resolver;
    private final YouTubeMediaModels.HttpRequester requester;
    private final AudioDecoderRegistry registry;
    private final AudioFormatDetector detector = new AudioFormatDetector();
    private final long maximumBytes;

    public JavaAudioDownloadBackend() { this(new YouTubeStreamResolver(), new YouTubeMediaModels.UrlConnectionHttpRequester(), new AudioDecoderRegistry(), 64L * 1024L * 1024L); }
    public JavaAudioDownloadBackend(YouTubeStreamResolver resolver, YouTubeMediaModels.HttpRequester requester, AudioDecoderRegistry registry, long maximumBytes) {
        if (resolver == null || requester == null || registry == null || maximumBytes <= 0L) throw new IllegalArgumentException("Download backend dependencies are required");
        this.resolver = resolver; this.requester = requester; this.registry = registry; this.maximumBytes = maximumBytes;
    }

    @Override
    public Path download(String videoId, Path destination, YouTubeMediaModels.CancellationToken token) throws IOException {
        if (token == null) throw new IllegalArgumentException("Cancellation token is required");
        if (destination == null) throw new IllegalArgumentException("WAV destination is required");
        Path lockPath = destination.toAbsolutePath().normalize();
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
        checkCancelled(token);
        YouTubeMediaModels.ResolvedAudioStream stream = resolver.resolveAudio(videoId);
        checkCancelled(token);
        if (stream.getExpiresAtMillis() <= System.currentTimeMillis()) throw new MediaException("YouTube stream URL expired before download");
        WavFileSink sink = null;
        MediaRangeInputStream rangeInput = null;
        long chunkBytes = Math.min(RANGE_CHUNK_BYTES, maximumBytes);
        Map<String, String> mediaHeaders = mediaHeaders();
        if (stream.getVisitorData().length() > 0) {
            mediaHeaders.put("X-Goog-Visitor-Id", stream.getVisitorData());
        }
        Map<String, String> firstHeaders = new HashMap<String, String>(mediaHeaders);
        firstHeaders.put("Range", rangeHeader(0L, chunkBytes - 1L));
        try (YouTubeMediaModels.HttpResponse response = requester.get(
            stream.getUrl(),
            firstHeaders,
            TIMEOUT_MILLIS,
            maximumBytes,
            YouTubeMediaModels.RedirectPolicy.MEDIA)) {
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300
                || !YouTubeStreamResolver.isSafeMediaUrl(response.getUrl())) {
                throw new MediaException("Audio response exceeds its finite limits");
            }
            InputStream compressed;
            long expectedLength;
            if (response.getStatusCode() == 206) {
                rangeInput = MediaRangeInputStream.open(
                    stream.getUrl(), requester, mediaHeaders, TIMEOUT_MILLIS, maximumBytes, chunkBytes, response);
                compressed = rangeInput;
                expectedLength = rangeInput.getTotalBytes();
            } else {
                if (response.getContentLength() < 0L || response.getContentLength() > maximumBytes) {
                    throw new MediaException("Audio response exceeds its finite limits");
                }
                compressed = new BoundedInputStream(response.getInputStream(), response.getContentLength());
                expectedLength = response.getContentLength();
            }
            if (!YouTubeStreamResolver.isSafeMediaUrl(response.getUrl())) throw new MediaException("Audio response redirected to an unsafe URL");
            CountingInputStream counted = new CountingInputStream(compressed);
            InputStream input = new CancellationInputStream(counted, token);
            byte[] prefix = readPrefix(input, PREFIX_BYTES);
            MediaFormat detected = detector.detect(response.getContentType(), prefix);
            if (detected != stream.getFormat() || !registry.supports(detected)) throw new MediaException("Resolved stream format does not match the audio response");
            sink = new WavFileSink(destination, maximumBytes);
            DeferredFinishPcmSink deferred = new DeferredFinishPcmSink(sink, token);
            registry.find(detected, new java.io.ByteArrayInputStream(prefix), input).decode(
                new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(prefix), input), deferred);
            if (!deferred.isFinishRequested() || counted.getBytesRead() != expectedLength) {
                throw new MediaException("Audio response does not exactly match its declared body length");
            }
            checkCancelled(token);
            deferred.commit();
            return destination;
        } catch (IOException exception) {
            if (sink != null) sink.abort();
            throw exception;
        } finally {
            if (rangeInput != null) rangeInput.close();
        }
    }

    @Override public boolean isReady() { return true; }

    private static byte[] readPrefix(InputStream input, int maximum) throws IOException { java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[maximum]; int count = input.read(buffer); if (count > 0) output.write(buffer, 0, count); return output.toByteArray(); }
    private static void checkCancelled(YouTubeMediaModels.CancellationToken token) throws MediaException { if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new MediaException("YouTube audio download cancelled"); }
    private static Map<String, String> mediaHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept", "*/*");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Referer", "https://www.youtube.com/");
        headers.put("User-Agent", MEDIA_USER_AGENT);
        return headers;
    }
    private static String rangeHeader(long start, long end) { return "bytes=" + start + "-" + end; }
    private static final class CancellationInputStream extends FilterInputStream { private final YouTubeMediaModels.CancellationToken token; private CancellationInputStream(InputStream input, YouTubeMediaModels.CancellationToken token) { super(input); this.token = token; } @Override public int read() throws IOException { checkCancelled(token); return super.read(); } @Override public int read(byte[] bytes, int offset, int length) throws IOException { checkCancelled(token); return super.read(bytes, offset, length); } }
    private static final class CountingInputStream extends FilterInputStream { private long bytesRead; private CountingInputStream(InputStream input) { super(input); } @Override public int read() throws IOException { int value = super.read(); if (value >= 0) bytesRead++; return value; } @Override public int read(byte[] bytes, int offset, int length) throws IOException { int count = super.read(bytes, offset, length); if (count > 0) bytesRead += count; return count; } private long getBytesRead() { return bytesRead; } }
    private static final class DeferredFinishPcmSink implements PcmSink { private final PcmSink downstream; private final YouTubeMediaModels.CancellationToken token; private boolean finishRequested; private DeferredFinishPcmSink(PcmSink downstream, YouTubeMediaModels.CancellationToken token) { this.downstream = downstream; this.token = token; } @Override public void write(byte[] data, int offset, int length) throws IOException { checkCancelled(token); downstream.write(data, offset, length); } @Override public void finish() throws IOException { checkCancelled(token); finishRequested = true; } @Override public void abort() throws IOException { downstream.abort(); } @Override public void close() throws IOException { abort(); } private boolean isFinishRequested() { return finishRequested; } private void commit() throws IOException { token.finish(downstream); } }
}
