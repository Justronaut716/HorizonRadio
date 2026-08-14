package com.horizonradio.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered, client-local favorite sources and their cached display metadata. */
public final class ClientFavorites {

    private final List<Song> songs = new ArrayList<Song>();
    private final List<Radio> radios = new ArrayList<Radio>();

    public ClientFavorites() {}

    public ClientFavorites(List<Song> songs, List<Radio> radios) {
        if (songs != null) {
            for (Song song : songs) {
                addSongIfAbsent(song);
            }
        }
        if (radios != null) {
            for (Radio radio : radios) {
                addRadioIfAbsent(radio);
            }
        }
    }

    public List<Song> getSongs() {
        return new ArrayList<Song>(songs);
    }

    public List<Radio> getRadios() {
        return new ArrayList<Radio>(radios);
    }

    public boolean isSongFavorite(String videoId) {
        String normalizedId = normalizeId(videoId);
        return normalizedId.length() > 0 && findSongIndex(normalizedId) >= 0;
    }

    public boolean isRadioFavorite(String stationUuid) {
        String normalizedId = normalizeId(stationUuid);
        return normalizedId.length() > 0 && findRadioIndex(normalizedId) >= 0;
    }

    public boolean toggleSong(Song song) {
        if (song == null || song.getVideoId()
            .length() == 0) {
            return false;
        }
        int index = findSongIndex(song.getVideoId());
        if (index >= 0) {
            songs.remove(index);
            return false;
        }
        songs.add(0, song);
        return true;
    }

    public boolean toggleRadio(Radio radio) {
        if (radio == null || radio.getStationUuid()
            .length() == 0) {
            return false;
        }
        int index = findRadioIndex(radio.getStationUuid());
        if (index >= 0) {
            radios.remove(index);
            return false;
        }
        radios.add(0, radio);
        return true;
    }

    public void updateSong(Song song) {
        if (song == null || song.getVideoId()
            .length() == 0) {
            return;
        }
        int index = findSongIndex(song.getVideoId());
        if (index >= 0) {
            songs.set(index, song);
        }
    }

    public void updateRadio(Radio radio) {
        if (radio == null || radio.getStationUuid()
            .length() == 0) {
            return;
        }
        int index = findRadioIndex(radio.getStationUuid());
        if (index >= 0) {
            radios.set(index, radio);
        }
    }

    private void addSongIfAbsent(Song song) {
        if (song != null && song.getVideoId()
            .length() > 0 && !isSongFavorite(song.getVideoId())) {
            songs.add(song);
        }
    }

    private void addRadioIfAbsent(Radio radio) {
        if (radio != null && radio.getStationUuid()
            .length() > 0 && !isRadioFavorite(radio.getStationUuid())) {
            radios.add(radio);
        }
    }

    private int findSongIndex(String videoId) {
        for (int index = 0; index < songs.size(); index++) {
            if (songs.get(index)
                .getVideoId()
                .equals(videoId)) {
                return index;
            }
        }
        return -1;
    }

    private int findRadioIndex(String stationUuid) {
        for (int index = 0; index < radios.size(); index++) {
            if (radios.get(index)
                .getStationUuid()
                .equals(stationUuid)) {
                return index;
            }
        }
        return -1;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value;
    }

    public static final class Song {

        private final String videoId;
        private final String title;
        private final String channel;
        private final String duration;
        private final String thumbnail;

        public Song(String videoId, String title, String channel, String duration, String thumbnail) {
            this.videoId = normalizeId(videoId);
            this.title = normalizeText(title);
            this.channel = normalizeText(channel);
            this.duration = normalizeText(duration);
            this.thumbnail = normalizeText(thumbnail);
        }

        public String getVideoId() {
            return videoId;
        }

        public String getTitle() {
            return title;
        }

        public String getChannel() {
            return channel;
        }

        public String getDuration() {
            return duration;
        }

        public String getThumbnail() {
            return thumbnail;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Song)) {
                return false;
            }
            Song that = (Song) other;
            return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
                && Objects.equals(channel, that.channel)
                && Objects.equals(duration, that.duration)
                && Objects.equals(thumbnail, that.thumbnail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoId, title, channel, duration, thumbnail);
        }
    }

    public static final class Radio {

        private final String stationUuid;
        private final String name;

        public Radio(String stationUuid, String name) {
            this.stationUuid = normalizeId(stationUuid);
            this.name = normalizeText(name);
        }

        public String getStationUuid() {
            return stationUuid;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Radio)) {
                return false;
            }
            Radio that = (Radio) other;
            return Objects.equals(stationUuid, that.stationUuid) && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stationUuid, name);
        }
    }
}
