package com.horizonradio.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.integration.HorizonRadioIntegration;
import com.horizonradio.core.integration.HorizonRadioIntegrationContext;

public class IntegrationManagerTest {

    @Test
    public void invokesLifecycleCallbacksInOrderWithTheSameContext() {
        List<String> events = new ArrayList<String>();
        RecordingIntegration integration = new RecordingIntegration(events);
        IntegrationManager manager = new IntegrationManager(Arrays.<HorizonRadioIntegration>asList(integration));
        HorizonRadioIntegrationContext context = new HorizonRadioIntegrationContext(
            "1.0.0",
            HorizonRadioConfig.load(null));

        manager.onPreInit(context);
        manager.onInit(context);
        manager.onPostInit(context);

        assertEquals(Arrays.asList("preInit", "init", "postInit"), events);
        assertSame(context, integration.preInitContext);
        assertSame(context, integration.initContext);
        assertSame(context, integration.postInitContext);
    }

    private static final class RecordingIntegration implements HorizonRadioIntegration {

        private final List<String> events;
        private HorizonRadioIntegrationContext preInitContext;
        private HorizonRadioIntegrationContext initContext;
        private HorizonRadioIntegrationContext postInitContext;

        private RecordingIntegration(List<String> events) {
            this.events = events;
        }

        @Override
        public String getId() {
            return "recording";
        }

        @Override
        public void onPreInit(HorizonRadioIntegrationContext context) {
            events.add("preInit");
            preInitContext = context;
        }

        @Override
        public void onInit(HorizonRadioIntegrationContext context) {
            events.add("init");
            initContext = context;
        }

        @Override
        public void onPostInit(HorizonRadioIntegrationContext context) {
            events.add("postInit");
            postInitContext = context;
        }
    }
}
