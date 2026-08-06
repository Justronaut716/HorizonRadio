package com.horizonradio;

import net.minecraft.server.MinecraftServer;

import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.integration.HorizonRadioIntegrationContext;
import com.horizonradio.core.protocol.HorizonRadioProtocol;
import com.horizonradio.integration.IntegrationManager;
import com.horizonradio.network.HorizonRadioNetwork;
import com.horizonradio.server.ServerEvents;
import com.horizonradio.server.ServerThreadExecutor;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

@Mod(
    modid = HorizonRadio.MOD_ID,
    name = "HorizonRadio",
    version = HorizonRadioProtocol.VERSION,
    acceptedMinecraftVersions = "[1.7.10]")
public class HorizonRadio {

    public static final String MOD_ID = "horizonradio";
    private static HorizonRadioConfig config;
    private IntegrationManager integrationManager;
    private HorizonRadioIntegrationContext integrationContext;

    @SidedProxy(clientSide = "com.horizonradio.client.ClientProxy", serverSide = "com.horizonradio.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        HorizonRadioNetwork.registerMessages();
        proxy.preInit(event);
        integrationContext = new HorizonRadioIntegrationContext(HorizonRadioProtocol.VERSION, HorizonRadio.getConfig());
        integrationManager = IntegrationManager.discover();
        integrationManager.onPreInit(integrationContext);
        ServerEvents.register();
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        proxy.onServerStarting(event.getServer());
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        proxy.onServerStopping(server);
        if (server != null) {
            ServerThreadExecutor.clear(server);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        integrationManager.onInit(integrationContext);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
        integrationManager.onPostInit(integrationContext);
    }

    public static HorizonRadioConfig getConfig() {
        return config;
    }

    static void setConfig(HorizonRadioConfig loadedConfig) {
        config = loadedConfig;
    }
}
