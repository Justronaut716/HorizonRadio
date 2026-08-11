package com.horizonradio.server.media;

import java.io.IOException;

/**
 * Indicates that a media input could not be safely opened or decoded.
 */
public class MediaException extends IOException {

    public MediaException(String message) {
        super(message);
    }

    public MediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
