package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class RadioSearchResultsPacket implements IMessage {

    public static final int MAX_ENTRIES = 50;
    public static final int MAX_STATION_NAME_BYTES = 200;

    private List<Entry> entries;

    public RadioSearchResultsPacket() {
        entries = new ArrayList<Entry>();
    }

    public RadioSearchResultsPacket(List<Entry> entries) {
        this.entries = copy(entries);
    }

    public List<Entry> getEntries() {
        return new ArrayList<Entry>(entries);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        List<Entry> values = copy(entries);
        PacketBufferUtil.writeCount(buf, values.size());
        for (Entry entry : values) {
            PacketBufferUtil.writeString(buf, entry.getStationUuid(), SelectRadioStationPacket.MAX_STATION_UUID_BYTES);
            PacketBufferUtil.writeString(buf, entry.getName(), MAX_STATION_NAME_BYTES);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = PacketBufferUtil.readCount(buf);
        if (count > MAX_ENTRIES) {
            throw new IllegalArgumentException("radio search results must contain at most " + MAX_ENTRIES + " entries");
        }
        List<Entry> decoded = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(
                new Entry(
                    PacketBufferUtil.readString(buf, SelectRadioStationPacket.MAX_STATION_UUID_BYTES),
                    PacketBufferUtil.readString(buf, MAX_STATION_NAME_BYTES)));
        }
        entries = decoded;
    }

    private static List<Entry> copy(List<Entry> values) {
        if (values == null || values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("radio search results must contain at most " + MAX_ENTRIES + " entries");
        }
        return new ArrayList<Entry>(values);
    }

    public static final class Entry {

        private final String stationUuid;
        private final String name;

        public Entry(String stationUuid, String name) {
            SelectRadioStationPacket.validateStationUuid(stationUuid);
            SelectRadioStationPacket.validateUtf8(name, MAX_STATION_NAME_BYTES, "radio station name");
            this.stationUuid = stationUuid;
            this.name = name;
        }

        public String getStationUuid() {
            return stationUuid;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) other;
            return Objects.equals(stationUuid, that.stationUuid) && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stationUuid, name);
        }
    }
}
