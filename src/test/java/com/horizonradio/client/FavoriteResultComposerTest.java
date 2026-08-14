package com.horizonradio.client;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class FavoriteResultComposerTest {

    @Test
    public void songsPutFavoritesBeforeCachedChartsAndRemoveDuplicateChartRows() {
        List<ClientFavorites.Song> favorites = Arrays.asList(
            new ClientFavorites.Song("favorite", "Favorite", "", "2:00", ""));
        List<HorizonRadioScreen.SearchResult> charts = Arrays.asList(
            new HorizonRadioScreen.SearchResult("favorite", "Chart copy", "", "2:00", ""),
            new HorizonRadioScreen.SearchResult("chart", "Chart", "", "3:00", ""));

        List<HorizonRadioScreen.SearchResult> composed = FavoriteResultComposer.composeSongs(favorites, charts);

        assertEquals(Arrays.asList("favorite", "chart"), videoIds(composed));
        assertEquals("Favorite", composed.get(0).title);
    }

    @Test
    public void songsPreserveChartsWhenThereAreNoFavorites() {
        List<HorizonRadioScreen.SearchResult> charts = Arrays.asList(
            new HorizonRadioScreen.SearchResult("first", "First", "", "", ""),
            new HorizonRadioScreen.SearchResult("second", "Second", "", "", ""));

        List<HorizonRadioScreen.SearchResult> composed = FavoriteResultComposer.composeSongs(
            Collections.<ClientFavorites.Song>emptyList(),
            charts);

        assertEquals(charts, composed);
        assertEquals(charts, Arrays.asList(
            new HorizonRadioScreen.SearchResult("first", "First", "", "", ""),
            new HorizonRadioScreen.SearchResult("second", "Second", "", "", "")));
    }

    @Test
    public void missingSongMetadataFallsBackToVideoId() {
        List<HorizonRadioScreen.SearchResult> composed = FavoriteResultComposer.composeSongs(
            Collections.singletonList(new ClientFavorites.Song("video", "", "", "", "")),
            Collections.<HorizonRadioScreen.SearchResult>emptyList());

        assertEquals("video", composed.get(0).title);
        assertEquals("video", composed.get(0).videoId);
    }

    @Test
    public void radiosPutFavoritesBeforePopularStationsAndRemoveDuplicates() {
        List<ClientFavorites.Radio> favorites = Arrays.asList(
            new ClientFavorites.Radio("favorite", "Favorite Station"));
        List<HorizonRadioScreen.RadioStationResult> popular = Arrays.asList(
            new HorizonRadioScreen.RadioStationResult("favorite", "Duplicate Station"),
            new HorizonRadioScreen.RadioStationResult("popular", "Popular Station"));

        List<HorizonRadioScreen.RadioStationResult> composed = FavoriteResultComposer.composeRadios(favorites, popular);

        assertEquals(Arrays.asList("favorite", "popular"), radioIds(composed));
        assertEquals("Favorite Station", composed.get(0).name);
    }

    @Test
    public void missingRadioNameFallsBackToStationUuid() {
        List<HorizonRadioScreen.RadioStationResult> composed = FavoriteResultComposer.composeRadios(
            Collections.singletonList(new ClientFavorites.Radio("station", "")),
            Collections.<HorizonRadioScreen.RadioStationResult>emptyList());

        assertEquals("station", composed.get(0).name);
    }

    private static List<String> videoIds(List<HorizonRadioScreen.SearchResult> results) {
        List<String> ids = new ArrayList<String>();
        for (HorizonRadioScreen.SearchResult result : results) {
            ids.add(result.videoId);
        }
        return ids;
    }

    private static List<String> radioIds(List<HorizonRadioScreen.RadioStationResult> results) {
        List<String> ids = new ArrayList<String>();
        for (HorizonRadioScreen.RadioStationResult result : results) {
            ids.add(result.stationUuid);
        }
        return ids;
    }
}
