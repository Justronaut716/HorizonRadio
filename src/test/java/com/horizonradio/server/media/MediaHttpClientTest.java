package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;

import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

public class MediaHttpClientTest {

    @Test
    public void rejectsUnknownLengthResponsesBeforeExposingTheirBody() throws Exception {
        TrackingHttpURLConnection connection = new TrackingHttpURLConnection(200, -1L, new byte[] { 1 });
        MediaHttpClient client = new MediaHttpClient(4L);

        assertOpenFails(client, connection, "Content-Length");
        assertTrue(connection.input.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    public void acceptsResponsesWithAKnownLengthAtTheConfiguredLimit() throws Exception {
        TrackingHttpURLConnection connection = new TrackingHttpURLConnection(200, 4L, new byte[] { 0, 1, 2, 3 });
        MediaHttpClient client = new MediaHttpClient(4L);

        MediaHttpClient.MediaHttpResponse response = client.open(urlFor(connection), Collections.<String, String>emptyMap(), 1000);
        try {
            byte[] bytes = new byte[4];
            assertEquals(4, response.getInputStream().read(bytes));
            assertEquals(-1, response.getInputStream().read());
        } finally {
            response.close();
        }

        assertTrue(connection.input.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    public void rejectsDeclaredLengthAboveTheConfiguredLimitAndReleasesIt() throws Exception {
        TrackingHttpURLConnection connection = new TrackingHttpURLConnection(200, 5L, new byte[] { 0, 1, 2, 3, 4 });

        assertOpenFails(new MediaHttpClient(4L), connection, "exceeds");

        assertTrue(connection.input.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    public void closesTheErrorStreamForNon2xxResponses() throws Exception {
        TrackingHttpURLConnection connection = new TrackingHttpURLConnection(404, 0L, new byte[0]);
        connection.error = new CloseTrackingInputStream(new byte[] { 9 });

        assertOpenFails(new MediaHttpClient(), connection, "404");
        assertTrue(connection.error.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    public void responseCloseClosesInputAndDisconnects() throws Exception {
        TrackingHttpURLConnection connection = new TrackingHttpURLConnection(200, 1L, new byte[] { 1 });
        MediaHttpClient.MediaHttpResponse response = new MediaHttpClient().open(
            urlFor(connection), Collections.<String, String>emptyMap(), 1000);

        response.close();

        assertTrue(connection.input.closed);
        assertTrue(connection.disconnected);
    }

    @Test
    public void followsRedirectsBeforeOpeningAKnownLengthResponse() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/audio");
            exchange.sendResponseHeaders(302, -1L);
            exchange.close();
        });
        server.createContext("/audio", exchange -> {
            byte[] body = new byte[] { 7 };
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URL redirect = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            MediaHttpClient.MediaHttpResponse response = new MediaHttpClient().open(
                redirect, Collections.<String, String>emptyMap(), 1000);
            try {
                assertEquals(7, response.getInputStream().read());
            } finally {
                response.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsZeroAndNegativeTimeouts() throws Exception {
        MediaHttpClient client = new MediaHttpClient();
        TrackingHttpURLConnection connection = new TrackingHttpURLConnection(200, 0L, new byte[0]);
        try {
            client.open(urlFor(connection), Collections.<String, String>emptyMap(), 0);
            fail("Expected zero timeout to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
        try {
            client.open(urlFor(connection), Collections.<String, String>emptyMap(), -1);
            fail("Expected negative timeout to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
    }

    private static void assertOpenFails(MediaHttpClient client, TrackingHttpURLConnection connection, String messagePart)
        throws Exception {
        try {
            client.open(urlFor(connection), Collections.<String, String>emptyMap(), 1000);
            fail("Expected HTTP media request to fail");
        } catch (MediaException expected) {
            assertTrue(expected.getMessage().contains(messagePart));
        }
    }

    private static URL urlFor(final TrackingHttpURLConnection connection) throws Exception {
        return new URL(null, "test://media/response", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return connection;
            }
        });
    }

    private static final class TrackingHttpURLConnection extends HttpURLConnection {

        private final CloseTrackingInputStream input;
        private final int statusCode;
        private final long contentLength;
        private CloseTrackingInputStream error;
        private boolean disconnected;

        private TrackingHttpURLConnection(int statusCode, long contentLength, byte[] body) throws Exception {
            super(new URL("http://media.test/response"));
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            input = new CloseTrackingInputStream(body);
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public String getContentType() {
            return "audio/test";
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return error;
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
