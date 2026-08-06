package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests that the server clears the complete shared queue. */
public class ClearPlaylistPacket implements IMessage {

    @Override
    public void toBytes(ByteBuf buf) {}

    @Override
    public void fromBytes(ByteBuf buf) {}
}
