package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Broadcasts the server's repeat-one state to clients. */
public class LoopStatePacket implements IMessage {

    private boolean looping;

    public LoopStatePacket() {}

    public LoopStatePacket(boolean looping) {
        this.looping = looping;
    }

    public boolean isLooping() {
        return looping;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(looping);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        looping = buf.readBoolean();
    }
}
