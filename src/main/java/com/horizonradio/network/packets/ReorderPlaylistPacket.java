package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests moving one queued entry to another playlist position. */
public class ReorderPlaylistPacket implements IMessage {

    private int fromIndex;
    private int targetIndex;

    public ReorderPlaylistPacket() {}

    public ReorderPlaylistPacket(int fromIndex, int targetIndex) {
        this.fromIndex = fromIndex;
        this.targetIndex = targetIndex;
    }

    public int getFromIndex() {
        return fromIndex;
    }

    public int getTargetIndex() {
        return targetIndex;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(fromIndex);
        buf.writeInt(targetIndex);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        fromIndex = buf.readInt();
        targetIndex = buf.readInt();
    }
}
