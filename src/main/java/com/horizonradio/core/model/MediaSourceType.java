package com.horizonradio.core.model;

/** Identifies the kind of media represented by a playlist entry. */
public enum MediaSourceType {

    YOUTUBE((byte) 1),
    RADIO((byte) 2);

    private final byte wireValue;

    MediaSourceType(byte wireValue) {
        this.wireValue = wireValue;
    }

    public byte getWireValue() {
        return wireValue;
    }

    public static MediaSourceType fromWireValue(byte wireValue) {
        for (MediaSourceType type : values()) {
            if (type.wireValue == wireValue) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown media source type: " + wireValue);
    }
}
