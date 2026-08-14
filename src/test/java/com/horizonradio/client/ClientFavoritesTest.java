package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ClientFavoritesTest {

    @Test
    public void togglingSongsAddsNewestFirstAndRemovesOnSecondToggle() {
        ClientFavorites favorites = new ClientFavorites();

        assertTrue(favorites.toggleSong(new ClientFavorites.Song("first", "First", "", "2:00", "")));
        assertTrue(favorites.toggleSong(new ClientFavorites.Song("second", "Second", "", "3:00", "")));
        assertEquals(Arrays.asList("second", "first"), songIds(favorites.getSongs()));

        assertFalse(favorites.toggleSong(new ClientFavorites.Song("first", "Updated", "", "4:00", "")));
        assertEquals(Collections.singletonList("second"), songIds(favorites.getSongs()));
    }

    @Test
    public void constructorSkipsBlankIdsAndCollapsesDuplicateRecords() {
        ClientFavorites favorites = new ClientFavorites(
            Arrays.asList(
                new ClientFavorites.Song("", "Blank", "", "", ""),
                new ClientFavorites.Song("song", "Song", "Channel", "2:00", "thumb"),
                new ClientFavorites.Song(" song ", "Duplicate", "", "", "")),
            Arrays.asList(
                new ClientFavorites.Radio("", "Blank"),
                new ClientFavorites.Radio("station", "Station"),
                new ClientFavorites.Radio(" station ", "Duplicate")));

        assertEquals(Collections.singletonList("song"), songIds(favorites.getSongs()));
        assertEquals(Collections.singletonList("station"), radioIds(favorites.getRadios()));
    }

    @Test
    public void updatingSongMetadataPreservesItsExistingPosition() {
        ClientFavorites favorites = new ClientFavorites(
            Arrays.asList(
                new ClientFavorites.Song("first", "First", "", "", ""),
                new ClientFavorites.Song("second", "Second", "", "", "")),
            Collections.<ClientFavorites.Radio>emptyList());

        favorites.updateSong(new ClientFavorites.Song("first", "Updated", "Channel", "2:00", "thumb"));

        assertEquals(Arrays.asList("first", "second"), songIds(favorites.getSongs()));
        assertEquals("Updated", favorites.getSongs().get(0).getTitle());
        assertEquals("Channel", favorites.getSongs().get(0).getChannel());
    }

    @Test
    public void togglingRadioUsesStationUuidAsIdentity() {
        ClientFavorites favorites = new ClientFavorites();

        assertTrue(favorites.toggleRadio(new ClientFavorites.Radio("station", "Station")));
        assertTrue(favorites.isRadioFavorite(" station "));
        assertFalse(favorites.toggleRadio(new ClientFavorites.Radio(" station ", "Renamed")));
        assertFalse(favorites.isRadioFavorite("station"));
    }

    @Test
    public void returnedSnapshotsAreDefensive() {
        ClientFavorites favorites = new ClientFavorites();
        favorites.toggleSong(new ClientFavorites.Song("song", "Song", "", "", ""));

        List<ClientFavorites.Song> songs = favorites.getSongs();
        songs.clear();

        assertEquals(Collections.singletonList("song"), songIds(favorites.getSongs()));
    }

    private static List<String> songIds(List<ClientFavorites.Song> songs) {
        List<String> ids = new ArrayList<String>();
        for (ClientFavorites.Song song : songs) {
            ids.add(song.getVideoId());
        }
        return ids;
    }

    private static List<String> radioIds(List<ClientFavorites.Radio> radios) {
        List<String> ids = new ArrayList<String>();
        for (ClientFavorites.Radio radio : radios) {
            ids.add(radio.getStationUuid());
        }
        return ids;
    }
}
