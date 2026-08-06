package com.horizonradio.core.integration;

import com.horizonradio.core.config.HorizonRadioConfig;

public final class HorizonRadioIntegrationContext {

    private final String modVersion;
    private final HorizonRadioConfig config;

    public HorizonRadioIntegrationContext(String modVersion, HorizonRadioConfig config) {
        this.modVersion = modVersion;
        this.config = config;
    }

    public String getModVersion() {
        return modVersion;
    }

    public HorizonRadioConfig getConfig() {
        return config;
    }
}
