package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a media object through bounded, sequential HTTP byte ranges. */
final class MediaRangeInputStream extends InputStream {

    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$",
        Pattern.CASE_INSENSITIVE);

    private final URL url;
    private final YouTubeMediaModels.HttpRequester requester;
    private final Map<String, String> baseHeaders;
    private final int timeoutMillis;
    private final long maximumBytes;
    private final long chunkBytes;
    private final long totalBytes;
    private long position;
    private long currentRemaining;
    private YouTubeMediaModels.HttpResponse currentResponse;
    private InputStream currentInput;
    private boolean closed;

    private MediaRangeInputStream(URL url, YouTubeMediaModels.HttpRequester requester,
        Map<String, String> baseHeaders, int timeoutMillis, long maximumBytes, long chunkBytes,
        long totalBytes, YouTubeMediaModels.HttpResponse firstResponse, Range firstRange) throws IOException {
        this.url = url;
        this.requester = requester;
        this.baseHeaders = new HashMap<String, String>(baseHeaders);
        this.timeoutMillis = timeoutMillis;
        this.maximumBytes = maximumBytes;
        this.chunkBytes = chunkBytes;
        this.totalBytes = totalBytes;
        setCurrent(firstResponse, firstRange);
    }

    static MediaRangeInputStream open(URL url, YouTubeMediaModels.HttpRequester requester,
        Map<String, String> baseHeaders, int timeoutMillis, long maximumBytes, long chunkBytes,
        YouTubeMediaModels.HttpResponse firstResponse) throws IOException {
        if (url == null || requester == null || baseHeaders == null || timeoutMillis <= 0
            || maximumBytes <= 0L || chunkBytes <= 0L || firstResponse == null) {
            throw new IllegalArgumentException("Range stream dependencies must be valid");
        }
        Range first = range(firstResponse, 0L, chunkBytes, -1L, maximumBytes);
        return new MediaRangeInputStream(url, requester, baseHeaders, timeoutMillis, maximumBytes, chunkBytes,
            first.totalBytes, firstResponse, first);
    }

    long getTotalBytes() {
        return totalBytes;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("Invalid buffer range");
        }
        if (length == 0) return 0;
        ensureOpen();
        while (position < totalBytes) {
            if (currentRemaining == 0L) openNextRange();
            int requested = (int) Math.min((long) length, currentRemaining);
            int count = currentInput.read(bytes, offset, requested);
            if (count < 0) throw new MediaException("HTTP media range body is truncated");
            if (count == 0) continue;
            position += count;
            currentRemaining -= count;
            if (currentRemaining == 0L) closeCurrent();
            return count;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        closeCurrent();
    }

    private void openNextRange() throws IOException {
        long end = Math.min(totalBytes - 1L, position + chunkBytes - 1L);
        Map<String, String> headers = new HashMap<String, String>(baseHeaders);
        headers.put("Range", rangeHeader(position, end));
        YouTubeMediaModels.HttpResponse response = requester.get(
            url,
            headers,
            timeoutMillis,
            maximumBytes,
            YouTubeMediaModels.RedirectPolicy.MEDIA);
        try {
            Range next = range(response, position, chunkBytes, totalBytes, maximumBytes);
            setCurrent(response, next);
        } catch (IOException exception) {
            response.close();
            throw exception;
        }
    }

    private void setCurrent(YouTubeMediaModels.HttpResponse response, Range range) throws IOException {
        if (response.getContentLength() != range.length()) {
            throw new MediaException("HTTP media range length does not match Content-Range");
        }
        currentResponse = response;
        currentInput = new BoundedInputStream(response.getInputStream(), response.getContentLength());
        currentRemaining = response.getContentLength();
    }

    private void closeCurrent() throws IOException {
        IOException failure = null;
        if (currentInput != null) {
            try {
                currentInput.close();
            } catch (IOException exception) {
                failure = exception;
            }
            currentInput = null;
        }
        if (currentResponse != null) {
            try {
                currentResponse.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            currentResponse = null;
        }
        currentRemaining = 0L;
        if (failure != null) throw failure;
    }

    private void ensureOpen() throws MediaException {
        if (closed) throw new MediaException("HTTP media range stream is closed");
    }

    private static Range range(YouTubeMediaModels.HttpResponse response, long expectedStart,
        long requestedChunkBytes, long expectedTotal, long maximumBytes) throws IOException {
        if (response == null || response.getStatusCode() != 206
            || !YouTubeStreamResolver.isSafeMediaUrl(response.getUrl())
            || response.getContentLength() <= 0L || response.getContentLength() > maximumBytes) {
            throw new MediaException("HTTP media range response is not trusted");
        }
        String value = response.getContentRange();
        long start;
        long end;
        long total;
        if (value == null || value.length() == 0) {
            start = expectedStart;
            end = start + response.getContentLength() - 1L;
            total = expectedTotal > 0L ? expectedTotal : end + 1L;
            if (expectedTotal <= 0L && response.getContentLength() >= requestedChunkBytes) {
                throw new MediaException("HTTP media range response has no total length");
            }
        } else {
            Matcher matcher = CONTENT_RANGE.matcher(value.trim());
            if (!matcher.matches()) throw new MediaException("HTTP media response has an invalid Content-Range");
            try {
                start = Long.parseLong(matcher.group(1));
                end = Long.parseLong(matcher.group(2));
                total = Long.parseLong(matcher.group(3));
            } catch (NumberFormatException exception) {
                throw new MediaException("HTTP media response has an invalid Content-Range", exception);
            }
        }
        if (start != expectedStart || end < start || total <= 0L || end >= total
            || total > maximumBytes || end - start + 1L > requestedChunkBytes) {
            throw new MediaException("HTTP media response has an invalid byte range");
        }
        if (expectedTotal > 0L && total != expectedTotal) {
            throw new MediaException("HTTP media response changed its total byte length");
        }
        return new Range(start, end, total);
    }

    private static String rangeHeader(long start, long end) {
        return "bytes=" + start + "-" + end;
    }

    private static final class Range {
        private final long start;
        private final long end;
        private final long totalBytes;

        private Range(long start, long end, long totalBytes) {
            this.start = start;
            this.end = end;
            this.totalBytes = totalBytes;
        }

        private long length() {
            return end - start + 1L;
        }
    }
}
