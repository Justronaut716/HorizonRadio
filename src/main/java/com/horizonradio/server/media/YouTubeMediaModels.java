package com.horizonradio.server.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/** Small transport and value types shared by the finite YouTube download path. */
public final class YouTubeMediaModels {

    private YouTubeMediaModels() {}

    /** Allows IP-bound YouTube media URLs to use the same dual-stack route as player resolution. */
    public static void preferIpv6ForClientMedia() {
        if ("true".equalsIgnoreCase(System.getProperty("java.net.preferIPv4Stack"))) {
            System.setProperty("java.net.preferIPv4Stack", "false");
            System.setProperty("java.net.preferIPv6Addresses", "true");
        }
    }

    public interface CancellationToken {

        boolean isCancelled();

        /**
         * Checks cancellation and publishes one completed PCM sink as one operation when overridden by an owner token.
         */
        default void finish(PcmSink sink) throws IOException {
            if (sink == null) {
                throw new IllegalArgumentException("PCM sink is required");
            }
            if (isCancelled() || Thread.currentThread()
                .isInterrupted()) {
                throw new MediaException("YouTube audio download cancelled");
            }
            sink.finish();
        }
    }

    public interface AudioDownloadBackend {

        Path download(String videoId, Path destination, CancellationToken token) throws IOException;

        boolean isReady();

        /** Earliest time a new download may be attempted; 0 when not rate-limited. */
        default long nextRateLimitRetryAtMillis() {
            return 0L;
        }
    }

    public interface HttpRequester {

        HttpResponse post(URL url, Map<String, String> headers, byte[] body, int timeoutMillis, long maximumBytes)
            throws IOException;

        HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis, long maximumBytes) throws IOException;

        default HttpResponse post(URL url, Map<String, String> headers, byte[] body, int timeoutMillis,
            long maximumBytes, RedirectPolicy redirectPolicy) throws IOException {
            return post(url, headers, body, timeoutMillis, maximumBytes);
        }

