package com.horizonradio.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.horizonradio.core.model.RadioStation;
import com.horizonradio.network.PacketBufferUtil;
import com.horizonradio.network.packets.SelectRadioStationPacket;

/**
 * Queries Radio Browser directory mirrors for validated station records.
 */
public class RadioBrowserService {

    private static final String MIRROR_DISCOVERY_HOST = "all.api.radio-browser.info";
    private static final String API_HOST_SUFFIX = ".api.radio-browser.info";
    private static final String USER_AGENT = "HorizonRadio/1.1.0 (Radio Browser directory client)";
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    private static final int READ_TIMEOUT_MILLIS = 15000;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_STATION_NAME_BYTES = 200;
    private static final int MAX_RADIO_STATUS_BYTES = 160;
    private static final String PLAYING_STATUS_PREFIX = "Playing ";
    private static final int MAX_PUBLICATION_NAME_BYTES = Math.min(
        MAX_STATION_NAME_BYTES,
        MAX_RADIO_STATUS_BYTES - PLAYING_STATUS_PREFIX.getBytes(StandardCharsets.UTF_8).length);
    private static final Logger LOGGER = Logger.getLogger(RadioBrowserService.class.getName());

    public CompletableFuture<List<RadioStation>> search(final String query) {
        final String boundedQuery = boundQuery(query);
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<RadioStation>>() {

            @Override
            public List<RadioStation> get() {
                boolean popular = boundedQuery.length() == 0;
                return requestStations("json/stations/search", boundedQuery, popular);
            }
        });
    }

    public CompletableFuture<RadioStation> lookup(final String stationUuid) {
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<RadioStation>() {

            @Override
            public RadioStation get() {
                if (isBlank(stationUuid)) {
                    return null;
                }
                List<RadioStation> stations = requestStations(
                    "json/stations/byuuid/" + encodePathSegment(stationUuid.trim()),
                    "",
                    false);
                return stations.isEmpty() ? null : stations.get(0);
            }
        });
    }

    public CompletableFuture<Void> countClick(final String stationUuid) {
        return CompletableFuture.runAsync(new Runnable() {

            @Override
            public void run() {
                if (isBlank(stationUuid)) {
                    return;
                }
                request("json/url/" + encodePathSegment(stationUuid.trim()), null, false);
            }
        });
    }

    public static List<RadioStation> parseStations(String json) {
        List<RadioStation> stations = new ArrayList<RadioStation>();
        Set<String> seenUuids = new HashSet<String>();
        Set<String> seenNames = new HashSet<String>();
        try {
            JsonElement root = new Gson().fromJson(json, JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                return stations;
            }
            JsonArray records = root.getAsJsonArray();
            for (JsonElement record : records) {
                if (stations.size() >= MAX_RESULTS) {
                    return stations;
                }
                if (!record.isJsonObject()) {
                    continue;
                }
                JsonObject station = record.getAsJsonObject();
                RadioStation publicationStation = sanitizeForPublication(
                    new RadioStation(
                        getString(station, "stationuuid"),
                        getString(station, "name"),
                        getString(station, "url_resolved"),
                        isWorking(station),
                        getBoolean(station, "hls")));
                if (publicationStation == null || !seenUuids.add(publicationStation.getStationUuid())
                    || !seenNames.add(normalizeStationName(publicationStation.getName()))) {
                    continue;
                }
                stations.add(publicationStation);
            }
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Failed to parse Radio Browser response", exception);
        }
        return stations;
    }

    public static URI buildSearchUri(URI base, String query, boolean popular) {
        String baseUrl = base.toString();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        StringBuilder uri = new StringBuilder(baseUrl).append("json/stations/search?hidebroken=true&limit=50");
        if (popular) {
            uri.append("&order=votes&reverse=true");
        } else {
            uri.append("&name=")
                .append(encodeQueryValue(boundQuery(query)));
        }
        return URI.create(uri.toString());
    }

    private List<RadioStation> requestStations(String path, String query, boolean popular) {
        String response = request(path, query, popular);
        return response == null ? new ArrayList<RadioStation>() : parseStations(response);
    }

    private String request(String path, String query, boolean popular) {
        List<URI> mirrors = resolveMirrors();
        for (URI base : mirrors) {
            HttpURLConnection connection = null;
            try {
                URI uri = "json/stations/search".equals(path) ? buildSearchUri(base, query, popular)
                    : URI.create(base.toString() + path);
                connection = (HttpURLConnection) uri.toURL()
                    .openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                connection.setReadTimeout(READ_TIMEOUT_MILLIS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", USER_AGENT);

                int responseCode = connection.getResponseCode();
                if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= 300) {
                    closeQuietly(connection.getErrorStream());
                    continue;
                }
                return readResponse(connection.getInputStream());
            } catch (IOException exception) {
                LOGGER.log(Level.FINE, "Radio Browser request failed for mirror", exception);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        if (!mirrors.isEmpty()) {
            LOGGER.warning("All discovered Radio Browser mirrors failed");
        }
        return null;
    }

    public static RadioStation sanitizeForPublication(RadioStation station) {
        if (station == null) {
            return null;
        }
        String stationUuid = safe(station.getStationUuid()).trim();
        String name = safe(station.getName()).trim();
        String streamUrl = safe(station.getStreamUrl()).trim();
        if (isBlank(stationUuid)
            || stationUuid.getBytes(StandardCharsets.UTF_8).length > SelectRadioStationPacket.MAX_STATION_UUID_BYTES
            || isBlank(name)
            || !station.isLastCheckOk()
            || !isHttpStreamUrl(streamUrl)) {
            return null;
        }
        String boundedName = PacketBufferUtil.truncateUtf8(name, MAX_PUBLICATION_NAME_BYTES);
        if (isBlank(boundedName)) {
            return null;
        }
        return new RadioStation(stationUuid, boundedName, streamUrl, true, station.isHls());
    }

    private List<URI> resolveMirrors() {
        List<URI> mirrors = new ArrayList<URI>();
        Set<String> seenHosts = new HashSet<String>();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(MIRROR_DISCOVERY_HOST);
            for (InetAddress address : addresses) {
                String host = address.getCanonicalHostName();
                if (host.endsWith(API_HOST_SUFFIX) && seenHosts.add(host)) {
                    mirrors.add(URI.create("https://" + host + "/"));
                }
            }
            Collections.shuffle(mirrors);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to resolve Radio Browser mirrors", exception);
        }
        return mirrors;
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream stream = input;
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(reader)) {
            StringBuilder response = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = bufferedReader.read(buffer)) != -1) {
                response.append(buffer, 0, count);
            }
            return response.toString();
        }
    }

    private static boolean isWorking(JsonObject station) {
        return getBoolean(station, "lastcheckok");
    }

    private static boolean isHttpStreamUrl(String streamUrl) {
        try {
            URI streamUri = URI.create(streamUrl);
            String scheme = streamUri.getScheme();
            return streamUri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String getString(JsonObject object, String memberName) {
        JsonElement member = object.get(memberName);
        return member == null || member.isJsonNull() ? "" : member.getAsString();
    }

    private static boolean getBoolean(JsonObject object, String memberName) {
        try {
            JsonElement member = object.get(memberName);
            return member != null && !member.isJsonNull() && (member.getAsBoolean() || member.getAsInt() != 0);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String boundQuery(String query) {
        String value = query == null ? "" : query.trim();
        return value.length() > MAX_QUERY_LENGTH ? value.substring(0, MAX_QUERY_LENGTH) : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeStationName(String name) {
        return safe(name).trim()
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim()
            .length() == 0;
    }

    private static String encodeQueryValue(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 is required by Java", exception);
        }
    }

    private static String encodePathSegment(String value) {
        return encodeQueryValue(value).replace("+", "%20");
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // The HTTP response code is the useful error here.
        }
    }
}
