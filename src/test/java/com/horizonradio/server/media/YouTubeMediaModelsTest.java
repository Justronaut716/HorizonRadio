package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class YouTubeMediaModelsTest {

    @Test
    public void letsClientMediaUseIpv6WhenMinecraftForcesIpv4Stack() {
        String previousPreferIpv4Stack = System.getProperty("java.net.preferIPv4Stack");
        String previousPreferIpv6Addresses = System.getProperty("java.net.preferIPv6Addresses");
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");

            YouTubeMediaModels.preferIpv6ForClientMedia();

            assertEquals("false", System.getProperty("java.net.preferIPv4Stack"));
            assertEquals("true", System.getProperty("java.net.preferIPv6Addresses"));
        } finally {
            restoreSystemProperty("java.net.preferIPv4Stack", previousPreferIpv4Stack);
            restoreSystemProperty("java.net.preferIPv6Addresses", previousPreferIpv6Addresses);
        }
    }

    @Test
    public void acceptsUnknownLengthInnerTubeResponsesWhenTheBodyStaysWithinTheLimit() throws Exception {
        URL source = new URL("https://www.youtube.com/youtubei/v1/player");
        UnknownLengthConnection connection = new UnknownLengthConnection(source, new byte[] { '{', '}' });
        YouTubeMediaModels.UrlConnectionHttpRequester requester = new YouTubeMediaModels.UrlConnectionHttpRequester(
            url -> connection);

        YouTubeMediaModels.HttpResponse response = requester.get(
            source,
            Collections.<String, String>emptyMap(),
            1000,
            2L,
            YouTubeMediaModels.RedirectPolicy.INNER_TUBE);
        try {
            assertEquals(2L, response.getContentLength());
            assertEquals(
                '{',
                response.getInputStream()
                    .read());
            assertEquals(
                '}',
                response.getInputStream()
                    .read());
            assertEquals(
                -1,
                response.getInputStream()
                    .read());
        } finally {
            response.close();
        }
        assertEquals(true, connection.disconnected);
    }

    @Test
    public void rejectsUnsafeMediaRedirectBeforeOpeningItsTarget() throws Exception {
        URL source = new URL("https://r1.googlevideo.com/videoplayback");
        TrackingRedirectConnection redirect = new TrackingRedirectConnection(source, "https://127.0.0.1/private");
        List<URL> opened = new ArrayList<URL>();
        YouTubeMediaModels.UrlConnectionHttpRequester requester = new YouTubeMediaModels.UrlConnectionHttpRequester(
            url -> {
                opened.add(url);
                if (opened.size() > 1) {
                    throw new AssertionError("Unsafe redirect target was contacted");
                }
                return redirect;
            });

        try {
            requester.get(
                source,
                Collections.<String, String>emptyMap(),
                1000,
                32L,
                YouTubeMediaModels.RedirectPolicy.MEDIA);
            fail("Expected unsafe redirect to be rejected");
        } catch (MediaException expected) {
            // The policy is checked before the next connection is opened.
        }
        assertEquals(1, opened.size());
    }

    private static final class TrackingRedirectConnection extends HttpURLConnection {

        private final String location;

        private TrackingRedirectConnection(URL url, String location) {
            super(url);
            this.location = location;
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}

        @Override
        public int getResponseCode() {
            return 302;
        }

        @Override
        public String getHeaderField(String name) {
            return "Location".equals(name) ? location : null;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    private static final class UnknownLengthConnection extends HttpURLConnection {

        private final InputStream input;
        private boolean disconnected;

        private UnknownLengthConnection(URL url, byte[] body) {
            super(url);
            input = new ByteArrayInputStream(body);
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
        public void connect() {}

        @Override
        public int getResponseCode() {
            return 200;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public String getContentType() {
            return "application/json";
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
