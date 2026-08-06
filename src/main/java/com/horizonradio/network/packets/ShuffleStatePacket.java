package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Broadcasts the server's shuffle state to clients. */
public class ShuffleStatePacket implements IMessage {

    private boolean shuffling;

    public ShuffleStatePacket() {}

    public ShuffleStatePacket(boolean shuffling) {
        this.shuffling = shuffling;
    }

    public boolean isShuffling() {
        return shuffling;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(shuffling);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        shuffling = buf.readBoolean();
    }
}