        default HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis, long maximumBytes,
            RedirectPolicy redirectPolicy) throws IOException {
            return get(url, headers, timeoutMillis, maximumBytes);
        }
    }

    /** Preserves the remote HTTP status so callers can distinguish a rate limit from other media failures. */
    public static final class HttpStatusException extends MediaException {

        private final int statusCode;

        public HttpStatusException(int statusCode) {
            super("HTTP request failed with status " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    public enum RedirectPolicy {
        INNER_TUBE,
        MEDIA
    }

    interface ConnectionOpener {

        HttpURLConnection open(URL url) throws IOException;
    }

    public static final class HttpResponse implements Closeable {

        private final URL url;
        private final int statusCode;
        private final String contentType;
        private final long contentLength;
        private final String contentRange;
        private final InputStream input;

        public HttpResponse(URL url, int statusCode, String contentType, long contentLength, InputStream input) {
            this(url, statusCode, contentType, contentLength, input, null);
        }

        public HttpResponse(URL url, int statusCode, String contentType, long contentLength, InputStream input,
            String contentRange) {
            if (url == null || input == null) {
                throw new IllegalArgumentException("HTTP response URL and body are required");
            }
            this.url = url;
            this.statusCode = statusCode;
            this.contentType = contentType == null ? "" : contentType;
            this.contentLength = contentLength;
            this.input = input;
            this.contentRange = contentRange == null ? "" : contentRange;
        }

        public URL getUrl() {
            return url;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getContentType() {
            return contentType;
        }

        public long getContentLength() {
            return contentLength;
        }

        public String getContentRange() {
            return contentRange;
        }

        public InputStream getInputStream() {
            return input;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    public static final class ResolvedAudioStream {

        private final URL url;
        private final MediaFormat format;
        private final int bitrate;
        private final long expiresAtMillis;
        private final String visitorData;
        private final String userAgent;

        public ResolvedAudioStream(URL url, MediaFormat format, int bitrate, long expiresAtMillis) {
            this(url, format, bitrate, expiresAtMillis, "", "");
        }

        public ResolvedAudioStream(URL url, MediaFormat format, int bitrate, long expiresAtMillis, String visitorData) {
            this(url, format, bitrate, expiresAtMillis, visitorData, "");
        }

        public ResolvedAudioStream(URL url, MediaFormat format, int bitrate, long expiresAtMillis, String visitorData,
            String userAgent) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
            this.expiresAtMillis = expiresAtMillis;
            this.visitorData = visitorData == null ? "" : visitorData;
            this.userAgent = userAgent == null ? "" : userAgent;
        }

        public URL getUrl() {
            return url;
        }

        public MediaFormat getFormat() {
            return format;
        }

        public int getBitrate() {
            return bitrate;
        }

        public long getExpiresAtMillis() {
            return expiresAtMillis;
        }

        public String getVisitorData() {
            return visitorData;
        }

        public String getUserAgent() {
            return userAgent;
        }
    }

    /** JDK-only requester used in production; tests inject deterministic responders. */
    public static final class UrlConnectionHttpRequester implements HttpRequester {

        private static final int MAX_REDIRECTS = 3;
        private final ConnectionOpener connectionOpener;

        public UrlConnectionHttpRequester() {
            this(new ConnectionOpener() {

                @Override
                public HttpURLConnection open(URL url) throws IOException {
                    return (HttpURLConnection) url.openConnection();
                }
            });
        }

        UrlConnectionHttpRequester(ConnectionOpener connectionOpener) {
            if (connectionOpener == null) {
                throw new IllegalArgumentException("Connection opener is required");
            }
            this.connectionOpener = connectionOpener;
        }

        @Override
        public HttpResponse post(URL url, Map<String, String> headers, byte[] body, int timeoutMillis,
            long maximumBytes) throws IOException {
            return post(url, headers, body, timeoutMillis, maximumBytes, RedirectPolicy.INNER_TUBE);
        }

        @Override
        public HttpResponse post(URL url, Map<String, String> headers, byte[] body, int timeoutMillis,
            long maximumBytes, RedirectPolicy redirectPolicy) throws IOException {
            return open(
                url,
                "POST",
                headers,
                body == null ? new byte[0] : body,
                timeoutMillis,
                maximumBytes,
                redirectPolicy);
        }

        @Override
        public HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis, long maximumBytes)
            throws IOException {
            return get(url, headers, timeoutMillis, maximumBytes, RedirectPolicy.MEDIA);
        }

        @Override
        public HttpResponse get(URL url, Map<String, String> headers, int timeoutMillis, long maximumBytes,
            RedirectPolicy redirectPolicy) throws IOException {
            return open(url, "GET", headers, null, timeoutMillis, maximumBytes, redirectPolicy);
        }

        private HttpResponse open(URL initial, String method, Map<String, String> headers, byte[] body,
            int timeoutMillis, long maximumBytes, RedirectPolicy redirectPolicy) throws IOException {
            if (initial == null || redirectPolicy == null || timeoutMillis <= 0 || maximumBytes <= 0L) {
                throw new IllegalArgumentException("HTTP URL, timeout, and limit must be positive");
            }
            URL current = initial;
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                validateRedirectTarget(current, redirectPolicy);
                HttpURLConnection connection = connectionOpener.open(current);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);
                connection.setUseCaches(false);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> entry : (headers == null ? Collections.<String, String>emptyMap()
                    : headers).entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body);
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.length() == 0 || redirect == MAX_REDIRECTS) {
                        throw new MediaException("Unsafe or excessive HTTP redirect");
                    }
                    URL next = new URL(current, location);
                    validateRedirectTarget(next, redirectPolicy);
                    current = next;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    InputStream error = connection.getErrorStream();
                    if (error != null) error.close();
                    connection.disconnect();
                    throw new HttpStatusException(status);
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength < 0L) {
                    if (redirectPolicy != RedirectPolicy.INNER_TUBE) {
                        connection.disconnect();
                        throw new MediaException("HTTP response exceeds a finite byte limit");
                    }
                    return bufferUnknownLengthResponse(current, connection, status, maximumBytes);
                }
                if (contentLength > maximumBytes) {
                    connection.disconnect();
                    throw new MediaException("HTTP response exceeds a finite byte limit");
                }
                final HttpURLConnection responseConnection = connection;
                return new HttpResponse(
                    current,
                    status,
                    connection.getContentType(),
                    contentLength,
                    new FilterInputStream(new BoundedInputStream(connection.getInputStream(), maximumBytes)) {

                        @Override
                        public void close() throws IOException {
                            try {
                                super.close();
                            } finally {
                                responseConnection.disconnect();
                            }
                        }
                    },
                    connection.getHeaderField("Content-Range"));
            }
            throw new MediaException("Too many HTTP redirects");
        }

        private static HttpResponse bufferUnknownLengthResponse(URL url, HttpURLConnection connection, int status,
            long maximumBytes) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (total > maximumBytes - count) {
                        throw new MediaException("HTTP response exceeds a finite byte limit");
                    }
                    output.write(buffer, 0, count);
                    total += count;
                }
                return new HttpResponse(
                    url,
                    status,
                    connection.getContentType(),
                    total,
                    new ByteArrayInputStream(output.toByteArray()));
            } finally {
                connection.disconnect();
            }
        }

        private static void validateRedirectTarget(URL url, RedirectPolicy redirectPolicy) throws MediaException {
            if (url == null || !"https".equalsIgnoreCase(url.getProtocol())
                || url.getUserInfo() != null
                || url.getHost() == null
                || url.getHost()
                    .length() == 0) {
                throw new MediaException("Redirect target must be a safe HTTPS URL");
            }
            String host = url.getHost()
                .toLowerCase(java.util.Locale.ROOT);
            boolean allowed = redirectPolicy == RedirectPolicy.INNER_TUBE ? YouTubeUrlParser.isYouTubeHost(host)
                : YouTubeStreamResolver.isSafeMediaUrl(url);
            if (!allowed) {
                throw new MediaException("Redirect target is outside the trusted media hosts");
            }
        }
    }
}
