package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class SearchRequestPacket implements IMessage {

    private String query;

    public SearchRequestPacket() {}

    public SearchRequestPacket(String query) {
        this.query = query;
    }

    public String getQuery() {
        return query;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, query);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        query = PacketBufferUtil.readString(buf);
    }
}
