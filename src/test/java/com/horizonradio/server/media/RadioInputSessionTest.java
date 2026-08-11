package com.horizonradio.server.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

public class RadioInputSessionTest {

    private static final byte[] LIVE_MP3 = Base64.getDecoder().decode(
        "//tAwAAAAAAAAAAAAAAAAAAAAAAASW5mbwAAAA8AAAADAAADKAB7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3t7e3u9vb29vb29vb29vb29vb29vb29vb29vb29vb29vb29vb3///////////////////////////////////////////8AAAAATGF2YzYzLjEuAAAAAAAAAAAAAAAAJAKjAAAAAAAAAyiCLLOAAAAAAAD/+1DEAAAKLEMuVZSAAX0VaLc20AAHqsOOM4XTZPGhwQgcWB9eHxgcRgQeieWzLloPqDsTa/D4YFAUBAMCgkQMTmjRo2wfeIAQBDE4P8EHTnT5zl/Ocv7un3cuD4Pg+HwQDEoAwf0gAAeaAQCAYCgYCgUDgA0KBNpABgMRIDCSI04PcIFdF0PJKE0smMjJGhdfrYTUFd8FZE+Ey/EuJEeo4f8dwwwlxIj1/8kTIvF4xLv/5dMi8XkS6Xf4iCoKiI9/wV//73JEABQe2WyNuMpZ//tSxAUAB1QhPb3BADEPg6U+uhAESkKwhuRo");
    private static final byte[] LIVE_AAC = Base64.getDecoder().decode(
        "//FQQCD//NwATGF2YzYzLjEuMTAwAAJgrFupUHImpXj14/euuJbVSWqWlSTJO4OhAQYVcvF3Gukta9peY8G7Y87q+TxS2DM+S+edNdI83c27i2DlLiOvtU21bNlU7VWfdVYjhWNxVxs2g2bG2LE3Ky3LOdexOOxuKuNmuM9WY6Nfmr81fmsc+tn1tSz1LHRr81pmr81fn1s+tn1s+qn1+awzWGmzos6K1FailTYzMZmMzGitRWorUWNNjMxmYxMaLOitRWosaLGZjMxiSiSniniiSiXwvkvklElPFPFPFEvkvkvkvvFPFPFPFEvkvxRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRcA=");

    @Test
    public void closeDuringConnectionOpenClosesReturnedConnectionBeforeReading() throws Exception {
        BlockingOpenConnection connection = new BlockingOpenConnection();
        BlockingConnectionFactory connections = new BlockingConnectionFactory(connection);
        RadioInputSession session = new RadioInputSession(
            new URL("http://radio.test/stream"),
            new RecordingListener(),
            connections,
            new RecordingDecoderFactory(new RecordingDecoder(new byte[] { 1, 2, 3, 4 })),
            100,
            1L,
            4L,
            4,
            32);

        try {
            session.start();
            assertTrue(connections.awaitOpen());
            session.close();
            connections.releaseOpen();

            assertTrue(connection.awaitClosed());
            assertFalse(connection.inputRead);
        } finally {
            connections.releaseOpen();
            session.close();
        }
    }

    @Test
    public void defaultMp3DecoderEmitsPcmWhileLiveConnectionRemainsOpen() throws Exception {
        assertDefaultLiveDecoderEmitsPcm("audio/mpeg", LIVE_MP3);
    }

    @Test
    public void defaultAacDecoderEmitsPcmWhileLiveConnectionRemainsOpen() throws Exception {
        assertDefaultLiveDecoderEmitsPcm("audio/aac", LIVE_AAC);
    }

