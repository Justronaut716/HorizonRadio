package com.horizonradio.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

/**
 * Downloads audio from YouTube using yt-dlp as a subprocess and stores the
 * resulting WAV files on disk. The server then reads and delivers the file
 * contents to clients via Minecraft packets (no separate HTTP port needed).
 */
public class AudioDownloadService {

    private static final Logger LOGGER = Logger.getLogger(AudioDownloadService.class.getName());
    private static final int MAX_PROCESS_OUTPUT_BYTES = 1024 * 1024;
    private static final long PROCESS_TERMINATION_TIMEOUT_MILLIS = 1000L;
    private static final long PROCESS_OUTPUT_JOIN_TIMEOUT_MILLIS = 1000L;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS = 2000L;
    private static final long DEPENDENCY_TIMEOUT_SECONDS = 5L;
    private static final long DOWNLOAD_TIMEOUT_MINUTES = 2L;
    private static final long METADATA_TIMEOUT_MINUTES = 5L;

    private final Path downloadDir;
    private final String youtubeCookiesFromBrowser;
    private final String youtubeCookiesFile;
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool(new ThreadFactory() {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HorizonRadio-Downloader");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final ConcurrentMap<String, CompletableFuture<Path>> activeDownloads = new ConcurrentHashMap<String, CompletableFuture<Path>>();
    private volatile boolean ytDlpAvailable;
    private volatile boolean ffmpegAvailable;

    public AudioDownloadService(Path downloadDir) throws IOException {
        this(downloadDir, true, "", "");
    }

    AudioDownloadService(Path downloadDir, boolean checkDependencies) throws IOException {
        this(downloadDir, checkDependencies, "", "");
    }

    public AudioDownloadService(Path downloadDir, String youtubeCookiesFromBrowser, String youtubeCookiesFile)
        throws IOException {
        this(downloadDir, true, youtubeCookiesFromBrowser, youtubeCookiesFile);
    }

    AudioDownloadService(Path downloadDir, boolean checkDependencies, String youtubeCookiesFromBrowser,
        String youtubeCookiesFile) throws IOException {
        this.downloadDir = downloadDir;
        this.youtubeCookiesFromBrowser = youtubeCookiesFromBrowser == null ? "" : youtubeCookiesFromBrowser.trim();
        this.youtubeCookiesFile = youtubeCookiesFile == null ? "" : youtubeCookiesFile.trim();
        Files.createDirectories(downloadDir);
        if (checkDependencies) {
            checkDependencies();
        }
    }

    /**
     * Downloads a YouTube video as WAV using yt-dlp.
     * Returns a CompletableFuture with the Path to the WAV file, or null on failure.
     */
    public synchronized CompletableFuture<Path> download(final String videoId) {
        CompletableFuture<Path> existing = activeDownloads.get(videoId);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Path> future = CompletableFuture.supplyAsync(new Supplier<Path>() {

            @Override
            public Path get() {
                Path filePath = getFilePath(videoId);

                if (Files.exists(filePath)) {
                    LOGGER.info("HorizonRadio: Using cached audio for " + videoId);
                    return filePath;
                }

                LOGGER.info("HorizonRadio: Downloading audio for " + videoId);
                try {
                    ProcessBuilder processBuilder = new ProcessBuilder(
                        buildDownloadCommand(filePath, videoId, youtubeCookiesFromBrowser, youtubeCookiesFile));
                    processBuilder.redirectErrorStream(true);
                    ProcessResult result = runProcess(processBuilder, DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);

                    if (!result.finished || result.exitCode != 0) {
                        LOGGER.warning("yt-dlp failed for " + videoId + ": " + result.output);
                        logFfmpegHint(result.output);
                        return null;
                    }

                    if (!Files.exists(filePath)) {
                        LOGGER.warning("yt-dlp produced no output file for " + videoId);
                        return null;
                    }

                    return filePath;
                } catch (IOException exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "yt-dlp not found or failed to start for " + videoId + ". Is yt-dlp installed?",
                        exception);
                    return null;
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    LOGGER.log(Level.WARNING, "Download interrupted for " + videoId, exception);
                    return null;
                }
            }
        }, downloadExecutor);
        activeDownloads.put(videoId, future);
        future.whenComplete(new java.util.function.BiConsumer<Path, Throwable>() {

            @Override
            public void accept(Path path, Throwable failure) {
                activeDownloads.remove(videoId, future);
            }
        });
        return future;
    }

    /** Cancels an in-flight yt-dlp download for the given video, if present. */
    public void cancelDownload(String videoId) {
        if (videoId == null) {
            return;
        }
        CompletableFuture<Path> future = activeDownloads.remove(videoId);
        if (future != null) {
            future.cancel(true);
        }
    }

    public CompletableFuture<String> extractPlaylistJson(final String playlistUrl) {
        return CompletableFuture.supplyAsync(new Supplier<String>() {

            @Override
            public String get() {
                try {
                    ProcessResult result = runProcess(
                        new ProcessBuilder(
                            buildPlaylistCommand(playlistUrl, youtubeCookiesFromBrowser, youtubeCookiesFile)),
                        DOWNLOAD_TIMEOUT_MINUTES,
                        TimeUnit.MINUTES);
                    if (!result.finished || result.exitCode != 0) {
                        LOGGER.warning("yt-dlp playlist import failed: " + result.output);
                        return null;
                    }
                    return result.output;
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "yt-dlp playlist import could not start", exception);
                    return null;
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    LOGGER.log(Level.WARNING, "yt-dlp playlist import was interrupted", exception);
                    return null;
                }
            }
        }, downloadExecutor);
    }

