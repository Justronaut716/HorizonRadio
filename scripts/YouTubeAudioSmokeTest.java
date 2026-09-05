import com.horizonradio.server.media.JavaAudioDownloadBackend;
import com.horizonradio.server.media.YouTubeUrlParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Manual, one-shot smoke test for the production YouTube audio downloader. */
public final class YouTubeAudioSmokeTest {

    private static final String DEFAULT_VIDEO_ID = "M7lc1UVf-VE";

    private YouTubeAudioSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String[] inputs = args.length == 0 ? new String[] { DEFAULT_VIDEO_ID } : args;
        Path outputDirectory = Paths.get("build", "yt-audio-smoke");
        Files.createDirectories(outputDirectory);
        JavaAudioDownloadBackend backend = new JavaAudioDownloadBackend();
        int failures = 0;

        for (String input : inputs) {
            String videoId = input.matches("[A-Za-z0-9_-]{11}") ? input : YouTubeUrlParser.parseVideoId(input);
            Path output = outputDirectory.resolve(videoId + ".wav");
            try {
                backend.download(videoId, output, () -> false);
                System.out.printf("PASS %s -> %,d bytes%n", videoId, Files.size(output));
            } catch (Exception failure) {
                failures++;
                System.out.printf("FAIL %s -> %s%n", videoId, rootMessage(failure));
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
