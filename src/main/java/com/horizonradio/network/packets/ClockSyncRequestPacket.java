package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class ClockSyncRequestPacket implements IMessage {

    private long clientSentAtMs;

    public ClockSyncRequestPacket() {}

    public ClockSyncRequestPacket(long clientSentAtMs) {
        this.clientSentAtMs = clientSentAtMs;
    }

    public long getClientSentAtMs() {
        return clientSentAtMs;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(clientSentAtMs);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        clientSentAtMs = buf.readLong();
    }
}
