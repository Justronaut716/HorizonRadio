package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class ClockSyncResponsePacket implements IMessage {

    private long clientSentAtMs;
    private long serverReceivedAtMs;
    private long serverSentAtMs;

    public ClockSyncResponsePacket() {}

    public ClockSyncResponsePacket(long clientSentAtMs, long serverReceivedAtMs, long serverSentAtMs) {
        this.clientSentAtMs = clientSentAtMs;
        this.serverReceivedAtMs = serverReceivedAtMs;
        this.serverSentAtMs = serverSentAtMs;
    }

    public long getClientSentAtMs() {
        return clientSentAtMs;
    }

    public long getServerReceivedAtMs() {
        return serverReceivedAtMs;
    }

    public long getServerSentAtMs() {
        return serverSentAtMs;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(clientSentAtMs);
        buf.writeLong(serverReceivedAtMs);
        buf.writeLong(serverSentAtMs);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        clientSentAtMs = buf.readLong();
        serverReceivedAtMs = buf.readLong();
        serverSentAtMs = buf.readLong();
    }
}
