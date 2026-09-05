package com.horizonradio.server.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Resolves one bounded, supported YouTube adaptive audio stream without downloading it. */
public final class YouTubeStreamResolver {

    private static final String CLIENT_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final ClientProfile ANDROID_VR_CLIENT = new ClientProfile(
        "ANDROID_VR",
        "1.65.10",
        "28",
        CLIENT_USER_AGENT,
        "Oculus",
        "Quest 3",
        32,
        "Android",
        "12L");
    private static final ClientProfile VISIONOS_CLIENT = new ClientProfile(
        "VISIONOS",
        "1.02",
        "101",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        "Apple",
        "RealityDevice17,1",
        0,
        "visionOS",
        "26.5.23O471");
    private static final ClientProfile IOS_CLIENT = new ClientProfile(
        "IOS",
        "20.10.4",
        "5",
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X)",
        "",
        "",
        0,
        "iOS",
        "18.1");
    private static final String WATCH_PAGE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    private static final Logger LOGGER = Logger.getLogger(YouTubeStreamResolver.class.getName());
    private static final URL PLAYER_URL;
    private static final int TIMEOUT_MILLIS = 15000;
    private static final long MAX_PLAYER_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_PLAYER_SCRIPT_BYTES = 1024L * 1024L;
    private static final long MAX_WATCH_PAGE_BYTES = 4L * 1024L * 1024L;
    private static final long VISITOR_DATA_CACHE_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_MEDIA_URL_LENGTH = 4096;
    private static final Pattern VISITOR_DATA_PATTERN = Pattern
        .compile("\\\"(?:VISITOR_DATA|visitorData)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    static {
        try {
            PLAYER_URL = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private final YouTubeMediaModels.HttpRequester requester;
    private final AudioDecoderRegistry registry;
    private final LongSupplier clock;
    private final String signaturePlan;
    private final String nPlan;
    private final boolean extractPlayerTransforms;
    private final Object visitorDataLock = new Object();
    private volatile String cachedVisitorData = "";
    private volatile long visitorDataExpiresAtMillis;

    public YouTubeStreamResolver() {
        this(new YouTubeMediaModels.UrlConnectionHttpRequester(), new AudioDecoderRegistry(), new LongSupplier() {

            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    public YouTubeStreamResolver(YouTubeMediaModels.HttpRequester requester, AudioDecoderRegistry registry,
        LongSupplier clock) {
        this(requester, registry, clock, null, null, true);
    }

    public YouTubeStreamResolver(YouTubeMediaModels.HttpRequester requester, AudioDecoderRegistry registry,
        LongSupplier clock, String signaturePlan, String nPlan) {
        this(requester, registry, clock, signaturePlan, nPlan, false);
    }

    private YouTubeStreamResolver(YouTubeMediaModels.HttpRequester requester, AudioDecoderRegistry registry,
        LongSupplier clock, String signaturePlan, String nPlan, boolean extractPlayerTransforms) {
        if (requester == null || registry == null || clock == null)
            throw new IllegalArgumentException("Resolver dependencies are required");
        this.requester = requester;
        this.registry = registry;
        this.clock = clock;
        this.signaturePlan = safePlan(signaturePlan);
        this.nPlan = safePlan(nPlan);
        this.extractPlayerTransforms = extractPlayerTransforms;
    }

    public YouTubeMediaModels.ResolvedAudioStream resolveAudio(String videoId) throws IOException {
        return resolveAudioCandidates(videoId).getPrimaryCandidates()
            .get(0);
    }

    public ResolvedAudioCandidates resolveAudioCandidates(String videoId) throws IOException {
        String safeVideoId = YouTubeUrlParser.requireVideoId(videoId);
        String visitorData = "";
        IOException visitorFailure = null;
        try {
            visitorData = resolveVisitorData(safeVideoId);
        } catch (IOException exception) {
            visitorFailure = exception;
            LOGGER.log(
                Level.WARNING,
                "YouTube visitor data is unavailable for " + safeVideoId
                    + "; stream requests will continue without a visitor id",
                exception);
        }
        IOException visionOsFailure;
        final String resolvedVisitorData = visitorData;
        try {
            final List<YouTubeMediaModels.ResolvedAudioStream> primary = resolveAudioWithClient(
                safeVideoId,
                resolvedVisitorData,
                VISIONOS_CLIENT);
            return new ResolvedAudioCandidates(primary, new AlternativeResolver() {

                @Override
                public List<YouTubeMediaModels.ResolvedAudioStream> resolve() throws IOException {
                    return resolveFallbackClients(safeVideoId, resolvedVisitorData);
                }
            });
        } catch (ClientUnavailableException exception) {
            visionOsFailure = exception;
        }
        try {
            return new ResolvedAudioCandidates(
                resolveAudioWithClient(safeVideoId, resolvedVisitorData, ANDROID_VR_CLIENT),
                new AlternativeResolver() {

                    @Override
                    public List<YouTubeMediaModels.ResolvedAudioStream> resolve() throws IOException {
                        return resolveAudioWithClient(safeVideoId, resolvedVisitorData, IOS_CLIENT);
                    }
                });
        } catch (IOException androidFailure) {
            try {
                return new ResolvedAudioCandidates(
                    resolveAudioWithClient(safeVideoId, resolvedVisitorData, IOS_CLIENT),
                    null);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(androidFailure);
                fallbackFailure.addSuppressed(visionOsFailure);
                if (visitorFailure != null) fallbackFailure.addSuppressed(visitorFailure);
                throw fallbackFailure;
            }
        }
    }

    private List<YouTubeMediaModels.ResolvedAudioStream> resolveFallbackClients(String videoId, String visitorData)
        throws IOException {
        List<YouTubeMediaModels.ResolvedAudioStream> candidates = new ArrayList<YouTubeMediaModels.ResolvedAudioStream>();
        IOException androidFailure = null;
        try {
            candidates.addAll(resolveAudioWithClient(videoId, visitorData, ANDROID_VR_CLIENT));
        } catch (IOException exception) {
            androidFailure = exception;
        }
        try {
            candidates.addAll(resolveAudioWithClient(videoId, visitorData, IOS_CLIENT));
        } catch (IOException iosFailure) {
            if (androidFailure != null) iosFailure.addSuppressed(androidFailure);
            if (candidates.isEmpty()) throw iosFailure;
        }
        return candidates;
    }

    /**
     * Discards cached visitor data so the next resolution fetches a fresh visitor id. Callers should do this when
     * YouTube rejects a stream fetched under the cached visitor id (for example with HTTP 403), because the cached id
     * is then burned until its TTL expires on its own.
     */
    public void invalidateVisitorCache() {
        synchronized (visitorDataLock) {
            cachedVisitorData = "";
            visitorDataExpiresAtMillis = 0L;
        }
    }

    private List<YouTubeMediaModels.ResolvedAudioStream> resolveAudioWithClient(String videoId, String visitorData,
        ClientProfile client) throws IOException {
        byte[] body = buildRequestBody(videoId, client).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("User-Agent", client.userAgent);
        headers.put("X-YouTube-Client-Name", client.headerName);
        headers.put("X-YouTube-Client-Version", client.version);
        if (visitorData.length() > 0) headers.put("X-Goog-Visitor-Id", visitorData);
        try (YouTubeMediaModels.HttpResponse response = requester.post(
            PLAYER_URL,
            headers,
            body,
            TIMEOUT_MILLIS,
            MAX_PLAYER_BYTES,
            YouTubeMediaModels.RedirectPolicy.INNER_TUBE)) {
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300
                || !YouTubeUrlParser.isYouTubeHost(
                    response.getUrl()
                        .getHost())) {
                throw new MediaException("InnerTube player response is not trusted");
            }
            if (response.getContentLength() < 0L || response.getContentLength() > MAX_PLAYER_BYTES) {
                throw new MediaException("InnerTube player response exceeds its byte limit");
            }
            JsonObject root = parse(
                readExact(
                    response.getInputStream(),
                    response.getContentLength(),
                    MAX_PLAYER_BYTES,
                    "InnerTube player response"));
            return select(root, resolveTransformPlans(root), visitorData);
        }
    }

    private String resolveVisitorData(String videoId) throws IOException {
        long now = clock.getAsLong();
        String cached = cachedVisitorData;
        if (cached.length() > 0 && now < visitorDataExpiresAtMillis) return cached;
        synchronized (visitorDataLock) {
            now = clock.getAsLong();
            cached = cachedVisitorData;
            if (cached.length() > 0 && now < visitorDataExpiresAtMillis) return cached;
            URL watchUrl = new URL("https://www.youtube.com/watch?v=" + videoId);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Accept", "text/html,application/xhtml+xml");
            headers.put("Accept-Language", "en-US,en;q=0.8");
            headers.put("User-Agent", WATCH_PAGE_USER_AGENT);
            try (YouTubeMediaModels.HttpResponse response = requester.get(
                watchUrl,
                headers,
                TIMEOUT_MILLIS,
                MAX_WATCH_PAGE_BYTES,
                YouTubeMediaModels.RedirectPolicy.INNER_TUBE)) {
                if (response.getStatusCode() < 200 || response.getStatusCode() >= 300
                    || !YouTubeUrlParser.isYouTubeHost(
                        response.getUrl()
                            .getHost())) {
                    throw new MediaException("YouTube watch page response is not trusted");
                }
                String page = readExact(
                    response.getInputStream(),
                    response.getContentLength(),
                    MAX_WATCH_PAGE_BYTES,
                    "YouTube watch page");
                Matcher matcher = VISITOR_DATA_PATTERN.matcher(page);
                if (!matcher.find()) throw new MediaException("YouTube watch page has no visitor data");
                String visitorData = matcher.group(1);
                cachedVisitorData = visitorData;
                visitorDataExpiresAtMillis = now + VISITOR_DATA_CACHE_MILLIS;
                return visitorData;
            }
        }
    }

    static String applyTransform(String value, String plan) throws MediaException {
        String transformed = value == null ? "" : value;
        for (String operation : safePlan(plan).split(",")) {
            if ("identity".equals(operation)) continue;
            if ("reverse".equals(operation)) {
                transformed = new StringBuilder(transformed).reverse()
                    .toString();
                continue;
            }
            if (operation.startsWith("slice:") || operation.startsWith("splice:")) {
                transformed = transformed.substring(boundedIndex(operation, transformed.length()));
                continue;
            }
            if (operation.startsWith("swap:")) {
                int index = boundedIndex(operation, transformed.length());
                if (transformed.length() > 0) {
                    char[] chars = transformed.toCharArray();
                    char first = chars[0];
                    chars[0] = chars[index];
                    chars[index] = first;
                    transformed = new String(chars);
                }
                continue;
            }
            throw new MediaException("Unsupported YouTube transform operation");
        }
        return transformed;
    }

    private List<YouTubeMediaModels.ResolvedAudioStream> select(JsonObject root, TransformPlans transformPlans,
        String visitorData) throws IOException {
        JsonObject streaming = object(root, "streamingData");
        JsonArray formats = streaming == null ? null : streaming.getAsJsonArray("adaptiveFormats");
        if (formats == null || formats.size() == 0)
            throw new ClientUnavailableException("YouTube player response has no adaptive audio formats");
        long rootExpiry = relativeExpiry(root);
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (JsonElement element : formats) {
            if (!element.isJsonObject()) continue;
            Candidate candidate = candidate(element.getAsJsonObject(), rootExpiry, transformPlans);
            if (candidate != null && registry.supports(candidate.format)) candidates.add(candidate);
        }
        if (candidates.isEmpty())
            throw new ClientUnavailableException("YouTube player response has no supported audio stream");
        Collections.sort(candidates, new Comparator<Candidate>() {

            @Override
            public int compare(Candidate left, Candidate right) {
                int preference = left.preference - right.preference;
                return preference != 0 ? preference : right.bitrate - left.bitrate;
            }
        });
        long now = clock.getAsLong();
        List<YouTubeMediaModels.ResolvedAudioStream> resolved = new ArrayList<YouTubeMediaModels.ResolvedAudioStream>();
        Set<String> seenUrls = new HashSet<String>();
        for (Candidate candidate : candidates) {
            if (candidate.expiresAtMillis <= now || !seenUrls.add(candidate.url.toExternalForm())) continue;
            resolved.add(
                new YouTubeMediaModels.ResolvedAudioStream(
                    candidate.url,
                    candidate.format,
                    candidate.bitrate,
                    candidate.expiresAtMillis,
                    visitorData));
        }
        if (resolved.isEmpty()) throw new MediaException("YouTube stream URLs have expired");
        return Collections.unmodifiableList(resolved);
    }

    private Candidate candidate(JsonObject format, long rootExpiry, TransformPlans transformPlans) throws IOException {
        String mime = string(format, "mimeType").toLowerCase(Locale.ROOT);
        MediaFormat mediaFormat = fromMime(mime);
        if (mediaFormat == MediaFormat.UNKNOWN || !mime.startsWith("audio/")) return null;
        URL url = streamUrl(format, transformPlans);
        if (url == null || !isSafeMediaUrl(url)) return null;
        long urlExpiry = urlExpiry(url);
        long expiry = minPositive(rootExpiry, urlExpiry);
        if (expiry == Long.MAX_VALUE) throw new MediaException("YouTube stream URL has no finite expiry");
        int bitrate = Math.max(0, integer(format, "bitrate"));
        return new Candidate(url, mediaFormat, bitrate, expiry, preference(mediaFormat));
    }

    private URL streamUrl(JsonObject format, TransformPlans transformPlans) throws IOException {
        String direct = string(format, "url");
        if (direct.length() > 0) {
            URL url = new URL(direct);
            String n = queryValue(url.getQuery(), "n");
            return n == null ? url : replaceParameter(url, "n", applyTransform(n, transformPlans.nPlan));
        }
        String cipher = string(format, "signatureCipher");
        if (cipher.length() == 0) cipher = string(format, "cipher");
        if (cipher.length() == 0) return null;
        Map<String, String> parameters = decodeQuery(cipher);
        String source = parameters.get("url");
        if (source == null || source.length() == 0) return null;
        URL url = new URL(source);
        String signature = parameters.get("s");
        if (signature != null) url = replaceParameter(
            url,
            parameters.containsKey("sp") ? parameters.get("sp") : "signature",
            applyTransform(signature, transformPlans.signaturePlan));
        String n = queryValue(url.getQuery(), "n");
        return n == null ? url : replaceParameter(url, "n", applyTransform(n, transformPlans.nPlan));
    }

    private static String buildRequestBody(String videoId, ClientProfile clientProfile) {
        JsonObject client = new JsonObject();
        client.addProperty("clientName", clientProfile.name);
        client.addProperty("clientVersion", clientProfile.version);
        client.addProperty("userAgent", clientProfile.userAgent);
        if (clientProfile.deviceMake.length() > 0) client.addProperty("deviceMake", clientProfile.deviceMake);
        if (clientProfile.deviceModel.length() > 0) client.addProperty("deviceModel", clientProfile.deviceModel);
        if (clientProfile.androidSdkVersion > 0) {
            client.addProperty("androidSdkVersion", clientProfile.androidSdkVersion);
        }
        if (clientProfile.osName.length() > 0) client.addProperty("osName", clientProfile.osName);
        if (clientProfile.osVersion.length() > 0) client.addProperty("osVersion", clientProfile.osVersion);
        client.addProperty("hl", "en");
        JsonObject context = new JsonObject();
        context.add("client", client);
        JsonObject request = new JsonObject();
        request.add("context", context);
        request.addProperty("videoId", videoId);
        JsonObject contentPlaybackContext = new JsonObject();
        contentPlaybackContext.addProperty("html5Preference", "HTML5_PREF_WANTS");
        JsonObject playbackContext = new JsonObject();
        playbackContext.add("contentPlaybackContext", contentPlaybackContext);
        request.add("playbackContext", playbackContext);
        request.addProperty("contentCheckOk", true);
        request.addProperty("racyCheckOk", true);
        return new Gson().toJson(request);
    }

    private static JsonObject parse(String json) throws IOException {
        try {
            JsonObject result = new Gson().fromJson(json, JsonObject.class);
            if (result == null) throw new MediaException("Empty InnerTube player response");
            return result;
        } catch (RuntimeException exception) {
            throw new MediaException("Invalid InnerTube player JSON", exception);
        }
    }

    private TransformPlans resolveTransformPlans(JsonObject root) throws IOException {
        if (!requiresPlayerTransforms(root)) {
            return new TransformPlans(signaturePlan, nPlan);
        }
        if (!extractPlayerTransforms) {
            return new TransformPlans(signaturePlan, nPlan);
        }
        JsonObject assets = object(root, "assets");
        String playerScript = string(assets, "js");
        if (playerScript.length() == 0) {
            throw new MediaException("Ciphered YouTube stream has no player JavaScript URL");
        }
        URL scriptUrl = new URL(playerScript);
        if (!"https".equalsIgnoreCase(scriptUrl.getProtocol())
            || !YouTubeUrlParser.isYouTubeHost(scriptUrl.getHost())) {
            throw new MediaException("YouTube player JavaScript URL is not trusted");
        }
        try (YouTubeMediaModels.HttpResponse response = requester.get(
            scriptUrl,
            Collections.<String, String>emptyMap(),
            TIMEOUT_MILLIS,
            MAX_PLAYER_SCRIPT_BYTES,
            YouTubeMediaModels.RedirectPolicy.INNER_TUBE)) {
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300
                || !YouTubeUrlParser.isYouTubeHost(
                    response.getUrl()
                        .getHost())
                || response.getContentLength() < 0L
                || response.getContentLength() > MAX_PLAYER_SCRIPT_BYTES) {
                throw new MediaException("YouTube player JavaScript response is not trusted");
            }
            return PlayerScriptTransformExtractor.extract(
                readExact(
                    response.getInputStream(),
                    response.getContentLength(),
                    MAX_PLAYER_SCRIPT_BYTES,
                    "YouTube player JavaScript"));
        }
    }

    private static boolean requiresPlayerTransforms(JsonObject root) {
        JsonObject streaming = object(root, "streamingData");
        JsonArray formats = streaming == null ? null : streaming.getAsJsonArray("adaptiveFormats");
        if (formats == null) return false;
        for (JsonElement element : formats) {
            if (!element.isJsonObject()) continue;
            JsonObject format = element.getAsJsonObject();
            if (string(format, "signatureCipher").length() > 0 || string(format, "cipher").length() > 0) return true;
            try {
                String direct = string(format, "url");
                if (direct.length() > 0 && queryValue(new URL(direct).getQuery(), "n") != null) return true;
            } catch (IOException exception) {
                return true;
            }
        }
        return false;
    }

    private static String readExact(InputStream input, long declaredLength, long maximum, String description)
        throws IOException {
        if (declaredLength < 0L || declaredLength > maximum) {
            throw new MediaException(description + " exceeds its byte limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (total > maximum - count) throw new MediaException(description + " exceeds its byte limit");
            output.write(buffer, 0, count);
            total += count;
        }
        if (total != declaredLength)
            throw new MediaException(description + " does not match its declared Content-Length");
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static MediaFormat fromMime(String mime) {
        if (mime.startsWith("audio/mp4") && mime.contains("mp4a")) return MediaFormat.M4A;
        if (mime.startsWith("audio/webm") && mime.contains("opus")) return MediaFormat.WEBM_OPUS;
        if (mime.startsWith("audio/aac")) return MediaFormat.AAC;
        if (mime.startsWith("audio/mpeg")) return MediaFormat.MP3;
        if (mime.startsWith("audio/wav") || mime.startsWith("audio/wave")) return MediaFormat.WAV;
        return MediaFormat.UNKNOWN;
    }

    private static int preference(MediaFormat format) {
        if (format == MediaFormat.WEBM_OPUS) return 0;
        if (format == MediaFormat.M4A) return 1;
        if (format == MediaFormat.AAC) return 2;
        if (format == MediaFormat.MP3) return 3;
        return 4;
    }

    static boolean isSafeMediaUrl(URL url) {
        if (url.toExternalForm()
            .length() > MAX_MEDIA_URL_LENGTH || url.getUserInfo() != null
            || url.getRef() != null
            || !"https".equalsIgnoreCase(url.getProtocol())) return false;
        String host = url.getHost() == null ? ""
            : url.getHost()
                .toLowerCase(Locale.ROOT);
        return host.endsWith(".googlevideo.com") || "googlevideo.com".equals(host)
            || YouTubeUrlParser.isYouTubeHost(host);
    }

    private static long urlExpiry(URL url) throws IOException {
        String value = queryValue(url.getQuery(), "expire");
        if (value == null || value.length() == 0) return Long.MAX_VALUE;
        try {
            long seconds = Long.parseLong(value);
            return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
        } catch (NumberFormatException exception) {
            throw new MediaException("Invalid YouTube stream expiry", exception);
        }
    }

    private long relativeExpiry(JsonObject root) {
        int seconds = integer(root, "expiresInSeconds");
        return seconds > 0 && seconds <= 21600 ? clock.getAsLong() + seconds * 1000L : Long.MAX_VALUE;
    }

    private static long minPositive(long first, long second) {
        return Math.min(first, second);
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static int integer(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value == null ? 0 : value.getAsInt();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String safePlan(String plan) {
        return plan == null || plan.trim()
            .length() == 0 ? "identity" : plan.trim();
    }

    private static int boundedIndex(String operation, int length) throws MediaException {
        try {
            int index = Integer.parseInt(operation.substring(operation.indexOf(':') + 1));
            if (index < 0 || index > length || (operation.startsWith("swap:") && index == length && length > 0))
                throw new NumberFormatException();
            return index;
        } catch (NumberFormatException exception) {
            throw new MediaException("Invalid YouTube transform index", exception);
        }
    }

    private static Map<String, String> decodeQuery(String query) throws IOException {
        Map<String, String> result = new HashMap<String, String>();
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            result.put(
                decode(equals < 0 ? part : part.substring(0, equals)),
                decode(equals < 0 ? "" : part.substring(equals + 1)));
        }
        return result;
    }

    private static String queryValue(String query, String name) throws IOException {
        return decodeQuery(query == null ? "" : query).get(name);
    }

    private static String decode(String value) throws IOException {
        return URLDecoder.decode(value, "UTF-8");
    }

    private static URL replaceParameter(URL url, String name, String value) throws IOException {
        StringBuilder query = new StringBuilder();
        String raw = url.getQuery();
        if (raw != null && raw.length() > 0) {
            for (String part : raw.split("&")) {
                int equals = part.indexOf('=');
                String encodedKey = equals < 0 ? part : part.substring(0, equals);
                if (name.equals(decode(encodedKey))) {
                    continue;
                }
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(part);
            }
        }
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(URLEncoder.encode(name, "UTF-8"));
        query.append('=');
        query.append(URLEncoder.encode(value, "UTF-8"));
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath() + "?" + query);
    }

    public static final class ResolvedAudioCandidates {

        private final List<YouTubeMediaModels.ResolvedAudioStream> primaryCandidates;
        private final AlternativeResolver alternativeResolver;
        private boolean alternativeResolved;
        private List<YouTubeMediaModels.ResolvedAudioStream> alternativeCandidates = Collections.emptyList();

        private ResolvedAudioCandidates(List<YouTubeMediaModels.ResolvedAudioStream> primaryCandidates,
            AlternativeResolver alternativeResolver) {
            if (primaryCandidates == null || primaryCandidates.isEmpty()) {
                throw new IllegalArgumentException("At least one audio candidate is required");
            }
            this.primaryCandidates = Collections
                .unmodifiableList(new ArrayList<YouTubeMediaModels.ResolvedAudioStream>(primaryCandidates));
            this.alternativeResolver = alternativeResolver;
        }

        public List<YouTubeMediaModels.ResolvedAudioStream> getPrimaryCandidates() {
            return primaryCandidates;
        }

        public synchronized List<YouTubeMediaModels.ResolvedAudioStream> resolveAlternativeCandidates()
            throws IOException {
            if (alternativeResolved) return alternativeCandidates;
            if (alternativeResolver == null) {
                alternativeResolved = true;
                return alternativeCandidates;
            }
            List<YouTubeMediaModels.ResolvedAudioStream> resolved = alternativeResolver.resolve();
            alternativeCandidates = resolved == null || resolved.isEmpty()
                ? Collections.<YouTubeMediaModels.ResolvedAudioStream>emptyList()
                : Collections.unmodifiableList(new ArrayList<YouTubeMediaModels.ResolvedAudioStream>(resolved));
            alternativeResolved = true;
            return alternativeCandidates;
        }
    }

    private interface AlternativeResolver {

        List<YouTubeMediaModels.ResolvedAudioStream> resolve() throws IOException;
    }

    private static final class ClientProfile {

        private final String name;
        private final String version;
        private final String headerName;
        private final String userAgent;
        private final String deviceMake;
        private final String deviceModel;
        private final int androidSdkVersion;
        private final String osName;
        private final String osVersion;

        private ClientProfile(String name, String version, String headerName, String userAgent, String deviceMake,
            String deviceModel, int androidSdkVersion, String osName, String osVersion) {
            this.name = name;
            this.version = version;
            this.headerName = headerName;
            this.userAgent = userAgent;
            this.deviceMake = deviceMake;
            this.deviceModel = deviceModel;
            this.androidSdkVersion = androidSdkVersion;
            this.osName = osName;
            this.osVersion = osVersion;
        }
    }

    private static final class ClientUnavailableException extends MediaException {

        private ClientUnavailableException(String message) {
            super(message);
        }
    }

    private static final class Candidate {

        private final URL url;
        private final MediaFormat format;
        private final int bitrate;
        private final long expiresAtMillis;
        private final int preference;

        private Candidate(URL url, MediaFormat format, int bitrate, long expiresAtMillis, int preference) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
            this.expiresAtMillis = expiresAtMillis;
            this.preference = preference;
        }
    }

    private static final class TransformPlans {

        private final String signaturePlan;
        private final String nPlan;

        private TransformPlans(String signaturePlan, String nPlan) {
            this.signaturePlan = signaturePlan;
            this.nPlan = nPlan;
        }
    }

    /** Parses only split-array transform calls; no JavaScript is evaluated or executed. */
    private static final class PlayerScriptTransformExtractor {

        private static TransformPlans extract(String script) throws IOException {
            if (script == null || script.length() == 0) throw new MediaException("YouTube player JavaScript is empty");
            List<FunctionDefinition> functions = functions(script);
            Map<String, String> operations = operations(script);
            List<FunctionDefinition> signatureTargets = targets(script, functions, true);
            List<FunctionDefinition> nTargets = targets(script, functions, false);
            return new TransformPlans(
                transformPlan("signature", signatureTargets, operations),
                transformPlan("n", nTargets, operations));
        }

        private static Map<String, String> operations(String script) throws IOException {
            Map<String, String> result = new HashMap<String, String>();
            for (int index = 0; index < script.length();) {
                index = skipIgnored(script, index);
                if (index >= script.length() || !isIdentifierStart(script.charAt(index))) {
                    index++;
                    continue;
                }
                int end = identifierEnd(script, index);
                String objectName = script.substring(index, end);
                int cursor = skipIgnored(script, end);
                if (cursor < script.length() && script.charAt(cursor) == '=') {
                    cursor = skipIgnored(script, cursor + 1);
                    if (cursor < script.length() && script.charAt(cursor) == '{') {
                        int close = matching(script, cursor, '{', '}');
                        objectOperations(script, cursor + 1, close, objectName, result);
                        index = close + 1;
                        continue;
                    }
                }
                index = end;
            }
            return result;
        }

        private static List<FunctionDefinition> functions(String script) throws IOException {
            Map<String, FunctionDefinition> definitions = new HashMap<String, FunctionDefinition>();
            for (int index = 0; index < script.length();) {
                index = skipIgnored(script, index);
                if (index >= script.length() || !isIdentifierStart(script.charAt(index))) {
                    index++;
                    continue;
                }
                int end = identifierEnd(script, index);
                String token = script.substring(index, end);
                if ("function".equals(token)) {
                    int cursor = skipIgnored(script, end);
                    if (cursor < script.length() && isIdentifierStart(script.charAt(cursor))) {
                        int nameEnd = identifierEnd(script, cursor);
                        addFunction(
                            definitions,
                            parseFunction(script, index, cursor, script.substring(cursor, nameEnd), cursor));
                    }
                } else {
                    int cursor = skipIgnored(script, end);
                    if (cursor < script.length() && script.charAt(cursor) == '=') {
                        cursor = skipIgnored(script, cursor + 1);
                        if (wordAt(script, cursor, "function")) {
                            addFunction(definitions, parseFunction(script, cursor, -1, token, -1));
                        }
                    }
                }
                index = end;
            }
            return new ArrayList<FunctionDefinition>(definitions.values());
        }

        private static void addFunction(Map<String, FunctionDefinition> definitions, FunctionDefinition definition)
            throws IOException {
            FunctionDefinition previous = definitions.put(definition.name, definition);
            if (previous != null && (previous.start != definition.start || previous.end != definition.end)) {
                throw new MediaException("Ambiguous YouTube player transform function");
            }
        }

        private static FunctionDefinition parseFunction(String script, int functionStart, int declaredNameStart,
            String name, int nameStart) throws IOException {
            int cursor = skipIgnored(script, functionStart + "function".length());
            if (declaredNameStart >= 0) cursor = identifierEnd(script, declaredNameStart);
            cursor = skipIgnored(script, cursor);
            if (cursor >= script.length() || script.charAt(cursor) != '(')
                throw new MediaException("Malformed YouTube function");
            int closingParameters = matching(script, cursor, '(', ')');
            List<String> parameters = arguments(script.substring(cursor + 1, closingParameters));
            if (parameters.isEmpty() || parameters.size() > 2
                || !isIdentifier(parameters.get(0))
                || (parameters.size() == 2 && !isIdentifier(parameters.get(1)))) {
                throw new MediaException("Unsupported YouTube transform function parameters");
            }
            int openingBody = skipIgnored(script, closingParameters + 1);
            if (openingBody >= script.length() || script.charAt(openingBody) != '{')
                throw new MediaException("Malformed YouTube function body");
            int closingBody = matching(script, openingBody, '{', '}');
            return new FunctionDefinition(
                name,
                parameters.get(0),
                parameters.size() == 2 ? parameters.get(1) : null,
                script.substring(openingBody + 1, closingBody),
                functionStart,
                closingBody + 1,
                nameStart);
        }

        private static void objectOperations(String script, int start, int end, String objectName,
            Map<String, String> result) throws IOException {
            int index = start;
            while (index < end) {
                index = skipTrivia(script, index);
                if (index >= end) return;
                Property property = objectProperty(script, index, end);
                if (property == null) throw new MediaException("Unsupported YouTube player helper property");
                int cursor = skipIgnored(script, property.end);
                if (cursor >= end || script.charAt(cursor) != ':')
                    throw new MediaException("Unsupported YouTube player helper property");
                cursor = skipIgnored(script, cursor + 1);
                if (!wordAt(script, cursor, "function"))
                    throw new MediaException("Unsupported YouTube player helper method");
                FunctionDefinition method = parseFunction(script, cursor, -1, objectName + "." + property.name, -1);
                if (method.end > end + 1) throw new MediaException("Malformed YouTube player helper method");
                result.put(objectName + "." + property.name, operation(method));
                index = skipIgnored(script, method.end);
                if (index < end && script.charAt(index) == ',') {
                    index++;
                    continue;
                }
                if (index < end) throw new MediaException("Unsupported YouTube player helper syntax");
            }
        }

        private static String operation(FunctionDefinition method) throws IOException {
            String compact = compact(method.body);
            String array = method.argument;
            String escapedArray = Pattern.quote(array);
            if (compact.matches(escapedArray + "\\.reverse\\(\\);?")) return "reverse";
            if (method.secondArgument != null
                && compact.matches(escapedArray + "\\.splice\\(0," + Pattern.quote(method.secondArgument) + "\\);?"))
                return "splice:";
            if (method.secondArgument != null && compact
                .matches("(?:return)?" + escapedArray + "\\.slice\\(" + Pattern.quote(method.secondArgument) + "\\);?"))
                return "slice:";
            if (method.secondArgument != null && compact.matches(
                "(?:var|let|const)([A-Za-z_$][A-Za-z0-9_$]*)=" + escapedArray
                    + "\\[0\\];"
                    + escapedArray
                    + "\\[0\\]="
                    + escapedArray
                    + "\\["
                    + Pattern.quote(method.secondArgument)
                    + "%"
                    + escapedArray
                    + "\\.length\\];"
                    + escapedArray
                    + "\\["
                    + Pattern.quote(method.secondArgument)
                    + "\\]=\\1;?"))
                return "swap:";
            throw new MediaException("Unsupported YouTube player transform operation");
        }

        private static List<FunctionDefinition> targets(String script, List<FunctionDefinition> functions,
            boolean signature) throws IOException {
            Map<String, FunctionDefinition> byName = new HashMap<String, FunctionDefinition>();
            for (FunctionDefinition function : functions) byName.put(function.name, function);
            List<FunctionDefinition> targets = new ArrayList<FunctionDefinition>();
            for (int index = 0; index < script.length();) {
                index = skipIgnored(script, index);
                if (index >= script.length() || !isIdentifierStart(script.charAt(index))) {
                    index++;
                    continue;
                }
                int end = identifierEnd(script, index);
                FunctionDefinition function = byName.get(script.substring(index, end));
                int opening = skipIgnored(script, end);
                if (function != null && function.nameStart != index
                    && opening < script.length()
                    && script.charAt(opening) == '(') {
                    int closing = matching(script, opening, '(', ')');
                    String context = callContext(script, index, closing);
                    boolean signatureContext = signatureContext(context);
                    boolean nContext = nContext(context);
                    if (signatureContext && nContext) throw new MediaException("Ambiguous YouTube transform call site");
                    if ((signature && signatureContext) || (!signature && nContext)) addTarget(targets, function);
                    index = closing + 1;
                    continue;
                }
                index = end;
            }
            if (targets.size() != 1) throw new MediaException(
                "YouTube player JavaScript has no unambiguous " + (signature ? "signature" : "n")
                    + " transform target");
            return targets;
        }

        private static String callContext(String script, int start, int end) throws IOException {
            int statementStart = delimiterBefore(script, start - 1) + 1;
            int statementEnd = delimiterAfter(script, end + 1);
            String current = script.substring(statementStart, statementEnd);
            if (signatureContext(current) || nContext(current)) return current;
            int previousStart = delimiterBefore(script, statementStart - 2) + 1;
            return script.substring(previousStart, statementEnd);
        }

        private static int delimiterBefore(String script, int index) throws IOException {
            int delimiter = -1;
            for (int cursor = 0; cursor <= index && cursor < script.length();) {
                char value = script.charAt(cursor);
                if (value == '\'' || value == '\"' || value == '`') {
                    cursor = quotedEnd(script, cursor) + 1;
                    continue;
                }
                if (value == '/' && cursor + 1 < script.length() && script.charAt(cursor + 1) == '/') {
                    cursor = lineCommentEnd(script, cursor + 2) + 1;
                    continue;
                }
                if (value == '/' && cursor + 1 < script.length() && script.charAt(cursor + 1) == '*') {
                    cursor = blockCommentEnd(script, cursor + 2) + 1;
                    continue;
                }
                if (value == ';' || value == '{' || value == '}') delimiter = cursor;
                cursor++;
            }
            return delimiter;
        }

        private static int delimiterAfter(String script, int index) throws IOException {
            while (index < script.length()) {
                char value = script.charAt(index);
                if (value == '\'' || value == '\"' || value == '`') {
                    index = quotedEnd(script, index) + 1;
                    continue;
                }
                if (value == '/' && index + 1 < script.length() && script.charAt(index + 1) == '/') {
                    index = lineCommentEnd(script, index + 2) + 1;
                    continue;
                }
                if (value == '/' && index + 1 < script.length() && script.charAt(index + 1) == '*') {
                    index = blockCommentEnd(script, index + 2) + 1;
                    continue;
                }
                if (value == ';' || value == '{' || value == '}') return index;
                index++;
            }
            return script.length();
        }

        private static void addTarget(List<FunctionDefinition> targets, FunctionDefinition target) throws IOException {
            if (targets.isEmpty() || targets.get(0) != target) {
                if (!targets.isEmpty()) throw new MediaException("Ambiguous YouTube player transform target");
                targets.add(target);
            }
        }

        private static boolean signatureContext(String context) throws IOException {
            String code = codeOnly(context);
            return hasProperty(code, "sig") || hasAssignment(code, "sig")
                || hasAssignment(code, "signature")
                || hasLiteralCall(context, "set", "sig")
                || hasLiteralCall(context, "set", "signature");
        }

        private static boolean nContext(String context) throws IOException {
            String code = codeOnly(context);
            return hasProperty(code, "n") || hasAssignment(code, "n")
                || hasLiteralCall(context, "get", "n")
                || hasLiteralCall(context, "set", "n");
        }

        private static String transformPlan(String kind, List<FunctionDefinition> targets,
            Map<String, String> operations) throws IOException {
            FunctionDefinition function = targets.get(0);
            if (function.secondArgument != null)
                throw new MediaException("Unsupported YouTube " + kind + " transform function parameters");
            List<String> plan = new ArrayList<String>();
            boolean split = false;
            boolean join = false;
            for (String statement : statements(function.body)) {
                String compact = compact(statement);
                if (compact.length() == 0) continue;
                if (compact.equals(function.argument + "=" + function.argument + ".split(\"\")")
                    || compact.equals(function.argument + "=" + function.argument + ".split('')")) {
                    if (split) throw new MediaException("Unsupported YouTube " + kind + " transform function");
                    split = true;
                    continue;
                }
                if (compact.equals("return" + function.argument + ".join(\"\")")
                    || compact.equals("return" + function.argument + ".join('')")) {
                    if (join) throw new MediaException("Unsupported YouTube " + kind + " transform function");
                    join = true;
                    continue;
                }
                int index = skipIgnored(statement, 0);
                if (index >= statement.length() || !isIdentifierStart(statement.charAt(index))) {
                    throw new MediaException("Unsupported YouTube " + kind + " transform statement");
                }
                int end = identifierEnd(statement, index);
                String base = statement.substring(index, end);
                Property property = property(statement, end, statement.length());
                int opening = property == null ? -1 : skipIgnored(statement, property.end);
                if (property == null || opening >= statement.length() || statement.charAt(opening) != '(') {
                    throw new MediaException("Unsupported YouTube " + kind + " transform statement");
                }
                int closing = matching(statement, opening, '(', ')');
                if (skipIgnored(statement, closing + 1) != statement.length()) {
                    throw new MediaException("Unsupported YouTube " + kind + " transform statement");
                }
                List<String> arguments = arguments(statement.substring(opening + 1, closing));
                if (function.argument.equals(base) && "reverse".equals(property.name) && arguments.isEmpty()) {
                    plan.add("reverse");
                } else if (!arguments.isEmpty() && function.argument.equals(arguments.get(0))) {
                    String operation = operations.get(base + "." + property.name);
                    if (operation == null)
                        throw new MediaException("Unsupported YouTube " + kind + " transform helper");
                    if (operation.endsWith(":")) {
                        if (arguments.size() != 2)
                            throw new MediaException("Unsupported YouTube " + kind + " transform argument");
                        operation += nonNegativeInteger(arguments.get(1));
                    } else if (arguments.size() != 1)
                        throw new MediaException("Unsupported YouTube " + kind + " transform argument");
                    plan.add(operation);
                } else {
                    throw new MediaException("Unsupported YouTube " + kind + " transform operation");
                }
            }
            if (!split || !join || plan.isEmpty())
                throw new MediaException("Unsupported YouTube " + kind + " transform function");
            return join(plan, ",");
        }

        private static List<String> statements(String source) throws IOException {
            List<String> result = new ArrayList<String>();
            int start = 0;
            for (int index = 0; index < source.length();) {
                char character = source.charAt(index);
                if (character == '\'' || character == '\"' || character == '`') {
                    index = quotedEnd(source, index) + 1;
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    index = lineCommentEnd(source, index + 2) + 1;
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                    index = blockCommentEnd(source, index + 2) + 1;
                    continue;
                }
                if (character == '(' || character == '[' || character == '{') {
                    index = matching(source, index, character, matchingClose(character)) + 1;
                    continue;
                }
                if (character == ';') {
                    result.add(source.substring(start, index));
                    start = ++index;
                    continue;
                }
                index++;
            }
            result.add(source.substring(start));
            return result;
        }

        private static Property property(String source, int index, int limit) throws IOException {
            int cursor = skipIgnored(source, index);
            if (cursor >= limit) return null;
            if (source.charAt(cursor) == '.') {
                cursor = skipIgnored(source, cursor + 1);
                if (cursor >= limit || !isIdentifierStart(source.charAt(cursor))) return null;
                int end = identifierEnd(source, cursor);
                return new Property(source.substring(cursor, end), end);
            }
            if (source.charAt(cursor) != '[') return null;
            int closing = matching(source, cursor, '[', ']');
            if (closing > limit) return null;
            String name = quoted(
                source.substring(cursor + 1, closing)
                    .trim());
            return name == null ? null : new Property(name, closing + 1);
        }

        private static Property objectProperty(String source, int index, int limit) throws IOException {
            int cursor = skipTrivia(source, index);
            if (cursor >= limit) return null;
            if (isIdentifierStart(source.charAt(cursor))) {
                int end = identifierEnd(source, cursor);
                return new Property(source.substring(cursor, end), end);
            }
            if (source.charAt(cursor) != '\'' && source.charAt(cursor) != '\"') return null;
            int end = quotedEnd(source, cursor);
            if (end >= limit) return null;
            String name = quoted(source.substring(cursor, end + 1));
            return name == null ? null : new Property(name, end + 1);
        }

        private static List<String> arguments(String source) throws IOException {
            List<String> values = new ArrayList<String>();
            int start = 0;
            for (int index = 0; index < source.length();) {
                index = skipIgnored(source, index);
                if (index >= source.length()) break;
                char character = source.charAt(index);
                if (character == '(' || character == '[' || character == '{') {
                    index = matching(source, index, character, matchingClose(character)) + 1;
                    continue;
                }
                if (character == ',') {
                    values.add(
                        source.substring(start, index)
                            .trim());
                    start = ++index;
                    continue;
                }
                index++;
            }
            String last = source.substring(start)
                .trim();
            if (last.length() > 0) values.add(last);
            return values;
        }

        private static int matching(String source, int opening, char open, char close) throws IOException {
            int depth = 0;
            for (int index = opening; index < source.length(); index++) {
                char character = source.charAt(index);
                if (character == '\'' || character == '\"' || character == '`') {
                    index = quotedEnd(source, index);
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    index = lineCommentEnd(source, index + 2);
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                    index = blockCommentEnd(source, index + 2);
                    continue;
                }
                if (character == open) depth++;
                if (character == close && --depth == 0) return index;
            }
            throw new MediaException("Unbalanced YouTube player JavaScript");
        }

        private static int skipIgnored(String source, int index) throws IOException {
            while (index < source.length()) {
                char character = source.charAt(index);
                if (Character.isWhitespace(character)) {
                    index++;
                    continue;
                }
                if (character == '\'' || character == '\"' || character == '`') {
                    index = quotedEnd(source, index) + 1;
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    index = lineCommentEnd(source, index + 2) + 1;
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                    index = blockCommentEnd(source, index + 2) + 1;
                    continue;
                }
                return index;
            }
            return index;
        }

        private static int skipTrivia(String source, int index) throws IOException {
            while (index < source.length()) {
                char character = source.charAt(index);
                if (Character.isWhitespace(character)) {
                    index++;
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    index = lineCommentEnd(source, index + 2) + 1;
                    continue;
                }
                if (character == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                    index = blockCommentEnd(source, index + 2) + 1;
                    continue;
                }
                return index;
            }
            return index;
        }

        private static int quotedEnd(String source, int opening) throws IOException {
            char quote = source.charAt(opening);
            for (int index = opening + 1; index < source.length(); index++) {
                if (source.charAt(index) == '\\') {
                    index++;
                    continue;
                }
                if (source.charAt(index) == quote) return index;
            }
            throw new MediaException("Unterminated string in YouTube player JavaScript");
        }

        private static int lineCommentEnd(String source, int index) {
            while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') index++;
            return index;
        }

        private static int blockCommentEnd(String source, int index) throws IOException {
            while (index + 1 < source.length()) {
                if (source.charAt(index) == '*' && source.charAt(index + 1) == '/') return index + 1;
                index++;
            }
            throw new MediaException("Unterminated comment in YouTube player JavaScript");
        }

        private static char matchingClose(char opening) {
            return opening == '(' ? ')' : opening == '[' ? ']' : '}';
        }

        private static boolean isIdentifierStart(char value) {
            return value == '$' || value == '_' || Character.isLetter(value);
        }

        private static boolean isIdentifierPart(char value) {
            return isIdentifierStart(value) || Character.isDigit(value);
        }

        private static int identifierEnd(String source, int start) {
            int index = start + 1;
            while (index < source.length() && isIdentifierPart(source.charAt(index))) index++;
            return index;
        }

        private static boolean isIdentifier(String value) {
            return value.length() > 0 && isIdentifierStart(value.charAt(0))
                && identifierEnd(value, 0) == value.length();
        }

        private static boolean wordAt(String source, int index, String word) {
            return index >= 0 && index + word.length() <= source.length()
                && source.regionMatches(index, word, 0, word.length())
                && (index == 0 || !isIdentifierPart(source.charAt(index - 1)))
                && (index + word.length() == source.length()
                    || !isIdentifierPart(source.charAt(index + word.length())));
        }

        private static String quoted(String value) throws IOException {
            if (value.length() < 2 || (value.charAt(0) != '\'' && value.charAt(0) != '\"')
                || value.charAt(value.length() - 1) != value.charAt(0)) return null;
            if (value.indexOf('\\') >= 0)
                throw new MediaException("Escaped YouTube player helper property is unsupported");
            return value.substring(1, value.length() - 1);
        }

        private static boolean emptyString(String value) throws IOException {
            String unquoted = quoted(value);
            return unquoted != null && unquoted.length() == 0;
        }

        private static int nonNegativeInteger(String value) throws IOException {
            try {
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new MediaException("Invalid YouTube transform argument", exception);
            }
        }

        private static String compact(String value) {
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < value.length(); index++)
                if (!Character.isWhitespace(value.charAt(index))) result.append(value.charAt(index));
            return result.toString();
        }

        private static String codeOnly(String source) throws IOException {
            StringBuilder result = new StringBuilder(source.length());
            for (int index = 0; index < source.length();) {
                char value = source.charAt(index);
                if (value == '\'' || value == '\"' || value == '`') {
                    int end = quotedEnd(source, index);
                    while (index <= end) {
                        result.append(' ');
                        index++;
                    }
                    continue;
                }
                if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    int end = lineCommentEnd(source, index + 2);
                    while (index <= end && index < source.length()) {
                        result.append(' ');
                        index++;
                    }
                    continue;
                }
                if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                    int end = blockCommentEnd(source, index + 2);
                    while (index <= end) {
                        result.append(' ');
                        index++;
                    }
                    continue;
                }
                result.append(value);
                index++;
            }
            return result.toString();
        }

        private static boolean hasProperty(String value, String expected) {
            return value.contains("." + expected);
        }

        private static boolean hasLiteralCall(String source, String method, String expected) throws IOException {
            for (int index = 0; index < source.length();) {
                index = skipIgnored(source, index);
                if (index >= source.length() || !isIdentifierStart(source.charAt(index))) {
                    index++;
                    continue;
                }
                int end = identifierEnd(source, index);
                if (!method.equals(source.substring(index, end))) {
                    index = end;
                    continue;
                }
                int opening = skipIgnored(source, end);
                if (opening >= source.length() || source.charAt(opening) != '(') {
                    index = end;
                    continue;
                }
                int closing = matching(source, opening, '(', ')');
                List<String> arguments = arguments(source.substring(opening + 1, closing));
                if (!arguments.isEmpty() && expected.equals(quoted(arguments.get(0)))) return true;
                index = closing + 1;
            }
            return false;
        }

        private static boolean hasAssignment(String value, String expected) {
            for (int index = 0; index < value.length();) {
                if (!isIdentifierStart(value.charAt(index))) {
                    index++;
                    continue;
                }
                int end = identifierEnd(value, index);
                if (expected.equals(value.substring(index, end)) && skipWhitespace(value, end) < value.length()
                    && value.charAt(skipWhitespace(value, end)) == '=') return true;
                index = end;
            }
            return false;
        }

        private static int skipWhitespace(String value, int index) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            return index;
        }

        private static String join(List<String> values, String separator) {
            StringBuilder result = new StringBuilder();
            for (String value : values) {
                if (result.length() > 0) result.append(separator);
                result.append(value);
            }
            return result.toString();
        }

        private static final class FunctionDefinition {

            private final String name;
            private final String argument;
            private final String secondArgument;
            private final String body;
            private final int start;
            private final int end;
            private final int nameStart;

            private FunctionDefinition(String name, String argument, String secondArgument, String body, int start,
                int end, int nameStart) {
                this.name = name;
                this.argument = argument;
                this.secondArgument = secondArgument;
                this.body = body;
                this.start = start;
                this.end = end;
                this.nameStart = nameStart;
            }
        }

        private static final class Property {

            private final String name;
            private final int end;

            private Property(String name, int end) {
                this.name = name;
                this.end = end;
            }
        }
    }
}
