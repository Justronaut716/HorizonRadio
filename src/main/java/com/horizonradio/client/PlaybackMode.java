package com.horizonradio.client;

import java.util.Locale;

public enum PlaybackMode {

    PRIVATE("private", true),
    SERVER("server", true),
    GROUP("group", false);

    private final String persistedName;
    private final boolean selectable;

    PlaybackMode(String persistedName, boolean selectable) {
        this.persistedName = persistedName;
        this.selectable = selectable;
    }

    public static PlaybackMode fromPersistedName(String value) {
        if (value == null) {
            return SERVER;
        }
        String normalized = value.trim()
            .toLowerCase(Locale.ROOT);
        for (PlaybackMode mode : values()) {
            if (mode.persistedName.equals(normalized)) {
                return mode;
            }
        }
        return SERVER;
    }

    public String getPersistedName() {
        return persistedName;
    }

    public boolean isSelectable() {
        return selectable;
    }
}
