package com.horizonradio.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/** Client-local persistence for HorizonRadio volume and favorites. */
public final class HorizonRadioClientConfig {

    public static final float DEFAULT_VOLUME = 1.0f;
    public static final boolean DEFAULT_YOUTUBE_AUDIO_ENABLED = true;
    public static final String FILE_NAME = "horizonradio-client.json";

    private static final Logger LOGGER = Logger.getLogger(HorizonRadioClientConfig.class.getName());
    private static final Gson GSON = new Gson();

    private final File configFile;
    private final float volume;
    private final ClientFavorites favorites;
    private final PlaybackMode playbackMode;
    private final boolean youtubeAudioEnabled;

    private HorizonRadioClientConfig(File configFile, float volume, ClientFavorites favorites,
        PlaybackMode playbackMode, boolean youtubeAudioEnabled) {
        this.configFile = configFile;
        this.volume = volume;
        this.favorites = favorites == null ? new ClientFavorites() : favorites;
        this.playbackMode = playbackMode == null ? PlaybackMode.SERVER : playbackMode;
        this.youtubeAudioEnabled = youtubeAudioEnabled;
    }

    public static HorizonRadioClientConfig load(File configDirectory) {
        File configFile = configDirectory == null ? null : new File(configDirectory, FILE_NAME);
        if (configFile == null || !configFile.isFile()) {
            return new HorizonRadioClientConfig(
                configFile,
                DEFAULT_VOLUME,
                new ClientFavorites(),
                PlaybackMode.SERVER,
                DEFAULT_YOUTUBE_AUDIO_ENABLED);
        }

        try (Reader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            if (object != null) {
                return new HorizonRadioClientConfig(
                    configFile,
                    readVolume(object),
                    readFavorites(object),
                    readPlaybackMode(object),
                    readYoutubeAudioEnabled(object));
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not load HorizonRadio client configuration", exception);
        } catch (JsonParseException exception) {
            LOGGER.log(Level.WARNING, "Could not parse HorizonRadio client configuration", exception);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not read HorizonRadio client configuration", exception);
        }

        return new HorizonRadioClientConfig(
            configFile,
            DEFAULT_VOLUME,
            new ClientFavorites(),
            PlaybackMode.SERVER,
            DEFAULT_YOUTUBE_AUDIO_ENABLED);
    }

    public float getVolume() {
        return volume;
    }

    public ClientFavorites getFavorites() {
        return new ClientFavorites(favorites.getSongs(), favorites.getRadios());
    }

    public PlaybackMode getPlaybackMode() {
        return playbackMode;
    }

    public boolean isYoutubeAudioEnabled() {
        return youtubeAudioEnabled;
    }

    public void save(float value) {
        save(value, favorites, playbackMode, youtubeAudioEnabled);
    }

    public void save(float value, ClientFavorites favoriteState) {
        save(value, favoriteState, playbackMode, youtubeAudioEnabled);
    }

    public void save(float value, ClientFavorites favoriteState, PlaybackMode mode) {
        save(value, favoriteState, mode, youtubeAudioEnabled);
    }

    public void save(float value, ClientFavorites favoriteState, PlaybackMode mode, boolean youtubeAudioEnabled) {
        if (configFile == null) {
            return;
        }

        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOGGER.warning("Could not create the HorizonRadio client configuration directory");
            return;
        }

        File temporaryFile = new File(configFile.getPath() + ".tmp");
        JsonObject object = new JsonObject();
        object.addProperty("volume", normalize(value));
        ClientFavorites safeFavorites = favoriteState == null ? new ClientFavorites() : favoriteState;
        object.add("favoriteSongs", songArray(safeFavorites));
        object.add("favoriteRadios", radioArray(safeFavorites));
        PlaybackMode safeMode = mode == null || mode == PlaybackMode.GROUP ? PlaybackMode.SERVER : mode;
        object.addProperty("playbackMode", safeMode.getPersistedName());
        object.addProperty("youtubeAudioEnabled", youtubeAudioEnabled);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temporaryFile), StandardCharsets.UTF_8)) {
            GSON.toJson(object, writer);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not write HorizonRadio client configuration", exception);
            temporaryFile.delete();
            return;
        }

        try {
            try {
                Files.move(
                    temporaryFile.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not replace HorizonRadio client configuration", exception);
            temporaryFile.delete();
        }
    }

    private static boolean readYoutubeAudioEnabled(JsonObject object) {
        JsonElement value = object.get("youtubeAudioEnabled");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return DEFAULT_YOUTUBE_AUDIO_ENABLED;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException exception) {
            return DEFAULT_YOUTUBE_AUDIO_ENABLED;
        }
    }

    private static float readVolume(JsonObject object) {
        JsonElement value = object.get("volume");
        if (value == null || value.isJsonNull()) {
            return DEFAULT_VOLUME;
        }
        try {
            return normalize(value.getAsFloat());
        } catch (RuntimeException exception) {
            return DEFAULT_VOLUME;
        }
    }

    private static ClientFavorites readFavorites(JsonObject object) {
        java.util.List<ClientFavorites.Song> songs = new java.util.ArrayList<ClientFavorites.Song>();
        java.util.List<ClientFavorites.Radio> radios = new java.util.ArrayList<ClientFavorites.Radio>();
        JsonElement songElement = object.get("favoriteSongs");
        if (songElement != null && songElement.isJsonArray()) {
            for (JsonElement element : songElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject song = element.getAsJsonObject();
                songs.add(
                    new ClientFavorites.Song(
                        string(song, "videoId"),
                        string(song, "title"),
                        string(song, "channel"),
                        string(song, "duration"),
                        string(song, "thumbnail")));
            }
        }
        JsonElement radioElement = object.get("favoriteRadios");
        if (radioElement != null && radioElement.isJsonArray()) {
            for (JsonElement element : radioElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject radio = element.getAsJsonObject();
                radios.add(new ClientFavorites.Radio(string(radio, "stationUuid"), string(radio, "name")));
            }
        }
        return new ClientFavorites(songs, radios);
    }

    private static PlaybackMode readPlaybackMode(JsonObject object) {
        JsonElement value = object.get("playbackMode");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return PlaybackMode.SERVER;
        }
        try {
            return PlaybackMode.fromPersistedName(value.getAsString());
        } catch (RuntimeException exception) {
            return PlaybackMode.SERVER;
        }
    }

    private static JsonArray songArray(ClientFavorites favorites) {
        JsonArray songs = new JsonArray();
        for (ClientFavorites.Song song : favorites.getSongs()) {
            JsonObject object = new JsonObject();
            object.addProperty("videoId", song.getVideoId());
            object.addProperty("title", song.getTitle());
            object.addProperty("channel", song.getChannel());
            object.addProperty("duration", song.getDuration());
            object.addProperty("thumbnail", song.getThumbnail());
            songs.add(object);
        }
        return songs;
    }

    private static JsonArray radioArray(ClientFavorites favorites) {
        JsonArray radios = new JsonArray();
        for (ClientFavorites.Radio radio : favorites.getRadios()) {
            JsonObject object = new JsonObject();
            object.addProperty("stationUuid", radio.getStationUuid());
            object.addProperty("name", radio.getName());
            radios.add(object);
        }
        return radios;
    }

    private static String string(JsonObject object, String memberName) {
        JsonElement value = object.get(memberName);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static float normalize(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_VOLUME;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
