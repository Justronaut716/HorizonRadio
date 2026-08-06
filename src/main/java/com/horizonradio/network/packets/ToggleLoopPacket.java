package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests toggling repeat-one mode on the server. */
public class ToggleLoopPacket implements IMessage {

    @Override
    public void toBytes(ByteBuf buf) {}

    @Override
    public void fromBytes(ByteBuf buf) {}
}