    public CompletableFuture<String> extractVideoJson(final String videoUrl) {
        return CompletableFuture.supplyAsync(new Supplier<String>() {

            @Override
            public String get() {
                try {
                    ProcessResult result = runProcess(
                        new ProcessBuilder(
                            buildVideoMetadataCommand(videoUrl, youtubeCookiesFromBrowser, youtubeCookiesFile)),
                        DOWNLOAD_TIMEOUT_MINUTES,
                        TimeUnit.MINUTES);
                    if (!result.finished || result.exitCode != 0) {
                        LOGGER.warning("yt-dlp video import failed: " + result.output);
                        return null;
                    }
                    return result.output;
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "yt-dlp video import could not start", exception);
                    return null;
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    LOGGER.log(Level.WARNING, "yt-dlp video import was interrupted", exception);
                    return null;
                }
            }
        }, downloadExecutor);
    }

    public CompletableFuture<String> extractVideoDurationOutput(final List<String> videoIds) {
        return CompletableFuture.supplyAsync(new Supplier<String>() {

            @Override
            public String get() {
                try {
                    ProcessResult result = runProcess(
                        new ProcessBuilder(
                            buildVideoDurationCommand(videoIds, youtubeCookiesFromBrowser, youtubeCookiesFile)),
                        METADATA_TIMEOUT_MINUTES,
                        TimeUnit.MINUTES);
                    if (!result.finished || result.exitCode != 0) {
                        LOGGER.warning("yt-dlp chart duration lookup failed: " + result.output);
                        return null;
                    }
                    return result.output;
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "yt-dlp chart duration lookup could not start", exception);
                    return null;
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                        .interrupt();
                    LOGGER.log(Level.WARNING, "yt-dlp chart duration lookup was interrupted", exception);
                    return null;
                }
            }
        }, downloadExecutor);
    }

    public Path getFilePath(String videoId) {
        return downloadDir.resolve(videoId + ".wav");
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

    private void checkDependencies() {
        try {
            ProcessResult result = runProcess(
                new ProcessBuilder("yt-dlp", "--version"),
                DEPENDENCY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
            if (result.finished && result.exitCode == 0) {
                ytDlpAvailable = true;
                LOGGER.info("HorizonRadio: Found yt-dlp version " + result.output.trim());
            } else {
                LOGGER.warning("HorizonRadio: yt-dlp check failed. Audio downloads will not work!");
            }
        } catch (IOException exception) {
            LOGGER.warning("HorizonRadio: yt-dlp not found! Please install yt-dlp and ensure it's on the system PATH.");
            LOGGER.warning(
                "HorizonRadio: See https://github.com/yt-dlp/yt-dlp#installation for installation instructions.");
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            LOGGER.log(Level.WARNING, "HorizonRadio: yt-dlp dependency check was interrupted", exception);
        }

        try {
            ProcessResult result = runProcess(
                new ProcessBuilder("ffmpeg", "-version"),
                DEPENDENCY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
            if (result.finished && result.exitCode == 0) {
                ffmpegAvailable = true;
                LOGGER.info("HorizonRadio: Found ffmpeg");
            } else {
                LOGGER.warning("HorizonRadio: ffmpeg check failed. Audio conversion will not work!");
            }
        } catch (IOException exception) {
            LOGGER.warning("HorizonRadio: ffmpeg not found! Please install ffmpeg and ensure it's on the system PATH.");
            LOGGER.warning("HorizonRadio: ffmpeg is required by yt-dlp to convert audio to WAV format.");
            LOGGER.warning("HorizonRadio: See https://ffmpeg.org/download.html for installation instructions.");
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            LOGGER.log(Level.WARNING, "HorizonRadio: ffmpeg dependency check was interrupted", exception);
        }

        if (!ytDlpAvailable || !ffmpegAvailable) {
            LOGGER.warning("HorizonRadio: ============================================================");
            LOGGER.warning("HorizonRadio: AUDIO DOWNLOADS WILL FAIL - Missing required dependencies!");
            LOGGER.warning("HorizonRadio: Please install both yt-dlp and ffmpeg on the server.");
            LOGGER.warning("HorizonRadio: ============================================================");
        }
    }

    public boolean isDependenciesAvailable() {
        return ytDlpAvailable && ffmpegAvailable;
    }

    public void shutdown() {
        downloadExecutor.shutdownNow();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS);
        while (!downloadExecutor.isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                downloadExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
                downloadExecutor.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread()
                .interrupt();
        }
        if (!downloadExecutor.isTerminated()) {
            LOGGER.warning(
                "HorizonRadio: Audio download executor did not terminate within " + EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS
                    + " ms");
        }
        LOGGER.info("HorizonRadio: Audio download service shut down");
    }

    static List<String> buildDownloadCommand(Path filePath, String videoId) {
        return buildDownloadCommand(filePath, videoId, "", "");
    }

    static List<String> buildDownloadCommand(Path filePath, String videoId, String cookiesFromBrowser,
        String cookiesFile) {
        List<String> command = new ArrayList<String>(
            java.util.Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "-f",
                "bestaudio[ext=m4a]/bestaudio/best",
                "--retries",
                "3",
                "--fragment-retries",
                "3",
                "--sleep-requests",
                "1",
                "-x",
                "--audio-format",
                "wav",
                "--audio-quality",
                "0",
                "-o",
                filePath.toString(),
                "--no-playlist",
                "https://www.youtube.com/watch?v=" + videoId));
        addCookieOptions(command, cookiesFromBrowser, cookiesFile);
        return command;
    }

    static List<String> buildPlaylistCommand(String playlistUrl) {
        return buildPlaylistCommand(playlistUrl, "", "");
    }

    static List<String> buildPlaylistCommand(String playlistUrl, String cookiesFromBrowser, String cookiesFile) {
        List<String> command = new ArrayList<String>(
            java.util.Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "--flat-playlist",
                "--dump-single-json",
                "--skip-download",
                "--quiet",
                "--no-warnings",
                "--yes-playlist",
                playlistUrl));
        addCookieOptions(command, cookiesFromBrowser, cookiesFile);
        return command;
    }

    static List<String> buildVideoMetadataCommand(String videoUrl) {
        return buildVideoMetadataCommand(videoUrl, "", "");
    }

    static List<String> buildVideoMetadataCommand(String videoUrl, String cookiesFromBrowser, String cookiesFile) {
        List<String> command = new ArrayList<String>(
            java.util.Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "--dump-single-json",
                "--skip-download",
                "--quiet",
                "--no-warnings",
                "--no-playlist",
                videoUrl));
        addCookieOptions(command, cookiesFromBrowser, cookiesFile);
        return command;
    }

    static List<String> buildVideoDurationCommand(List<String> videoIds) {
        return buildVideoDurationCommand(videoIds, "", "");
    }

    static List<String> buildVideoDurationCommand(List<String> videoIds, String cookiesFromBrowser,
        String cookiesFile) {
        List<String> command = new ArrayList<String>(
            java.util.Arrays.asList(
                "yt-dlp",
                "--extractor-args",
                "youtube:player_client=android",
                "--print",
                "%(id)s\\t%(duration_string)s",
                "--skip-download",
                "--quiet",
                "--no-warnings",
                "--ignore-errors",
                "--no-playlist",
                "--retries",
                "1"));
        if (videoIds != null) {
            for (String videoId : videoIds) {
                if (videoId != null && videoId.trim()
                    .length() > 0) {
                    command.add("https://www.youtube.com/watch?v=" + videoId.trim());
                }
            }
        }
        addCookieOptions(command, cookiesFromBrowser, cookiesFile);
        return command;
    }

    private static void addCookieOptions(List<String> command, String cookiesFromBrowser, String cookiesFile) {
        if (cookiesFile != null && cookiesFile.trim()
            .length() > 0) {
            command.add("--cookies");
            command.add(cookiesFile.trim());
        } else if (cookiesFromBrowser != null && cookiesFromBrowser.trim()
            .length() > 0) {
                command.add("--cookies-from-browser");
                command.add(cookiesFromBrowser.trim());
            }
    }

    static String collectProcessOutput(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        byte[] buffer = new byte[4096];
        int storedBytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (storedBytes < maxBytes) {
                int bytesToStore = Math.min(count, maxBytes - storedBytes);
                output.write(buffer, 0, bytesToStore);
                storedBytes += bytesToStore;
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ProcessResult runProcess(ProcessBuilder processBuilder, long timeout, TimeUnit timeUnit)
        throws IOException, InterruptedException {
        processBuilder.redirectErrorStream(true);
        return runProcess(processBuilder.start(), timeout, timeUnit);
    }

    private static ProcessResult runProcess(Process process, long timeout, TimeUnit timeUnit)
        throws IOException, InterruptedException {
        OutputCollector outputCollector = new OutputCollector(process.getInputStream(), MAX_PROCESS_OUTPUT_BYTES);
        outputCollector.start();
        boolean finished = false;
        InterruptedException interruption = null;
        try {
            finished = process.waitFor(timeout, timeUnit);
        } catch (InterruptedException exception) {
            interruption = exception;
        } finally {
            try {
                if (!finished) {
                    terminateProcess(process);
                }
            } catch (InterruptedException exception) {
                interruption = exception;
            }
            if (finished) {
                outputCollector.awaitCompletionAfterNormalCompletion();
                closeProcessStreams(process);
            } else {
                closeProcessStreams(process);
                outputCollector.awaitCompletion();
            }
        }

        if (interruption != null) {
            throw interruption;
        }
        return new ProcessResult(finished, finished ? process.exitValue() : -1, outputCollector.getOutput());
    }

    private static void terminateProcess(Process process) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }

        boolean interrupted = false;
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                TerminationResult termination = waitForTermination(process);
                interrupted = termination.interrupted;
                logProcessTerminationFailure(process, termination.terminated);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            process.destroyForcibly();
            TerminationResult termination = waitForTermination(process);
            interrupted = termination.interrupted || interrupted;
            logProcessTerminationFailure(process, termination.terminated);
        }
        if (interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new InterruptedException("Interrupted while terminating process");
        }
    }

    private static TerminationResult waitForTermination(Process process) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TERMINATION_TIMEOUT_MILLIS);
        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return new TerminationResult(!process.isAlive(), interrupted);
    }

    private static void logProcessTerminationFailure(Process process, boolean terminated) {
        if (!terminated && process.isAlive()) {
            LOGGER.warning(
                "HorizonRadio: Audio process did not terminate within " + PROCESS_TERMINATION_TIMEOUT_MILLIS
                    + " ms after forced cleanup");
        }
    }

    private static void closeProcessStreams(Process process) {
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup must continue even if a process stream is already closed.
        }
    }

    private static void logFfmpegHint(String output) {
        if (output.contains("ffprobe and ffmpeg not found") || output.contains("ffmpeg not found")) {
            LOGGER.warning("HorizonRadio: ffmpeg is not installed! Please install ffmpeg to enable audio downloads.");
            LOGGER.warning(
                "HorizonRadio: On Windows: winget install ffmpeg OR choco install ffmpeg OR scoop install ffmpeg");
            LOGGER.warning("HorizonRadio: On Linux: sudo apt install ffmpeg OR sudo dnf install ffmpeg");
            LOGGER.warning("HorizonRadio: On macOS: brew install ffmpeg");
        }
    }

    private static final class TerminationResult {

        private final boolean terminated;
        private final boolean interrupted;

        private TerminationResult(boolean terminated, boolean interrupted) {
            this.terminated = terminated;
            this.interrupted = interrupted;
        }
    }

    private static final class ProcessResult {

        private final boolean finished;
        private final int exitCode;
        private final String output;

        private ProcessResult(boolean finished, int exitCode, String output) {
            this.finished = finished;
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class OutputCollector implements Runnable {

        private final InputStream input;
        private final int maxBytes;
        private final Thread thread;
        private volatile String output = "";

        private OutputCollector(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
            this.thread = new Thread(this, "HorizonRadio-Downloader-Output");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        @Override
        public void run() {
            try {
                output = collectProcessOutput(input, maxBytes);
            } catch (IOException exception) {
                LOGGER.log(Level.FINE, "Could not collect process output", exception);
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // The process may already have closed its output stream.
                }
            }
        }

        private void closeInput() {
            try {
                input.close();
            } catch (IOException ignored) {
                // The process may already have closed its output stream.
            }
        }

        private void awaitCompletion() {
            awaitCompletion(true);
        }

        private void awaitCompletionAfterNormalCompletion() {
            awaitCompletion(false);
        }

        private void awaitCompletion(boolean closeBeforeJoin) {
            if (closeBeforeJoin) {
                closeInput();
            }
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_OUTPUT_JOIN_TIMEOUT_MILLIS);
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
                    thread.join(Math.max(1L, remainingMillis));
                } catch (InterruptedException exception) {
                    interrupted = true;
                    closeInput();
                    thread.interrupt();
                }
            }
            if (thread.isAlive()) {
                closeInput();
                thread.interrupt();
                LOGGER.warning(
                    "HorizonRadio: Audio process output collector did not terminate within "
                        + PROCESS_OUTPUT_JOIN_TIMEOUT_MILLIS
                        + " ms");
            }
            if (interrupted) {
                Thread.currentThread()
                    .interrupt();
            }
        }

        private String getOutput() {
            return output;
        }
    }
}
