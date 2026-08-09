package com.horizonradio.core.server;

/** Immutable canonical chart region shared by the client and server. */
public final class ChartRegion {

    private final String code;
    private final String apiCountryCode;
    private final String displayName;

    ChartRegion(String code, String apiCountryCode, String displayName) {
        if (isEmpty(code) || isEmpty(apiCountryCode) || isEmpty(displayName)) {
            throw new IllegalArgumentException("chart region fields must not be empty");
        }
        this.code = code;
        this.apiCountryCode = apiCountryCode;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getApiCountryCode() {
        return apiCountryCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
