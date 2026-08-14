package com.horizonradio.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds empty-query result lists with client-local favorites at the front. */
public final class FavoriteResultComposer {

    private FavoriteResultComposer() {}

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
