package com.horizonradio.server.media;

import java.net.URI;
import java.net.URLDecoder;

/** Validates public YouTube links before an InnerTube request is constructed. */
public final class YouTubeUrlParser {

    private static final int MAX_URL_LENGTH = 2048;

    private YouTubeUrlParser() {
    }

    public static String parseVideoId(String value) throws MediaException {
        if (value == null || value.length() == 0 || value.length() > MAX_URL_LENGTH) {
            throw new MediaException("YouTube URL is missing or exceeds its limit");
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || !isYouTubeHost(uri.getHost())) {
                throw new MediaException("URL is not a safe YouTube URL");
            }
            String queryId = queryValue(uri.getRawQuery(), "v");
            if (queryValue(uri.getRawQuery(), "redirect") != null) {
                throw new MediaException("YouTube URL contains an unsafe redirect");
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            String id;
            if ("youtu.be".equalsIgnoreCase(uri.getHost())) {
                id = firstPathSegment(path);
            } else if (path.startsWith("/shorts/")) {
                id = firstPathSegment(path.substring("/shorts".length()));
            } else if (path.startsWith("/live/")) {
                id = firstPathSegment(path.substring("/live".length()));
            } else if ("/watch".equals(path)) {
                id = queryId;
            } else {
                throw new MediaException("YouTube URL does not identify a video");
            }
            return requireVideoId(id);
        } catch (MediaException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MediaException("Invalid YouTube URL", exception);
        }
    }

    public static String requireVideoId(String videoId) throws MediaException {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new MediaException("Invalid YouTube video ID");
        }
        return videoId;
    }

    static boolean isYouTubeHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return "youtube.com".equals(normalized) || normalized.endsWith(".youtube.com") || "youtu.be".equals(normalized);
    }

    private static String firstPathSegment(String path) {
        String remaining = path.startsWith("/") ? path.substring(1) : path;
        int end = remaining.indexOf('/');
        return end < 0 ? remaining : remaining.substring(0, end);
    }

    private static String queryValue(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = decode(equals < 0 ? part : part.substring(0, equals));
            if (name.equals(key)) return decode(equals < 0 ? "" : part.substring(equals + 1));
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 is required by the JDK", exception);
        }
    }
}
