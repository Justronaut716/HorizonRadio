package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class RadioStatePacket implements IMessage {

    public static final int MAX_STATUS_BYTES = 160;

    private boolean active;
    private boolean musicMode;
    private long generation;
    private String stationUuid;
    private String stationName;
    private String status;

    public RadioStatePacket() {}

    public RadioStatePacket(boolean active, long generation, String stationUuid, String stationName, String status) {
        this(active, generation, stationUuid, stationName, status, false);
    }

    public RadioStatePacket(boolean active, long generation, String stationUuid, String stationName, String status,
        boolean musicMode) {
        validate(stationUuid, stationName, status);
        this.active = active;
        this.musicMode = musicMode;
        this.generation = generation;
        this.stationUuid = stationUuid;
        this.stationName = stationName;
        this.status = status;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMusicMode() {
        return musicMode;
    }

    public long getGeneration() {
        return generation;
    }

    public String getStationUuid() {
        return stationUuid;
    }

    public String getStationName() {
        return stationName;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validate(stationUuid, stationName, status);
        buf.writeBoolean(active);
        buf.writeBoolean(musicMode);
        buf.writeLong(generation);
        PacketBufferUtil.writeString(buf, stationUuid, SelectRadioStationPacket.MAX_STATION_UUID_BYTES);
        PacketBufferUtil.writeString(buf, stationName, RadioSearchResultsPacket.MAX_STATION_NAME_BYTES);
        PacketBufferUtil.writeString(buf, status, MAX_STATUS_BYTES);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        active = buf.readBoolean();
        musicMode = buf.readBoolean();
        generation = buf.readLong();
        stationUuid = PacketBufferUtil.readString(buf, SelectRadioStationPacket.MAX_STATION_UUID_BYTES);
        stationName = PacketBufferUtil.readString(buf, RadioSearchResultsPacket.MAX_STATION_NAME_BYTES);
        status = PacketBufferUtil.readString(buf, MAX_STATUS_BYTES);
        validate(stationUuid, stationName, status);
    }

    private static void validate(String uuid, String name, String value) {
        SelectRadioStationPacket.validateStationUuid(uuid);
        SelectRadioStationPacket
            .validateUtf8(name, RadioSearchResultsPacket.MAX_STATION_NAME_BYTES, "radio station name");
        SelectRadioStationPacket.validateUtf8(value, MAX_STATUS_BYTES, "radio status");
    }
}
