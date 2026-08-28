package com.horizonradio.server.media;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.horizonradio.media.net.ExternalResourcePolicy;

/**
 * Opens and decodes one live radio URL without buffering the station stream.
 * Each instance owns one daemon worker and reconnects the bounded streaming
 * decoder after an end or read/decode failure.
 */
public class RadioInputSession implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(RadioInputSession.class.getName());
    private static final int PREFIX_BYTES = 4096;
    private static final int PREFIX_READ_CHUNK_BYTES = 256;
    private static final int PREFIX_DETECTION_BYTES = 4;
    private static final int NORMALIZED_FRAME_BYTES = 4;
    private static final int PCM_CHUNK_BYTES = 4096;
    private static final int DEFAULT_HTTP_TIMEOUT_MILLIS = 10000;
    private static final long DEFAULT_RECONNECT_INITIAL_MILLIS = 250L;
    private static final long DEFAULT_RECONNECT_MAX_MILLIS = 10000L;
    private static final int DEFAULT_JITTER_STARTUP_BYTES = NORMALIZED_FRAME_BYTES;
    private static final int DEFAULT_JITTER_MAX_BYTES = 128 * 1024;
    private static final String RADIO_USER_AGENT = "HorizonRadio/1.0";

    private final Object stateLock = new Object();
    private final Object callbackLock = new Object();
    private final URL streamUrl;
    private final RadioPcmListener listener;
    private final ConnectionFactory connectionFactory;
    private final DecoderFactory decoderFactory;
    private final AudioFormatDetector formatDetector;
    private final int httpTimeoutMillis;
    private final long reconnectInitialMillis;
    private final long reconnectMaximumMillis;
    private final int jitterStartupBytes;
    private final int jitterMaximumBytes;
    private final ExecutorService executor;

    private volatile boolean closed;
    private boolean started;
    private Future<?> worker;
    private volatile RadioConnection activeConnection;
    private volatile InputStream activeInput;
    private volatile AudioDecoder activeDecoder;
    private volatile PcmSink activeSink;

    public RadioInputSession(String streamUrl, RadioPcmListener listener) {
        this(parseUrl(streamUrl), listener);
    }

    public RadioInputSession(URL streamUrl, RadioPcmListener listener) {
        this(
            streamUrl,
            listener,
            new HttpConnectionFactory(),
            new StreamingDecoderFactory(),
            DEFAULT_HTTP_TIMEOUT_MILLIS,
            DEFAULT_RECONNECT_INITIAL_MILLIS,
            DEFAULT_RECONNECT_MAX_MILLIS,
            DEFAULT_JITTER_STARTUP_BYTES,
            DEFAULT_JITTER_MAX_BYTES);
    }

    RadioInputSession(URL streamUrl, RadioPcmListener listener, ConnectionFactory connectionFactory,
        DecoderFactory decoderFactory, int httpTimeoutMillis, long reconnectInitialMillis, long reconnectMaximumMillis,
        int jitterStartupBytes, int jitterMaximumBytes) {
        if (streamUrl == null || listener == null || connectionFactory == null || decoderFactory == null) {
            throw new IllegalArgumentException("Radio input session dependencies are required");
        }
        if (httpTimeoutMillis <= 0 || reconnectInitialMillis < 0L || reconnectMaximumMillis < reconnectInitialMillis) {
            throw new IllegalArgumentException("Radio input session timing is invalid");
        }
        if (jitterStartupBytes <= 0 || jitterMaximumBytes < jitterStartupBytes) {
            throw new IllegalArgumentException("Radio input session jitter bounds are invalid");
        }
        this.streamUrl = streamUrl;
        this.listener = listener;
        this.connectionFactory = connectionFactory;
        this.decoderFactory = decoderFactory;
        formatDetector = new AudioFormatDetector();
        this.httpTimeoutMillis = httpTimeoutMillis;
        this.reconnectInitialMillis = reconnectInitialMillis;
        this.reconnectMaximumMillis = reconnectMaximumMillis;
        this.jitterStartupBytes = jitterStartupBytes;
        this.jitterMaximumBytes = jitterMaximumBytes;
        executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    /** Starts the asynchronous input loop. Repeated starts are harmless. */
    public void start() {
        synchronized (stateLock) {
            if (started || closed) {
                return;
            }
            started = true;
            worker = executor.submit(new Runnable() {

                @Override
                public void run() {
                    runInputLoop();
                }
            });
        }
    }

    /**
     * Interrupts the worker, closes the active response and decoder, and
     * suppresses any callback that has not already entered the listener.
     */
    @Override
    public void close() {
        Future<?> toCancel;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            toCancel = worker;
            stateLock.notifyAll();
        }
        if (toCancel != null) {
            toCancel.cancel(true);
        }
        closeActiveResources();
        executor.shutdownNow();
    }

    boolean isClosed() {
        return closed;
    }

    private void runInputLoop() {
        long backoff = reconnectInitialMillis;
        while (!closed) {
            Attempt attempt = new Attempt();
            try {
                openAttempt(attempt);
                if (closed) {
                    return;
                }
                attempt.decoder.decode(attempt.input, attempt.sink);
                if (!closed) {
                    backoff = attempt.sink.hasDeliveredPcm() ? reconnectInitialMillis : nextBackoff(backoff);
                    waitForReconnect(backoff);
                }
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.log(Level.FINE, "Radio input attempt failed; reconnecting", exception);
                    backoff = attempt.sink != null && attempt.sink.hasDeliveredPcm() ? reconnectInitialMillis
                        : nextBackoff(backoff);
                    waitForReconnect(backoff);
                }
            } catch (RuntimeException exception) {
                if (!closed) {
                    LOGGER.log(Level.FINE, "Radio input attempt failed; reconnecting", exception);
                    backoff = nextBackoff(backoff);
                    waitForReconnect(backoff);
                }
            } finally {
                attempt.close();
            }
        }
    }

    private void openAttempt(Attempt attempt) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Icy-MetaData", "1");
        headers.put("User-Agent", RADIO_USER_AGENT);
        headers.put("Accept", "audio/*");
        RadioConnection connection = connectionFactory
            .open(streamUrl, Collections.unmodifiableMap(headers), httpTimeoutMillis);
        if (connection == null || connection.getInputStream() == null) {
            closeQuietly(connection);
            throw new MediaException("Radio HTTP connection returned no input stream");
        }

        synchronized (stateLock) {
            if (closed) {
                closeQuietly(connection);
                throw new IOException("Radio input session is closed");
            }
            attempt.connection = connection;
            activeConnection = connection;
        }

        InputStream audio = connection.getInputStream();
        int metadataInterval = parseMetadataInterval(connection.getHeader("icy-metaint"));
        if (metadataInterval > 0) {
            audio = new IcyMetadataInputStream(audio, metadataInterval);
        }
        PushbackInputStream input = new PushbackInputStream(audio, PREFIX_BYTES);
        synchronized (stateLock) {
            if (closed) {
                closeQuietly(input);
                throw new IOException("Radio input session is closed");
            }
            attempt.input = input;
            activeInput = input;
        }
        byte[] prefix = readPrefix(input, connection.getContentType());
        if (prefix.length == 0) {
            throw new MediaException("Radio stream returned no audio bytes");
        }
        input.unread(prefix);
        MediaFormat format = formatDetector.detect(connection.getContentType(), prefix);
        if (format == MediaFormat.UNKNOWN) {
            throw new MediaException("Unsupported or unrecognized radio stream format");
        }
        AudioDecoder decoder = decoderFactory.create(format, new ByteArrayInputStream(prefix), input);
        if (decoder == null) {
            throw new MediaException("Radio decoder factory returned no decoder");
        }
        RadioPcmSink sink = new RadioPcmSink(new RadioJitterBuffer(jitterStartupBytes, jitterMaximumBytes));
        synchronized (stateLock) {
            if (closed) {
                closeQuietly(sink);
                closeQuietly(decoder);
                throw new IOException("Radio input session is closed");
            }
            attempt.decoder = decoder;
            attempt.sink = sink;
            activeDecoder = decoder;
            activeSink = sink;
        }
    }

    private void waitForReconnect(long delayMillis) {
        if (delayMillis <= 0L) {
            return;
        }
        synchronized (stateLock) {
            if (!closed) {
                try {
                    stateLock.wait(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
        }
    }

    private long nextBackoff(long current) {
        if (current >= reconnectMaximumMillis) {
            return reconnectMaximumMillis;
        }
        if (current > reconnectMaximumMillis / 2L) {
            return reconnectMaximumMillis;
        }
        return Math.min(reconnectMaximumMillis, Math.max(1L, current * 2L));
    }

    private void closeActiveResources() {
        closeQuietly(activeInput);
        closeQuietly(activeSink);
        closeQuietly(activeDecoder);
        closeQuietly(activeConnection);
    }

    private static int parseMetadataInterval(String value) throws MediaException {
        if (value == null || value.trim()
            .length() == 0) {
            return 0;
        }
        final int interval;
        try {
            interval = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new MediaException("Invalid ICY metadata interval", exception);
        }
        if (interval <= 0) {
            throw new MediaException("Invalid ICY metadata interval");
        }
        return interval;
    }

    private byte[] readPrefix(InputStream input, String contentType) throws IOException {
        byte[] prefix = new byte[PREFIX_BYTES];
        int total = 0;
        while (total < prefix.length) {
            int count = input.read(prefix, total, Math.min(PREFIX_READ_CHUNK_BYTES, prefix.length - total));
            if (count < 0) {
                break;
            }
            if (count == 0) {
                continue;
            }
            total += count;
            if (total >= PREFIX_DETECTION_BYTES) {
                byte[] candidate = new byte[total];
                System.arraycopy(prefix, 0, candidate, 0, total);
                MediaFormat format = formatDetector.detect(contentType, candidate);
                if (format != MediaFormat.UNKNOWN && (!isOgg(candidate) || hasExplicitOggCodec(contentType)
                    || AudioFormatDetector.hasOggIdentification(candidate))) {
                    break;
                }
            }
        }
        byte[] result = new byte[total];
        System.arraycopy(prefix, 0, result, 0, total);
        return result;
    }

    private static boolean isOgg(byte[] prefix) {
        return prefix.length >= 4 && prefix[0] == 'O' && prefix[1] == 'g' && prefix[2] == 'g' && prefix[3] == 'S';
    }

    private static boolean hasExplicitOggCodec(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("opus") || normalized.contains("vorbis");
    }

    private static URL parseUrl(String value) {
        if (value == null || value.trim()
            .length() == 0) {
            throw new IllegalArgumentException("Radio stream URL must not be empty");
        }
        try {
            return new URL(value.trim());
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Invalid radio stream URL", exception);
        }
    }

    private static void closeQuietly(Object resource) {
        if (!(resource instanceof Closeable)) {
            return;
        }
        try {
            ((Closeable) resource).close();
        } catch (IOException ignored) {
            // Closing a reconnect attempt is best effort.
        }
    }

    public interface RadioPcmListener {

        void onPcm(byte[] pcm);

        default void onFailure(String message) {}
    }

    public interface ConnectionFactory {

        RadioConnection open(URL url, Map<String, String> headers, int timeoutMillis) throws IOException;
    }

    public interface RadioConnection extends Closeable {

        InputStream getInputStream();

        String getContentType();

        String getHeader(String name);
    }

    public interface DecoderFactory {

        AudioDecoder create(MediaFormat format, InputStream prefix, InputStream input) throws IOException;
    }

    private final class Attempt implements Closeable {

        private RadioConnection connection;
        private InputStream input;
        private AudioDecoder decoder;
        private RadioPcmSink sink;

        @Override
        public void close() {
            closeQuietly(input);
            closeQuietly(sink);
            closeQuietly(decoder);
            closeQuietly(connection);
            if (activeInput == input) {
                activeInput = null;
            }
            if (activeSink == sink) {
                activeSink = null;
            }
            if (activeDecoder == decoder) {
                activeDecoder = null;
            }
            if (activeConnection == connection) {
                activeConnection = null;
            }
        }
    }

    private final class RadioPcmSink implements PcmSink {

        private final RadioJitterBuffer jitterBuffer;
        private final RadioPcmPacer pacer = new RadioPcmPacer();
        private final byte[] partialFrame = new byte[NORMALIZED_FRAME_BYTES];
        private byte[] chunk = new byte[PCM_CHUNK_BYTES];
        private int partialLength;
        private int chunkLength;
        private boolean deliveredPcm;
        private boolean closedSink;

        private RadioPcmSink(RadioJitterBuffer jitterBuffer) {
            this.jitterBuffer = jitterBuffer;
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) throws IOException {
            if (closedSink || closed) {
                throw new IOException("Radio PCM sink is closed");
            }
            if (data == null) {
                throw new NullPointerException("data");
            }
            if (offset < 0 || length < 0 || offset > data.length - length) {
                throw new IndexOutOfBoundsException("Invalid PCM range");
            }
            int position = offset;
            int end = offset + length;
            if (partialLength > 0) {
                int copied = Math.min(NORMALIZED_FRAME_BYTES - partialLength, end - position);
                System.arraycopy(data, position, partialFrame, partialLength, copied);
                partialLength += copied;
                position += copied;
                if (partialLength == NORMALIZED_FRAME_BYTES) {
                    appendCompleteBytes(partialFrame, 0, NORMALIZED_FRAME_BYTES);
                    partialLength = 0;
                }
            }
            while (position + NORMALIZED_FRAME_BYTES <= end) {
                int available = end - position;
                int writable = Math.min(available, chunk.length - chunkLength);
                writable -= writable % NORMALIZED_FRAME_BYTES;
                if (writable == 0) {
                    flushChunk();
                    continue;
                }
                appendCompleteBytes(data, position, writable);
                position += writable;
                if (chunkLength == chunk.length) {
                    flushChunk();
                }
            }
            if (position < end) {
                partialLength = end - position;
                System.arraycopy(data, position, partialFrame, 0, partialLength);
            }
            flushChunk();
        }

        @Override
        public synchronized void finish() throws IOException {
            if (closedSink) {
                return;
            }
            if (partialLength != 0) {
                throw new MediaException("Radio decoder ended with a partial PCM frame");
            }
            flushChunk();
            closedSink = true;
        }

        @Override
        public synchronized void abort() {
            closedSink = true;
            partialLength = 0;
            chunkLength = 0;
            jitterBuffer.close();
        }

        private synchronized boolean hasDeliveredPcm() {
            return deliveredPcm;
        }

        private void appendCompleteBytes(byte[] data, int offset, int length) {
            System.arraycopy(data, offset, chunk, chunkLength, length);
            chunkLength += length;
        }

        private void flushChunk() throws IOException {
            if (chunkLength == 0 || closed || closedSink) {
                return;
            }
            byte[] completeChunk = new byte[chunkLength];
            System.arraycopy(chunk, 0, completeChunk, 0, chunkLength);
            chunkLength = 0;
            pace(completeChunk.length);
            if (closed || closedSink) {
                return;
            }
            if (!jitterBuffer.offer(completeChunk)) {
                return;
            }
            byte[] next;
            while (!closed && (next = jitterBuffer.poll()) != null) {
                deliveredPcm = true;
                safePcm(next);
            }
        }

        private void pace(int bytes) throws IOException {
            long deadlineNanos = pacer.reserve(bytes, System.nanoTime());
            while (!closed && !closedSink) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return;
                }
                LockSupport.parkNanos(Math.min(remainingNanos, 50_000_000L));
                if (Thread.currentThread()
                    .isInterrupted()) {
                    throw new IOException("Radio PCM pacing interrupted");
                }
            }
        }
    }

    private void safePcm(byte[] pcm) {
        synchronized (callbackLock) {
            if (closed) {
                return;
            }
            try {
                listener.onPcm(pcm);
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Radio PCM listener failed", exception);
            }
        }
    }

    private static final class StreamingDecoderFactory implements DecoderFactory {

        private final AudioDecoderRegistry registry = new AudioDecoderRegistry();

        @Override
        public AudioDecoder create(MediaFormat format, InputStream prefix, InputStream input) throws IOException {
            if (format == MediaFormat.M4A || format == MediaFormat.WEBM_OPUS) {
                throw new MediaException("Radio format " + format + " is not safely streamable");
            }
            final AudioDecoder decoder = registry.find(format, prefix, input);
            if (format == MediaFormat.OGG_VORBIS) {
                return new AudioDecoder() {

                    @Override
                    public void decode(InputStream source, PcmSink sink) throws IOException {
                        ((OggVorbisDecoder) decoder).decodeStreaming(source, sink);
                    }
                };
            }
            if (format == MediaFormat.OGG_OPUS) {
                return new AudioDecoder() {

                    @Override
                    public void decode(InputStream source, PcmSink sink) throws IOException {
                        ((OggOpusDecoder) decoder).decodeStreaming(source, sink);
                    }
                };
            }
            return decoder;
        }
    }

    private static final class HttpConnectionFactory implements ConnectionFactory {

        private static final int MAX_REDIRECTS = 5;
        private final ExternalResourcePolicy externalResourcePolicy;

        private HttpConnectionFactory() {
            this(new ExternalResourcePolicy());
        }

        private HttpConnectionFactory(ExternalResourcePolicy externalResourcePolicy) {
            if (externalResourcePolicy == null) {
                throw new IllegalArgumentException("External resource policy is required");
            }
            this.externalResourcePolicy = externalResourcePolicy;
        }

        @Override
        public RadioConnection open(URL url, Map<String, String> headers, int timeoutMillis) throws IOException {
            URL current = url;
            int redirects = 0;
            while (true) {
                HttpURLConnection connection = null;
                try {
                    current = externalResourcePolicy.requirePublicHttpUrl(current);
                    URLConnection opened = current.openConnection();
                    if (!(opened instanceof HttpURLConnection)) {
                        throw new MediaException("Radio URL must use HTTP or HTTPS");
                    }
                    connection = (HttpURLConnection) opened;
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(timeoutMillis);
                    connection.setReadTimeout(timeoutMillis);
                    connection.setRequestMethod("GET");
                    connection.setUseCaches(false);
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }

                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw new MediaException("HTTP radio redirect limit exceeded");
                        }
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.trim()
                            .length() == 0) {
                            throw new MediaException("HTTP radio redirect has no Location");
                        }
                        URL redirected = new URL(current, location);
                        try {
                            redirected = externalResourcePolicy.requirePublicHttpUrl(redirected);
                        } catch (IOException exception) {
                            throw new MediaException(
                                "HTTP radio redirect rejected: " + exception.getMessage(),
                                exception);
                        }
                        closeQuietly(connection.getErrorStream());
                        connection.disconnect();
                        current = redirected;
                        redirects++;
                        continue;
                    }
                    if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                        closeQuietly(connection.getErrorStream());
                        throw new MediaException("HTTP radio request failed with status " + status);
                    }
                    return new HttpRadioConnection(connection);
                } catch (IOException exception) {
                    if (connection != null) {
                        closeQuietly(connection.getErrorStream());
                        connection.disconnect();
                    }
                    throw exception;
                }
            }
        }
    }

    private static final class HttpRadioConnection implements RadioConnection {

        private final HttpURLConnection connection;
        private final InputStream input;

        private HttpRadioConnection(HttpURLConnection connection) throws IOException {
            this.connection = connection;
            input = connection.getInputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public String getContentType() {
            return connection.getContentType();
        }

        @Override
        public String getHeader(String name) {
            return connection.getHeaderField(name);
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

    private static final class DaemonThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HorizonRadio-RadioInput");
            thread.setDaemon(true);
            return thread;
        }
    }
}
