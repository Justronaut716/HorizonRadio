package com.horizonradio.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.junit.Test;

public class ServerEventsStructureTest {

    @Test
    public void serverEventsOnlyContainsRegularFmlBusEvents() throws IOException {
        String source = readSource("src/main/java/com/horizonradio/server/ServerEvents.java");

        assertFalse(source.contains("FMLServerStartingEvent"));
        assertFalse(source.contains("FMLServerStoppingEvent"));
        assertTrue(source.contains("PlayerLoggedInEvent"));
        assertTrue(source.contains("PlayerLoggedOutEvent"));
        assertTrue(source.contains("ServerTickEvent"));
        assertTrue(normalizeSource(source).contains("FMLCommonHandler.instance().bus().register"));
    }

    @Test
    public void horizonRadioOwnsFmlLifecycleEventHandlers() throws IOException {
        String source = readSource("src/main/java/com/horizonradio/HorizonRadio.java");

        assertTrue(
            source.contains("@Mod.EventHandler\n    public void onServerStarting(FMLServerStartingEvent event)"));
        assertTrue(
            source.contains("@Mod.EventHandler\n    public void onServerStopping(FMLServerStoppingEvent event)"));
        assertTrue(source.contains("proxy.onServerStarting(event.getServer())"));
        assertTrue(source.contains("proxy.onServerStopping(server)"));
    }

    private static String readSource(String path) throws IOException {
        File file = new File(path);
        StringBuilder source = new StringBuilder();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                source.append(line)
                    .append('\n');
            }
        } finally {
            reader.close();
        }
        return source.toString();
    }

    private static String normalizeSource(String source) {
        return source.replaceAll("\\s+", " ")
            .replaceAll("\\s*\\.\\s*", ".")
            .replaceAll("\\(\\s+", "(")
            .replaceAll("\\s+\\)", ")")
            .trim();
    }
}
