package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class RadioSearchRequestPacket implements IMessage {

    public static final int MAX_QUERY_CHARACTERS = 100;

    private String query;

    public RadioSearchRequestPacket() {}

    public RadioSearchRequestPacket(String query) {
        validateQuery(query);
        this.query = query;
    }

    public String getQuery() {
        return query;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validateQuery(query);
        PacketBufferUtil.writeString(buf, query, MAX_QUERY_CHARACTERS * 3);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        query = PacketBufferUtil.readString(buf, MAX_QUERY_CHARACTERS * 3);
        validateQuery(query);
    }

    private static void validateQuery(String value) {
        if (value == null || value.length() > MAX_QUERY_CHARACTERS) {
            throw new IllegalArgumentException(
                "radio search query must be at most " + MAX_QUERY_CHARACTERS + " characters");
        }
    }
}
