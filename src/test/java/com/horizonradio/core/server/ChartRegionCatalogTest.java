package com.horizonradio.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChartRegionCatalogTest {

    @Test
    public void resolvesGlobalAndCommonAliases() {
        assertEquals(
            "GLOBAL",
            ChartRegionCatalog.resolve("Weltweit")
                .getCode());
        assertEquals(
            "GLOBAL",
            ChartRegionCatalog.resolve(" worldwide ")
                .getCode());
        assertEquals(
            "DE",
            ChartRegionCatalog.resolve("Deutschland")
                .getCode());
        assertEquals(
            "DE",
            ChartRegionCatalog.resolve("gErMaNy")
                .getCode());
        assertEquals(
            "US",
            ChartRegionCatalog.resolve("Amerika")
                .getCode());
        assertEquals(
            "US",
            ChartRegionCatalog.resolve("United States of America")
                .getCode());
    }

    @Test
    public void resolvesIsoCodesAndLocaleNames() {
        assertEquals(
            "FR",
            ChartRegionCatalog.resolve("fr")
                .getCode());
        assertEquals(
            "DE",
            ChartRegionCatalog.resolve("Allemagne")
                .getCode());
        assertEquals(
            "JP",
            ChartRegionCatalog.resolve("日本")
                .getCode());
    }

    @Test
    public void displayNamesForChartLabelsUseEnglish() {
        assertEquals(
            "Germany",
            ChartRegionCatalog.byCode("DE")
                .getDisplayName());
        assertEquals(
            "United States",
            ChartRegionCatalog.byCode("US")
                .getDisplayName());
    }

    @Test
    public void rejectsUnknownAndAmbiguousNames() {
        assertNull(ChartRegionCatalog.resolve("Atlantis"));
        assertTrue(ChartRegionCatalog.isAmbiguous("Congo"));
        assertNull(ChartRegionCatalog.resolve("Congo"));
    }

    @Test
    public void normalizesAccentsSeparatorsAndCase() {
        assertEquals(
            "CI",
            ChartRegionCatalog.resolve("CÔTE-D’IVOIRE")
                .getCode());
        assertEquals(
            "DE",
            ChartRegionCatalog.resolve("  deutsch-land ")
                .getCode());
    }
}
