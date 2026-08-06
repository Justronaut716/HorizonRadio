package com.horizonradio.integration;

import java.util.ArrayList;
import java.util.List;

import com.horizonradio.core.integration.HorizonRadioIntegration;
import com.horizonradio.core.integration.HorizonRadioIntegrationContext;

public final class IntegrationManager {

    private final List<HorizonRadioIntegration> integrations;

    public IntegrationManager(List<HorizonRadioIntegration> integrations) {
        this.integrations = new ArrayList<HorizonRadioIntegration>(integrations);
    }

    public static IntegrationManager discover() {
        List<HorizonRadioIntegration> integrations = new ArrayList<HorizonRadioIntegration>();
        if (GtnhEnvironmentDetector.isAvailable()) {
            integrations.add(new GtnhIntegration());
        }
        return new IntegrationManager(integrations);
    }

    public void onPreInit(HorizonRadioIntegrationContext context) {
        for (HorizonRadioIntegration integration : integrations) {
            integration.onPreInit(context);
        }
    }

    public void onInit(HorizonRadioIntegrationContext context) {
        for (HorizonRadioIntegration integration : integrations) {
            integration.onInit(context);
        }
    }

    public void onPostInit(HorizonRadioIntegrationContext context) {
        for (HorizonRadioIntegration integration : integrations) {
            integration.onPostInit(context);
        }
    }
}
