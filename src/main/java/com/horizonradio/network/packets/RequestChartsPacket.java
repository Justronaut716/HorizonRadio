package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests the current German YouTube Music Top Songs chart. */
public class RequestChartsPacket implements IMessage {

    private boolean forceRefresh;

    public RequestChartsPacket() {}

    public RequestChartsPacket(boolean forceRefresh) {
        this.forceRefresh = forceRefresh;
    }

    public boolean isForceRefresh() {
        return forceRefresh;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(forceRefresh);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        forceRefresh = buf.readBoolean();
    }
}
