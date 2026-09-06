import com.horizonradio.server.media.JavaAudioDownloadBackend;
import com.horizonradio.server.media.YouTubeMediaModels;
import com.horizonradio.server.media.YouTubeUrlParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Manual, one-shot smoke test for the production YouTube audio downloader. */
public final class YouTubeAudioSmokeTest {

    private static final String DEFAULT_VIDEO_ID = "M7lc1UVf-VE";
    private static final long WATCHDOG_DELAY_MILLIS = 10000L;

    private YouTubeAudioSmokeTest() {}

    public static void main(String[] args) throws Exception {
        YouTubeMediaModels.preferIpv6ForClientMedia();
        startWatchdog();
        String[] inputs = args.length == 0 ? new String[] { DEFAULT_VIDEO_ID } : args;
        Path outputDirectory = Paths.get("build", "yt-audio-smoke");
        Files.createDirectories(outputDirectory);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend();
        int failures = 0;

        for (String input : inputs) {
            String videoId = input.matches("[A-Za-z0-9_-]{11}") ? input : YouTubeUrlParser.parseVideoId(input);
            Path output = outputDirectory.resolve(videoId + ".wav");
            System.out.printf("START %s: entering backend.download (resolution + transfer + decode)%n", videoId);
            System.out.flush();
            try {
                backend.download(videoId, output, () -> false);
                System.out.printf("PASS %s -> %,d bytes%n", videoId, Files.size(output));
            } catch (Exception failure) {
                failures++;
                System.out.printf("FAIL %s -> %s%n", videoId, rootMessage(failure));
                failure.printStackTrace(System.out);
            }
            System.out.flush();
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void startWatchdog() {
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(WATCHDOG_DELAY_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                System.err.println("WATCHDOG: backend is still running after 10 seconds; thread dump follows");
                for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    System.err.println("\nThread: " + entry.getKey().getName() + " (" + entry.getKey().getState() + ")");
                    for (StackTraceElement frame : entry.getValue()) System.err.println("  at " + frame);
                }
                System.err.flush();
            }
        }, "youtube-audio-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
