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
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/** Client-local persistence for the HorizonRadio volume setting. */
public final class HorizonRadioClientConfig {

    public static final float DEFAULT_VOLUME = 1.0f;
    public static final String FILE_NAME = "horizonradio-client.json";

    private static final Logger LOGGER = Logger.getLogger(HorizonRadioClientConfig.class.getName());
    private static final Gson GSON = new Gson();

    private final File configFile;
    private final float volume;

    private HorizonRadioClientConfig(File configFile, float volume) {
        this.configFile = configFile;
        this.volume = volume;
    }

    public static HorizonRadioClientConfig load(File configDirectory) {
        File configFile = configDirectory == null ? null : new File(configDirectory, FILE_NAME);
        if (configFile == null || !configFile.isFile()) {
            return new HorizonRadioClientConfig(configFile, DEFAULT_VOLUME);
        }

        try (Reader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            if (object != null && object.has("volume")
                && !object.get("volume")
                    .isJsonNull()) {
                return new HorizonRadioClientConfig(
                    configFile,
                    normalize(
                        object.get("volume")
                            .getAsFloat()));
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not load HorizonRadio client configuration", exception);
        } catch (JsonParseException exception) {
            LOGGER.log(Level.WARNING, "Could not parse HorizonRadio client configuration", exception);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not read HorizonRadio client volume", exception);
        }

        return new HorizonRadioClientConfig(configFile, DEFAULT_VOLUME);
    }

    public float getVolume() {
        return volume;
    }

    public void save(float value) {
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

    private static float normalize(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_VOLUME;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
