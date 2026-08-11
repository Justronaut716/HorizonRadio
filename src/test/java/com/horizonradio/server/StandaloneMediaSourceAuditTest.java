package com.horizonradio.server;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;

/** Audits production inputs and, when supplied, the standalone artifact. */
public class StandaloneMediaSourceAuditTest {

    private static final Pattern[] FORBIDDEN_SOURCE_PATTERNS = {
        Pattern.compile("(?i)\\bProcessBuilder\\b"),
        Pattern.compile("(?i)Runtime\\.getRuntime\\s*\\(\\s*\\)\\s*\\.exec\\s*\\("),
        Pattern.compile("(?i)\\b(ffmpeg|yt-dlp|youtube-dl)\\b"),
        Pattern.compile("(?i)(^|[^A-Za-z0-9_])\\.(?:dll|so|dylib)(?=$|[^A-Za-z0-9_])")
    };

    @Test
    public void productionSourcesContainNoExternalMediaRuntimeReferences() throws Exception {
        List<String> violations = new ArrayList<String>();
        for (Path file : productionTextFiles()) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Pattern pattern : FORBIDDEN_SOURCE_PATTERNS) {
                if (pattern.matcher(source).find()) {
                    violations.add(file.toString() + " matches " + pattern.pattern());
                }
            }
        }
        assertTrue("Production external-media audit failed: " + violations, violations.isEmpty());
    }

    @Test
    public void configuredArtifactContainsNoExternalMediaRuntimeEntries() throws Exception {
        String artifact = System.getProperty("horizonradio.test.artifact");
        Assume.assumeTrue("artifact property is required for package verification", artifact != null);

        List<String> violations = new ArrayList<String>();
        try (ZipFile zip = new ZipFile(artifact)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isForbiddenArtifactName(name)) {
                    violations.add(name);
                }
                if (!entry.isDirectory() && isTextArtifact(name)) {
                    String content = new String(readAll(zip.getInputStream(entry)), StandardCharsets.UTF_8);
                    for (Pattern pattern : FORBIDDEN_SOURCE_PATTERNS) {
                        if (pattern.matcher(content).find()) {
                            violations.add(name + " matches " + pattern.pattern());
                        }
                    }
                }
            }
        }
        Collections.sort(violations);
        assertTrue("Packaged external-media audit failed: " + violations, violations.isEmpty());
    }

    private static List<Path> productionTextFiles() throws IOException {
        List<Path> files = new ArrayList<Path>();
        addProductionTextFiles(files, Paths.get("src", "main", "java"));
        addProductionTextFiles(files, Paths.get("src", "main", "resources"));
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.toString().compareTo(right.toString());
            }
        });
        return files;
    }

    private static void addProductionTextFiles(List<Path> files, Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                .filter(StandaloneMediaSourceAuditTest::isSourceOrTextResource)
                .forEach(files::add);
        }
    }

    private static boolean isSourceOrTextResource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
        return name.endsWith(".java") || name.endsWith(".txt") || name.endsWith(".json")
            || name.endsWith(".info") || name.endsWith(".properties") || name.endsWith(".xml")
            || name.endsWith(".mf");
    }

    private static boolean isForbiddenArtifactName(String name) {
        String normalized = name.toLowerCase(Locale.ENGLISH);
        return normalized.contains("ffmpeg") || normalized.contains("yt-dlp") || normalized.contains("youtube-dl")
            || normalized.matches(".*\\.(dll|so|dylib)(\\.[0-9]+)?$");
    }

    private static boolean isTextArtifact(String name) {
        String normalized = name.toLowerCase(Locale.ENGLISH);
        return normalized.endsWith(".txt") || normalized.endsWith(".json") || normalized.endsWith(".info")
            || normalized.endsWith(".properties") || normalized.endsWith(".xml") || normalized.endsWith(".mf");
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        }
    }
}