    @Test
    public void throttlesPcmWhenTheStationDeliversAStartupBurst() throws Exception {
        final CountDownLatch callbacks = new CountDownLatch(2);
        final long[] callbackNanos = new long[2];
        final int[] callbackCount = new int[1];
        RadioInputSession.RadioPcmListener listener = new RadioInputSession.RadioPcmListener() {

            @Override
            public synchronized void onPcm(byte[] pcm) {
                if (callbackCount[0] < callbackNanos.length) {
                    callbackNanos[callbackCount[0]] = System.nanoTime();
                }
                callbackCount[0]++;
                callbacks.countDown();
            }
        };
        RadioInputSession.DecoderFactory decoders = new RadioInputSession.DecoderFactory() {

            @Override
            public AudioDecoder create(MediaFormat format, InputStream prefix, InputStream input) {
                return new AudioDecoder() {

                    @Override
                    public void decode(InputStream ignored, PcmSink sink) throws IOException {
                        byte[] chunk = new byte[4096];
                        sink.write(chunk, 0, chunk.length);
                        sink.write(chunk, 0, chunk.length);
                    }
                };
            }
        };
        RadioInputSession session = new RadioInputSession(
            new URL("http://radio.test/stream"),
            listener,
            new RecordingConnectionFactory(new RecordingConnection(
                new byte[] { (byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64 }, "audio/mpeg", null)),
            decoders,
            100,
            1L,
            4L,
            4,
            32);

        try {
            session.start();
            assertTrue("startup PCM was not delivered", callbacks.await(1L, TimeUnit.SECONDS));
            assertTrue("startup burst was not paced", callbackNanos[1] - callbackNanos[0] >= 15_000_000L);
        } finally {
            session.close();
        }
    }

    @Test
    public void httpFactoryRejectsNonHttpRedirectBeforeOpeningTarget() throws Exception {
        final AtomicBoolean targetRequested = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "file:///tmp/radio");
            exchange.sendResponseHeaders(302, -1L);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetRequested.set(true);
            exchange.sendResponseHeaders(200, 0L);
            exchange.close();
        });
        server.start();
        try {
            RadioInputSession.ConnectionFactory factory = defaultConnectionFactory();
            try {
                factory.open(
                    new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"),
                    java.util.Collections.<String, String>emptyMap(),
                    1000);
                org.junit.Assert.fail("Expected non-HTTP redirect to be rejected");
            } catch (MediaException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("redirect"));
            }
            assertFalse(targetRequested.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void httpFactoryBoundsRedirectHops() throws Exception {
        final AtomicBoolean finalTargetRequested = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int index = 0; index < 7; index++) {
            final int next = index + 1;
            server.createContext("/redirect" + index, exchange -> {
                exchange.getResponseHeaders().set("Location", "/redirect" + next);
                exchange.sendResponseHeaders(302, -1L);
                exchange.close();
            });
        }
        server.createContext("/redirect7", exchange -> {
            finalTargetRequested.set(true);
            exchange.sendResponseHeaders(200, 0L);
            exchange.close();
        });
        server.start();
        try {
            try {
                defaultConnectionFactory().open(
                    new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect0"),
                    java.util.Collections.<String, String>emptyMap(),
                    1000);
                org.junit.Assert.fail("Expected excessive redirects to be rejected");
            } catch (MediaException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("redirect"));
            }
            assertFalse(finalTargetRequested.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamingOpusDecoderAcceptsAConnectionEndingWithoutOggEos() throws Exception {
        byte[] head = new byte[] {
            'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 0, 0,
            (byte) 0x80, (byte) 0xbb, 0, 0, 0, 0, 0
        };
        byte[] tags = new byte[] {
            'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0
        };
        RecordingPcmSink sink = new RecordingPcmSink();

        new OggOpusDecoder().decodeStreaming(
            new ByteArrayInputStream(join(
                oggPage(2, 9, 0, head),
                oggPage(0, 9, 1, tags),
                oggPage(0, 9, 2, new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe }))),
            sink);

        assertTrue(sink.bytes.size() > 0);
        assertEquals(1, sink.finishCalls);
    }

    @Test
    public void waitsForOggIdentificationBeforeSelectingTheDefaultCodec() throws Exception {
        byte[] head = new byte[] {
            'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 0, 0,
            (byte) 0x80, (byte) 0xbb, 0, 0, 0, 0, 0
        };
        byte[] tags = new byte[] {
            'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] body = join(
            oggPage(2, 10, 0, head),
            oggPage(0, 10, 1, tags),
            oggPage(0, 10, 2, new byte[] { (byte) 0xf8, (byte) 0xff, (byte) 0xfe }));
        RecordingConnection connection = new RecordingConnection(
            new FirstReadLimitedInputStream(body, 4), "audio/ogg", null);
        RecordingDecoderFactory decoders = new RecordingDecoderFactory(new RecordingDecoder(new byte[] { 1, 2, 3, 4 }));
        RecordingListener listener = new RecordingListener();
        RadioInputSession session = new RadioInputSession(
            new URL("http://radio.test/stream"), listener, new RecordingConnectionFactory(connection), decoders,
            100, 1L, 4L, 4, 32);

        try {
            session.start();

            assertTrue(listener.awaitPcm());
            assertEquals(MediaFormat.OGG_OPUS, decoders.format);
        } finally {
            session.close();
        }
    }

    @Test
    public void requestsIcyMetadataAndReportsOnlyDecodedPcmForTheDetectedFormat() throws Exception {
        byte[] metadata = new byte[16];
        byte[] body = join(
            new byte[] { (byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64 },
            new byte[] { 1 },
            metadata,
            new byte[] { 7, 8, 9, 10 });
        RecordingConnection connection = new RecordingConnection(body, "audio/mpeg", "4");
        RecordingConnectionFactory connections = new RecordingConnectionFactory(connection);
        RecordingDecoder decoder = new RecordingDecoder(new byte[] { 11, 12, 13, 14 });
        RecordingDecoderFactory decoders = new RecordingDecoderFactory(decoder);
        RecordingListener listener = new RecordingListener();
        RadioInputSession session = new RadioInputSession(
            new URL("http://radio.test/stream"),
            listener,
            connections,
            decoders,
            100,
            1L,
            4L,
            4,
            32);
        try {
            session.start();

            assertTrue(listener.awaitPcm());
            assertEquals("MP3", decoders.format.name());
            assertEquals("1", connections.headers.get("Icy-MetaData"));
            assertArrayEquals(new byte[] {
                (byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64, 7, 8, 9, 10
            }, decoder.inputBytes);
            assertArrayEquals(new byte[] { 11, 12, 13, 14 }, listener.pcm.get(0));
        } finally {
            session.close();
        }

        assertTrue(connection.closed);
        assertTrue(decoder.closed);
    }

    @Test
    public void closeInterruptsTheDecoderAndSuppressesCallbacksAfterClose() throws Exception {
        RecordingConnection connection = new RecordingConnection(
            new byte[] { (byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64 },
            "audio/mpeg",
            null);
        RecordingDecoder decoder = new RecordingDecoder(new byte[] { 1, 2, 3, 4 });
        decoder.blockAfterFirstWrite = true;
        RecordingListener listener = new RecordingListener();
        RadioInputSession session = new RadioInputSession(
            new URL("http://radio.test/stream"),
            listener,
            new RecordingConnectionFactory(connection),
            new RecordingDecoderFactory(decoder),
            100,
            1L,
            4L,
            4,
            32);

        session.start();
        assertTrue(listener.awaitPcm());
        int callbackCount = listener.pcm.size();

        session.close();
        decoder.emitAfterClose = true;
        decoder.release.countDown();
        Thread.sleep(20L);

        assertTrue(connection.closed);
        assertTrue(decoder.closed);
        assertEquals(callbackCount, listener.pcm.size());
    }

    private static void assertDefaultLiveDecoderEmitsPcm(String contentType, byte[] frame) throws Exception {
        final CountDownLatch frameSent = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean responseEnded = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            OutputStream output = null;
            try {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, 0L);
                output = exchange.getResponseBody();
                output.write(frame);
                output.flush();
                frameSent.countDown();
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                frameSent.countDown();
            } finally {
                responseEnded.set(true);
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException ignored) {
                        // The client may close the live response first.
                    }
                }
                exchange.close();
            }
        });
        server.start();
        RadioInputSession session = new RadioInputSession(
            new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/stream"),
            new RecordingListener());
        try {
            RecordingListener listener = (RecordingListener) getListener(session);
            session.start();
            assertTrue("live fixture did not send its first frame", frameSent.await(3L, TimeUnit.SECONDS));
            assertTrue("default live decoder did not emit PCM before EOF", listener.awaitPcm());
            assertFalse("live fixture ended before the PCM callback", responseEnded.get());
            assertTrue(listener.pcm.get(0).length > 0);
        } finally {
            session.close();
            release.countDown();
            server.stop(0);
        }
    }

    private static RadioInputSession.RadioPcmListener getListener(RadioInputSession session) throws Exception {
        java.lang.reflect.Field field = RadioInputSession.class.getDeclaredField("listener");
        field.setAccessible(true);
        return (RadioInputSession.RadioPcmListener) field.get(session);
    }

    private static RadioInputSession.ConnectionFactory defaultConnectionFactory() throws Exception {
        Class<?> type = Class.forName(RadioInputSession.class.getName() + "$HttpConnectionFactory");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (RadioInputSession.ConnectionFactory) constructor.newInstance();
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.write(part);
        }
        return output.toByteArray();
    }

    private static byte[] oggPage(int flags, int serial, int sequence, byte[] packet) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('O');
        output.write('g');
        output.write('g');
        output.write('S');
        output.write(0);
        output.write(flags);
        output.write(new byte[8], 0, 8);
        output.write(new byte[] { (byte) serial, 0, 0, 0 }, 0, 4);
        output.write(new byte[] { (byte) sequence, 0, 0, 0 }, 0, 4);
        output.write(new byte[4], 0, 4);
        output.write(1);
        output.write(packet.length);
        output.write(packet, 0, packet.length);
        byte[] page = output.toByteArray();
        putLittleEndian(page, 22, oggCrc(page));
        return page;
    }

    private static int oggCrc(byte[] page) {
        int crc = 0;
        for (int i = 0; i < page.length; i++) {
            int value = i >= 22 && i < 26 ? 0 : page[i] & 0xff;
            crc ^= value << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ ((crc & 0x80000000) == 0 ? 0 : 0x04c11db7);
            }
        }
        return crc;
    }

    private static void putLittleEndian(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            bytes[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    private static final class RecordingConnectionFactory implements RadioInputSession.ConnectionFactory {

        private final RecordingConnection connection;
        private Map<String, String> headers;

        private RecordingConnectionFactory(RecordingConnection connection) {
            this.connection = connection;
        }

        @Override
        public RadioInputSession.RadioConnection open(URL url, Map<String, String> headers, int timeoutMillis) {
            this.headers = headers;
            return connection;
        }
    }

    private static final class BlockingConnectionFactory implements RadioInputSession.ConnectionFactory {

        private final BlockingOpenConnection connection;
        private final CountDownLatch openEntered = new CountDownLatch(1);
        private final CountDownLatch releaseOpen = new CountDownLatch(1);

        private BlockingConnectionFactory(BlockingOpenConnection connection) {
            this.connection = connection;
        }

        @Override
        public RadioInputSession.RadioConnection open(URL url, Map<String, String> headers, int timeoutMillis)
            throws IOException {
            openEntered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseOpen.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return connection;
        }

        private boolean awaitOpen() throws InterruptedException {
            return openEntered.await(1L, TimeUnit.SECONDS);
        }

        private void releaseOpen() {
            releaseOpen.countDown();
        }
    }

    private static final class BlockingOpenConnection implements RadioInputSession.RadioConnection {

        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final InputStream input = new InputStream() {

            @Override
            public int read() {
                inputRead = true;
                return -1;
            }
        };
        private volatile boolean inputRead;

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public String getContentType() {
            return "audio/mpeg";
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public void close() throws IOException {
            input.close();
            closedLatch.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closedLatch.await(1L, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingConnection implements RadioInputSession.RadioConnection {

        private final InputStream input;
        private final String contentType;
        private final String icyMetaint;
        private volatile boolean closed;

        private RecordingConnection(byte[] body, String contentType, String icyMetaint) {
            this(new ByteArrayInputStream(body), contentType, icyMetaint);
        }

        private RecordingConnection(InputStream input, String contentType, String icyMetaint) {
            this.input = input;
            this.contentType = contentType;
            this.icyMetaint = icyMetaint;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getHeader(String name) {
            return "icy-metaint".equalsIgnoreCase(name) ? icyMetaint : null;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            input.close();
        }
    }

    private static final class FirstReadLimitedInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private final int firstReadLimit;
        private boolean firstRead = true;

        private FirstReadLimitedInputStream(byte[] bytes, int firstReadLimit) {
            delegate = new ByteArrayInputStream(bytes);
            this.firstReadLimit = firstReadLimit;
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            int limit = firstRead ? Math.min(length, firstReadLimit) : length;
            firstRead = false;
            return delegate.read(bytes, offset, limit);
        }
    }

    private static final class RecordingDecoderFactory implements RadioInputSession.DecoderFactory {

        private final RecordingDecoder decoder;
        private MediaFormat format;

        private RecordingDecoderFactory(RecordingDecoder decoder) {
            this.decoder = decoder;
        }

        @Override
        public AudioDecoder create(MediaFormat format, InputStream prefix, InputStream input) {
            this.format = format;
            decoder.input = input;
            return decoder;
        }
    }

    private static final class RecordingDecoder implements AudioDecoder, Closeable {

        private final byte[] pcm;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile InputStream input;
        private volatile byte[] inputBytes;
        private volatile boolean closed;
        private volatile boolean blockAfterFirstWrite;
        private volatile boolean emitAfterClose;

        private RecordingDecoder(byte[] pcm) {
            this.pcm = pcm;
        }

        @Override
        public void decode(InputStream input, PcmSink sink) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            inputBytes = bytes.toByteArray();
            entered.countDown();
            sink.write(pcm, 0, pcm.length);
            if (blockAfterFirstWrite) {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (emitAfterClose) {
                sink.write(pcm, 0, pcm.length);
            }
        }

        @Override
        public void close() {
            closed = true;
            release.countDown();
        }
    }

    private static final class RecordingPcmSink implements PcmSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int finishCalls;

        @Override
        public void write(byte[] data, int offset, int length) {
            bytes.write(data, offset, length);
        }

        @Override
        public void finish() {
            finishCalls++;
        }

        @Override
        public void abort() {}
    }

    private static final class RecordingListener implements RadioInputSession.RadioPcmListener {

        private final List<byte[]> pcm = new ArrayList<byte[]>();

        @Override
        public synchronized void onPcm(byte[] data) {
            pcm.add(Arrays.copyOf(data, data.length));
            notifyAll();
        }

        private synchronized boolean awaitPcm() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000L;
            while (pcm.isEmpty() && System.currentTimeMillis() < deadline) {
                wait(Math.max(1L, deadline - System.currentTimeMillis()));
            }
            return !pcm.isEmpty();
        }
    }
}
