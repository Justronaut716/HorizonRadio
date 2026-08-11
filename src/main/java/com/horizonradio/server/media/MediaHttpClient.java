package com.horizonradio.server.media;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.Map;

/**
 * Opens bounded HTTP media responses using the JDK HTTP stack.
 */
public final class MediaHttpClient {

    public static final long DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024L * 1024L;

    private final long maximumResponseBytes;

    public MediaHttpClient() {
        this(DEFAULT_MAX_RESPONSE_BYTES);
    }

    public MediaHttpClient(long maximumResponseBytes) {
        if (maximumResponseBytes <= 0L) {
            throw new IllegalArgumentException("Maximum response bytes must be positive");
        }
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public MediaHttpResponse open(URL url, Map<String, String> headers, int timeoutMillis) throws IOException {
        if (url == null) {
            throw new NullPointerException("url");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }

        URLConnection urlConnection = url.openConnection();
        if (!(urlConnection instanceof HttpURLConnection)) {
            throw new MediaException("Media URL must use HTTP or HTTPS");
        }

        HttpURLConnection connection = (HttpURLConnection) urlConnection;
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        for (Map.Entry<String, String> header : safeHeaders(headers).entrySet()) {
            if (header.getKey() == null || header.getValue() == null) {
                connection.disconnect();
                throw new IllegalArgumentException("HTTP headers must not contain null keys or values");
            }
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        int statusCode;
        try {
            statusCode = connection.getResponseCode();
        } catch (IOException exception) {
            connection.disconnect();
            throw exception;
        }
        if (statusCode < HttpURLConnection.HTTP_OK || statusCode >= 300) {
            closeQuietly(connection.getErrorStream());
            connection.disconnect();
            throw new MediaException("HTTP media request failed with status " + statusCode);
        }

        long contentLength = connection.getContentLengthLong();
        if (contentLength < 0L) {
            closeInputAndDisconnect(connection);
            throw new MediaException("HTTP media response must include a Content-Length");
        }
        if (contentLength > maximumResponseBytes) {
            closeInputAndDisconnect(connection);
            throw new MediaException("HTTP media response exceeds the configured byte limit");
        }

        try {
            InputStream body = connection.getInputStream();
            return new MediaHttpResponse(
                connection,
                new BoundedInputStream(body, maximumResponseBytes),
                statusCode,
                connection.getContentType(),
                contentLength);
        } catch (IOException exception) {
            connection.disconnect();
            throw exception;
        }
    }

    private static Map<String, String> safeHeaders(Map<String, String> headers) {
        return headers == null ? Collections.<String, String>emptyMap() : headers;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The original HTTP failure is more useful than an error-stream close failure.
        }
    }

    private static void closeInputAndDisconnect(HttpURLConnection connection) {
        try {
            closeQuietly(connection.getInputStream());
        } catch (IOException ignored) {
            // The response has already been rejected; disconnect still releases it.
        } finally {
            connection.disconnect();
        }
    }

    public static final class MediaHttpResponse implements Closeable {

        private final HttpURLConnection connection;
        private final BoundedInputStream input;
        private final int statusCode;
        private final String contentType;
        private final long contentLength;

        private MediaHttpResponse(HttpURLConnection connection, BoundedInputStream input, int statusCode,
            String contentType, long contentLength) {
            this.connection = connection;
            this.input = input;
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.contentLength = contentLength;
        }

        public InputStream getInputStream() {
            return input;
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

        @Override
        public void close() throws IOException {
            try {
                input.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
