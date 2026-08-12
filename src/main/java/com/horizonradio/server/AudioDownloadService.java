package com.horizonradio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.horizonradio.core.model.SearchResult;
import com.horizonradio.core.server.PlaylistImportService;
import com.horizonradio.server.media.JavaAudioDownloadBackend;
import com.horizonradio.server.media.MediaException;
import com.horizonradio.server.media.PcmSink;
import com.horizonradio.server.media.YouTubeMediaModels;
import com.horizonradio.server.media.YouTubeMetadataResolver;

/**
 * Downloads YouTube audio through the Java media backend and exposes the
 * existing metadata import shapes to PlaylistManager.
 */
public class AudioDownloadService {

    private static final Logger LOGGER = Logger.getLogger(AudioDownloadService.class.getName());
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS = 2000L;

    private final Path downloadDir;
    private final YouTubeMediaModels.AudioDownloadBackend downloadBackend;
    private final YouTubeMetadataResolver metadataResolver;
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool(new ThreadFactory() {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HorizonRadio-Downloader");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final ConcurrentMap<String, DownloadOperation> activeDownloads = new ConcurrentHashMap<String, DownloadOperation>();
    private final CancellationInterleavingHook cancellationInterleavingHook;

    public AudioDownloadService(Path downloadDir) throws IOException {
        this(
            downloadDir,
            new JavaAudioDownloadBackend(),
            new YouTubeMetadataResolver(),
            CancellationInterleavingHook.NONE);
    }

    AudioDownloadService(Path downloadDir, boolean checkDependencies) throws IOException {
        this(downloadDir);
    }

    public AudioDownloadService(Path downloadDir, String youtubeCookiesFromBrowser, String youtubeCookiesFile)
        throws IOException {
        this(downloadDir);
    }

    AudioDownloadService(Path downloadDir, boolean checkDependencies, String youtubeCookiesFromBrowser,
        String youtubeCookiesFile) throws IOException {
        this(downloadDir);
    }

    AudioDownloadService(Path downloadDir, YouTubeMediaModels.AudioDownloadBackend downloadBackend) throws IOException {
        this(downloadDir, downloadBackend, new YouTubeMetadataResolver(), CancellationInterleavingHook.NONE);
    }

    AudioDownloadService(Path downloadDir, YouTubeMediaModels.AudioDownloadBackend downloadBackend,
        YouTubeMetadataResolver metadataResolver) throws IOException {
        this(downloadDir, downloadBackend, metadataResolver, CancellationInterleavingHook.NONE);
    }

    AudioDownloadService(Path downloadDir, YouTubeMediaModels.AudioDownloadBackend downloadBackend,
        CancellationInterleavingHook cancellationInterleavingHook) throws IOException {
        this(downloadDir, downloadBackend, new YouTubeMetadataResolver(), cancellationInterleavingHook);
    }

    private AudioDownloadService(Path downloadDir, YouTubeMediaModels.AudioDownloadBackend downloadBackend,
        YouTubeMetadataResolver metadataResolver, CancellationInterleavingHook cancellationInterleavingHook)
        throws IOException {
        if (downloadBackend == null) throw new IllegalArgumentException("Java audio download backend is required");
        if (metadataResolver == null) throw new IllegalArgumentException("YouTube metadata resolver is required");
        this.downloadDir = downloadDir;
        this.downloadBackend = downloadBackend;
        this.metadataResolver = metadataResolver;
        this.cancellationInterleavingHook = cancellationInterleavingHook == null ? CancellationInterleavingHook.NONE
            : cancellationInterleavingHook;
        Files.createDirectories(downloadDir);
    }

    /** Downloads a YouTube video as WAV and returns its cache path, or null on failure. */
    public synchronized CompletableFuture<Path> download(final String videoId) {
        DownloadOperation existingOperation = activeDownloads.get(videoId);
        CompletableFuture<Path> existing = existingOperation == null ? null : existingOperation.future;
        if (existing != null) return existing;
        final Path filePath;
        try {
            filePath = getFilePath(videoId);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(null);
        }
        final DownloadOperation operation = new DownloadOperation();
        CompletableFuture<Path> future = CompletableFuture.supplyAsync(new Supplier<Path>() {

            @Override
            public Path get() {
                if (Files.exists(filePath)) {
                    if (isCanonicalCachedWav(filePath)) {
                        LOGGER.info("HorizonRadio: Using cached audio for " + videoId);
                        return filePath;
                    }
                    try {
                        Files.deleteIfExists(filePath);
                    } catch (IOException exception) {
                        LOGGER.log(Level.WARNING, "Could not remove invalid cached WAV for " + videoId, exception);
                        return null;
                    }
                }
                LOGGER.info("HorizonRadio: Downloading audio with the Java media backend for " + videoId);
                try {
                    return downloadBackend.download(videoId, filePath, operation);
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "Java audio download failed for " + videoId, exception);
                    return null;
                }
            }
        }, downloadExecutor);
        operation.future = future;
        activeDownloads.put(videoId, operation);
        future.whenComplete(new java.util.function.BiConsumer<Path, Throwable>() {

            @Override
            public void accept(Path path, Throwable failure) {
                synchronized (AudioDownloadService.this) {
                    activeDownloads.remove(videoId, operation);
                }
            }
        });
        return future;
    }

    /** Cancels an in-flight Java media download for the given video, if present. */
    public synchronized void cancelDownload(String videoId) {
        if (videoId == null) return;
        DownloadOperation operation = activeDownloads.get(videoId);
        if (operation == null) return;
        cancellationInterleavingHook.beforeOperationCancellation();
        synchronized (operation) {
            if (!operation.cancel()) return;
            activeDownloads.remove(videoId, operation);
            cancellationInterleavingHook.afterOperationRemoved();
            operation.future.cancel(true);
        }
    }

    public CompletableFuture<String> extractPlaylistJson(final String playlistUrl) {
        return metadataFuture(new Supplier<String>() {

            @Override
            public String get() {
                return metadataResolver.resolvePlaylistJson(playlistUrl);
            }
        }, "playlist");
    }

    public CompletableFuture<String> extractVideoJson(final String videoUrl) {
        return metadataFuture(new Supplier<String>() {

            @Override
            public String get() {
                return metadataResolver.resolveVideoJson(videoUrl);
            }
        }, "video");
    }

    /** Resolves one validated YouTube video ID to local presentation metadata. */
    public CompletableFuture<SearchResult> resolveVideoMetadata(String videoId) {
        try {
            String safeVideoId = com.horizonradio.server.media.YouTubeUrlParser.requireVideoId(videoId);
            return extractVideoJson("https://www.youtube.com/watch?v=" + safeVideoId)
                .thenApply(new java.util.function.Function<String, SearchResult>() {

                    @Override
                    public SearchResult apply(String json) {
                        return PlaylistImportService.parseVideo(json);
                    }
                });
        } catch (MediaException exception) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<String> extractVideoDurationOutput(final List<String> videoIds) {
        return metadataFuture(new Supplier<String>() {

            @Override
            public String get() {
                return metadataResolver.resolveDurationOutput(videoIds);
            }
        }, "duration");
    }

    private CompletableFuture<String> metadataFuture(final Supplier<String> operation, final String operationName) {
        return CompletableFuture.supplyAsync(new Supplier<String>() {

            @Override
            public String get() {
                try {
                    return operation.get();
                } catch (RuntimeException exception) {
                    LOGGER.log(Level.WARNING, "YouTube " + operationName + " metadata lookup failed", exception);
                    return null;
                }
            }
        }, downloadExecutor);
    }

    public Path getFilePath(String videoId) {
        try {
            return downloadDir.resolve(com.horizonradio.server.media.YouTubeUrlParser.requireVideoId(videoId) + ".wav");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid YouTube video ID", exception);
        }
    }

    private static boolean isCanonicalCachedWav(Path filePath) {
        try {
            long length = Files.size(filePath);
            if (length <= 44L || length > 0xffffffffL) return false;
            byte[] header = new byte[44];
            try (java.io.InputStream input = Files.newInputStream(filePath)) {
                int offset = 0;
                while (offset < header.length) {
                    int count = input.read(header, offset, header.length - offset);
                    if (count < 0) return false;
                    offset += count;
                }
            }
            long dataLength = unsignedInt(header, 40);
            return matches(header, 0, "RIFF") && unsignedInt(header, 4) == length - 8L
                && matches(header, 8, "WAVE")
                && matches(header, 12, "fmt ")
                && unsignedInt(header, 16) == 16L
                && unsignedShort(header, 20) == 1
                && unsignedShort(header, 22) == 2
                && unsignedInt(header, 24) == 44100L
                && unsignedInt(header, 28) == 176400L
                && unsignedShort(header, 32) == 4
                && unsignedShort(header, 34) == 16
                && matches(header, 36, "data")
                && dataLength == length - 44L
                && dataLength > 0L
                && dataLength % 4L == 0L;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean matches(byte[] bytes, int offset, String text) {
        for (int index = 0; index < text.length(); index++)
            if (bytes[offset + index] != (byte) text.charAt(index)) return false;
        return true;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xffL) | (((long) bytes[offset + 1] & 0xffL) << 8)
            | (((long) bytes[offset + 2] & 0xffL) << 16)
            | (((long) bytes[offset + 3] & 0xffL) << 24);
    }

    public void delete(String videoId) {
        Path filePath = getFilePath(videoId);
        try {
            Files.deleteIfExists(filePath);
            LOGGER.info("HorizonRadio: Deleted audio for " + videoId);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "HorizonRadio: Failed to delete audio for " + videoId, exception);
        }
    }

    public boolean isDependenciesAvailable() {
        return downloadBackend.isReady();
    }

    public void shutdown() {
        downloadExecutor.shutdownNow();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS);
        while (!downloadExecutor.isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                downloadExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
                downloadExecutor.shutdownNow();
            }
        }
        if (interrupted) Thread.currentThread()
            .interrupt();
        if (!downloadExecutor.isTerminated()) {
            LOGGER.warning(
                "HorizonRadio: Audio download executor did not terminate within " + EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS
                    + " ms");
        }
        LOGGER.info("HorizonRadio: Audio download service shut down");
    }

    interface CancellationInterleavingHook {

        CancellationInterleavingHook NONE = new CancellationInterleavingHook() {

            @Override
            public void afterOperationRemoved() {}
        };

        void afterOperationRemoved();

        default void beforeOperationCancellation() {}
    }

    private static final class DownloadOperation implements YouTubeMediaModels.CancellationToken {

        private boolean cancelled;
        private boolean committed;
        private volatile CompletableFuture<Path> future;

        @Override
        public synchronized boolean isCancelled() {
            return cancelled || Thread.currentThread()
                .isInterrupted();
        }

        private boolean cancel() {
            if (committed) return false;
            cancelled = true;
            return true;
        }

        @Override
        public synchronized void finish(PcmSink sink) throws IOException {
            if (cancelled || Thread.currentThread()
                .isInterrupted()) {
                throw new MediaException("YouTube audio download cancelled");
            }
            sink.finish();
            committed = true;
        }
    }
}
