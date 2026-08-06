package com.horizonradio.integration;

import cpw.mods.fml.common.Loader;

public final class GtnhEnvironmentDetector {

    private GtnhEnvironmentDetector() {}

    public static boolean isAvailable() {
        return Loader.isModLoaded("gtnhlib");
    }
}
