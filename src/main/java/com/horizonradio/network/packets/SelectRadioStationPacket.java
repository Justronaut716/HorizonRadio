package com.horizonradio.network.packets;

import java.nio.charset.Charset;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class SelectRadioStationPacket implements IMessage {

    public static final int MAX_STATION_UUID_BYTES = 64;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private String stationUuid;

    public SelectRadioStationPacket() {}

    public SelectRadioStationPacket(String stationUuid) {
        validateStationUuid(stationUuid);
        this.stationUuid = stationUuid;
    }

    public String getStationUuid() {
        return stationUuid;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validateStationUuid(stationUuid);
        PacketBufferUtil.writeString(buf, stationUuid, MAX_STATION_UUID_BYTES);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        stationUuid = PacketBufferUtil.readString(buf, MAX_STATION_UUID_BYTES);
        validateStationUuid(stationUuid);
    }

    static void validateStationUuid(String value) {
        validateUtf8(value, MAX_STATION_UUID_BYTES, "radio station UUID");
    }

    static void validateUtf8(String value, int maxBytes, String field) {
        if (value == null || value.getBytes(UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " must be at most " + maxBytes + " bytes");
        }
    }
}
