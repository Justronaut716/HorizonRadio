package com.horizonradio.client;

import java.util.EnumSet;

import com.google.gson.JsonObject;

/** Locally persisted interface preferences. */
final class ClientUiSettings {

    enum Category {

        PLAYBACK("Playback"),
        QUEUE("Queue"),
        FAVORITES("Favorites"),
        SEARCH("Search results"),
        CONTROLS("Playback controls"),
        VOLUME("Volume"),
        ERROR("Errors");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    enum Position {

        TOP_LEFT("Top left", true, true),
        TOP_MIDDLE("Top middle", false, true),
        TOP_RIGHT("Top right", false, true),
        BOTTOM_LEFT("Bottom left", true, false),
        BOTTOM_RIGHT("Bottom right", false, false);

        final String label;
        final boolean left, top;

        Position(String label, boolean left, boolean top) {
            this.label = label;
            this.left = left;
            this.top = top;
        }
    }

    boolean notifications = true;
    boolean autoSearch = true;
    int searchDelay = 300;
    int songResults = 10;
    int radioResults = 50;
    Position position = Position.TOP_MIDDLE;
    final EnumSet<Category> enabled = EnumSet.allOf(Category.class);

    boolean allows(String key) {
        Category category = key.startsWith("queue") ? Category.QUEUE
            : key.startsWith("favorites") ? Category.FAVORITES
                : key.startsWith("search") ? Category.SEARCH
                    : key.equals("playback") ? Category.PLAYBACK
                        : key.equals("volume") ? Category.VOLUME
                            : key.equals("error") ? Category.ERROR : Category.CONTROLS;
        return notifications && enabled.contains(category);
    }

    JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("notifications", notifications);
        result.addProperty("autoSearch", autoSearch);
        result.addProperty("searchDelay", searchDelay);
        result.addProperty("songResults", songResults);
        result.addProperty("radioResults", radioResults);
        result.addProperty("position", position.name());
        for (Category category : Category.values()) result.addProperty(category.name(), enabled.contains(category));
        return result;
    }

    static ClientUiSettings fromJson(JsonObject object) {
        ClientUiSettings settings = new ClientUiSettings();
        if (object == null) return settings;
        settings.songResults = readResultCount(object, "songResults", 10);
        settings.radioResults = readResultCount(object, "radioResults", 50);
        settings.notifications = readBoolean(object, "notifications", true);
        settings.autoSearch = readBoolean(object, "autoSearch", true);
        try {
            settings.searchDelay = Math.max(
                100,
                Math.min(
                    3000,
                    object.get("searchDelay")
                        .getAsInt()));
        } catch (RuntimeException ignored) {}
        try {
            settings.position = Position.valueOf(
                object.get("position")
                    .getAsString());
        } catch (RuntimeException ignored) {}
        for (Category category : Category.values())
            if (!readBoolean(object, category.name(), true)) settings.enabled.remove(category);
        return settings;
    }

    private static int readResultCount(JsonObject object, String key, int fallback) {
        try {
            return Math.max(
                5,
                Math.min(
                    100,
                    object.get(key)
                        .getAsInt()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || !object.get(key)
            .isJsonPrimitive()
            || !object.getAsJsonPrimitive(key)
                .isBoolean())
            return fallback;
        return object.get(key)
            .getAsBoolean();
    }
}
