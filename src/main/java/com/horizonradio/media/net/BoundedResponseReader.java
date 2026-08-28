package com.horizonradio.media.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads an HTTP response only while its encoded byte count remains bounded. */
public final class BoundedResponseReader {

    private BoundedResponseReader() {}

    public static String readUtf8(InputStream input, long declaredLength, int maximumBytes) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input is required");
        }
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (declaredLength > maximumBytes) {
                throw limitExceeded(maximumBytes);
            }
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > maximumBytes - total) {
                    throw limitExceeded(maximumBytes);
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static IOException limitExceeded(int maximumBytes) {
        return new IOException("HTTP response exceeds " + maximumBytes + " bytes");
    }
}
