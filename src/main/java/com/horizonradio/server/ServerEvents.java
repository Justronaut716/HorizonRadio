package com.horizonradio.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.horizonradio.HorizonRadio;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class ServerEvents {

    private static boolean registered;

    public static void register() {
        if (!registered) {
            FMLCommonHandler.instance()
                .bus()
                .register(new ServerEvents());
            registered = true;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MinecraftServer server = FMLCommonHandler.instance()
                .getMinecraftServerInstance();
            if (server != null) {
                ServerThreadExecutor.drain(server);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(final PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            final EntityPlayerMP player = (EntityPlayerMP) event.player;
            final MinecraftServer server = FMLCommonHandler.instance()
                .getMinecraftServerInstance();
            if (server != null) {
                ServerThreadExecutor.execute(server, new Runnable() {

                    @Override
                    public void run() {
                        HorizonRadio.proxy.onPlayerLoggedIn(player);
                    }
                });
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(final PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            final EntityPlayerMP player = (EntityPlayerMP) event.player;
            final MinecraftServer server = FMLCommonHandler.instance()
                .getMinecraftServerInstance();
            if (server != null) {
                ServerThreadExecutor.execute(server, new Runnable() {

                    @Override
                    public void run() {
                        HorizonRadio.proxy.onPlayerLoggedOut(player);
                    }
                });
            }
        }
    }
}
