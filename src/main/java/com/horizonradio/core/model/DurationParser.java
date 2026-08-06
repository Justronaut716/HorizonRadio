package com.horizonradio.core.model;

public final class DurationParser {

    public static final long DEFAULT_MILLIS = 180000L;

    private DurationParser() {}

    public static long parseMillis(String duration) {
        long parsed = parseMillisStrict(duration);
        return parsed < 0L ? DEFAULT_MILLIS : parsed;
    }

    public static long parseMillisStrict(String duration) {
        if (duration == null || duration.trim()
            .isEmpty()) {
            return -1L;
        }

        String[] parts = duration.trim()
            .split(":", -1);
        if (parts.length > 3) {
            return -1L;
        }

        try {
            long seconds = 0L;
            for (String part : parts) {
                String trimmedPart = part.trim();
                if (trimmedPart.isEmpty()) {
                    return -1L;
                }
                long value = Long.parseLong(trimmedPart);
                if (value < 0L) {
                    return -1L;
                }
                seconds = Math.addExact(Math.multiplyExact(seconds, 60L), value);
            }
            return Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException e) {
            return -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
