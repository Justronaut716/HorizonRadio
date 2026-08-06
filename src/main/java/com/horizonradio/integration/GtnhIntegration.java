package com.horizonradio.integration;

import java.util.logging.Logger;

import com.horizonradio.core.integration.HorizonRadioIntegration;
import com.horizonradio.core.integration.HorizonRadioIntegrationContext;

public final class GtnhIntegration implements HorizonRadioIntegration {

    private static final Logger LOGGER = Logger.getLogger(GtnhIntegration.class.getName());

    @Override
    public String getId() {
        return "gtnh";
    }

    @Override
    public void onPreInit(HorizonRadioIntegrationContext context) {
        LOGGER.info("HorizonRadio optional GTNH capability is available during preInit");
    }

    @Override
    public void onInit(HorizonRadioIntegrationContext context) {
        LOGGER.info("HorizonRadio optional GTNH capability is available during init");
    }

    @Override
    public void onPostInit(HorizonRadioIntegrationContext context) {
        LOGGER.info("HorizonRadio optional GTNH capability is available during postInit");
    }
}
