package com.horizonradio.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HorizonRadioUiLayoutTest {

    @Test
    public void prototypePanelUsesTheReferenceDimensions() {
        assertEquals(360, HorizonRadioScreen.PANEL_WIDTH);
        assertEquals(322, HorizonRadioScreen.PANEL_HEIGHT);
    }

    @Test
    public void referencePanelFitsAStandardMinecraftViewport() {
        HorizonRadioUiLayout layout = HorizonRadioUiLayout.create(640, 360);

        assertEquals(360, layout.panelWidth());
        assertEquals(322, layout.panelHeight());
        assertEquals(340.0F / 322.0F, layout.scale(), 0.0001f);
        assertEquals(380, layout.scaledPanelWidth());
        assertEquals(340, layout.scaledPanelHeight());
        assertEquals(130, layout.panelLeft());
        assertEquals(10, layout.panelTop());
        assertTrue(layout.queueLeft() > layout.contentLeft());
        assertTrue(layout.footerTop() > layout.bodyTop());
    }

    @Test
    public void largeViewportsUseOnlyALimitedPanelUpscale() {
        HorizonRadioUiLayout layout = HorizonRadioUiLayout.create(1920, 1080);

        assertEquals(1.10F, layout.scale(), 0.0001F);
    }

    @Test
    public void referenceCoordinatesRemainStableWhenTheViewportNeedsScaling() {
        HorizonRadioUiLayout layout = HorizonRadioUiLayout.create(320, 240);

        assertTrue(layout.scale() < 1.0f);
        assertEquals(layout.referencePanelLeft(), layout.toLogicalMouseX(layout.panelLeft()));
        assertEquals(layout.referencePanelTop(), layout.toLogicalMouseY(layout.panelTop()));
        assertTrue(layout.scaledPanelWidth() < layout.panelWidth());
        assertTrue(layout.scaledPanelHeight() < layout.panelHeight());
    }
}
