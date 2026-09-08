package com.horizonradio.client;

import java.util.Locale;

/** Short user-facing messages; never displays raw URLs or exception details. */
final class MediaErrorMessage {

    private MediaErrorMessage() {}

    static String describe(Throwable failure, String fallback) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage())
                .toLowerCase(Locale.ROOT);
            if (message.contains("429") || message.contains("rate limit")
                || message.contains("rate-limit")
                || message.contains("too many requests")) {
                return "YouTube rate limit - please try again later.";
            }
            if (message.contains("403") || message.contains("forbidden")
                || message.contains("sign in")
                || message.contains("bot")) {
                return "YouTube blocked the request - please try again later.";
            }
            if (cause instanceof java.net.SocketTimeoutException || message.contains("timed out")) {
                return "Connection timed out - please try again.";
            }
            if (cause instanceof java.net.UnknownHostException || cause instanceof java.net.ConnectException) {
                return "Connection failed - check your internet connection.";
            }
        }
        return fallback;
    }
}
