package com.horizonradio.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds result lists with matching client-local favorites at the front. */
public final class FavoriteResultComposer {

    private FavoriteResultComposer() {}

    public static List<HorizonRadioScreen.SearchResult> composeSongs(List<ClientFavorites.Song> favorites,
        List<HorizonRadioScreen.SearchResult> results, String query) {
        Set<String> resultIds = new LinkedHashSet<String>();
        for (HorizonRadioScreen.SearchResult result : results) {
            if (result != null) resultIds.add(result.videoId);
        }
        List<ClientFavorites.Song> matching = new ArrayList<ClientFavorites.Song>();
        for (ClientFavorites.Song song : favorites) {
            if (song != null && (resultIds.contains(song.getVideoId())
                || matchesQuery(song.getTitle() + " " + song.getChannel(), query))) {
                matching.add(song);
            }
        }
        return composeSongs(matching, results);
    }

    public static List<HorizonRadioScreen.RadioStationResult> composeRadios(List<ClientFavorites.Radio> favorites,
        List<HorizonRadioScreen.RadioStationResult> results, String query) {
        Set<String> resultIds = new LinkedHashSet<String>();
        for (HorizonRadioScreen.RadioStationResult result : results) {
            if (result != null) resultIds.add(result.stationUuid);
        }
        List<ClientFavorites.Radio> matching = new ArrayList<ClientFavorites.Radio>();
        for (ClientFavorites.Radio station : favorites) {
            if (station != null
                && (resultIds.contains(station.getStationUuid()) || matchesQuery(station.getName(), query))) {
                matching.add(station);
            }
        }
        return composeRadios(matching, results);
    }

    private static boolean matchesQuery(String text, String query) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String word : query.trim()
            .toLowerCase(Locale.ROOT)
            .split("\\s+")) {
            if (!normalized.contains(word)) return false;
        }
        return true;
    }

    public static List<HorizonRadioScreen.SearchResult> composeSongs(List<ClientFavorites.Song> favorites,
        List<HorizonRadioScreen.SearchResult> charts) {
        List<HorizonRadioScreen.SearchResult> composed = new ArrayList<HorizonRadioScreen.SearchResult>();
        Set<String> seenIds = new LinkedHashSet<String>();
        if (favorites != null) {
            for (ClientFavorites.Song song : favorites) {
                if (song == null || !seenIds.add(song.getVideoId())) {
                    continue;
                }
                String title = nonBlankOr(song.getTitle(), song.getVideoId());
                composed.add(
                    new HorizonRadioScreen.SearchResult(
                        song.getVideoId(),
                        title,
                        song.getChannel(),
                        song.getDuration(),
                        song.getThumbnail()));
            }
        }
        if (charts != null) {
            for (HorizonRadioScreen.SearchResult chart : charts) {
                if (chart == null || isBlank(chart.videoId) || !seenIds.add(chart.videoId)) {
                    continue;
                }
                composed.add(chart);
            }
        }
        return composed;
    }

    public static List<HorizonRadioScreen.RadioStationResult> composeRadios(List<ClientFavorites.Radio> favorites,
        List<HorizonRadioScreen.RadioStationResult> popular) {
        List<HorizonRadioScreen.RadioStationResult> composed = new ArrayList<HorizonRadioScreen.RadioStationResult>();
        Set<String> seenIds = new LinkedHashSet<String>();
        if (favorites != null) {
            for (ClientFavorites.Radio radio : favorites) {
                if (radio == null || !seenIds.add(radio.getStationUuid())) {
                    continue;
                }
                composed.add(
                    new HorizonRadioScreen.RadioStationResult(
                        radio.getStationUuid(),
                        nonBlankOr(radio.getName(), radio.getStationUuid())));
            }
        }
        if (popular != null) {
            for (HorizonRadioScreen.RadioStationResult station : popular) {
                if (station == null || isBlank(station.stationUuid) || !seenIds.add(station.stationUuid)) {
                    continue;
                }
                composed.add(station);
            }
        }
        return composed;
    }

    private static String nonBlankOr(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim()
            .length() == 0;
    }
}
