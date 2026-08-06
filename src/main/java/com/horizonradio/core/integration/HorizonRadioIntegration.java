package com.horizonradio.core.integration;

public interface HorizonRadioIntegration {

    String getId();

    void onPreInit(HorizonRadioIntegrationContext context);

    void onInit(HorizonRadioIntegrationContext context);

    void onPostInit(HorizonRadioIntegrationContext context);
}
