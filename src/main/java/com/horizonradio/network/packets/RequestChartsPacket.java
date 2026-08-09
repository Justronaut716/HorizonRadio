package com.horizonradio.network.packets;

import com.horizonradio.core.server.ChartRegionCatalog;
import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests the current YouTube Music Top Songs chart for one canonical region. */
public class RequestChartsPacket implements IMessage {

    private static final int MAX_REGION_CODE_BYTES = 16;

    private String regionCode;
    private boolean forceRefresh;

    public RequestChartsPacket() {
        this(ChartRegionCatalog.GLOBAL_CODE, false);
    }

    public RequestChartsPacket(boolean forceRefresh) {
        this(ChartRegionCatalog.GLOBAL_CODE, forceRefresh);
    }

    public RequestChartsPacket(String regionCode, boolean forceRefresh) {
        this.regionCode = normalizeRegionCode(regionCode);
        this.forceRefresh = forceRefresh;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public boolean isForceRefresh() {
        return forceRefresh;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, regionCode, MAX_REGION_CODE_BYTES);
        buf.writeBoolean(forceRefresh);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        regionCode = PacketBufferUtil.readString(buf, MAX_REGION_CODE_BYTES);
        if (regionCode.length() == 0) {
            regionCode = ChartRegionCatalog.GLOBAL_CODE;
        }
        forceRefresh = buf.readBoolean();
    }

    private static String normalizeRegionCode(String value) {
        if (value == null || value.trim()
            .length() == 0) {
            throw new IllegalArgumentException("region code must not be empty");
        }
        String normalized = value.trim()
            .toUpperCase(java.util.Locale.ROOT);
        if (normalized.getBytes(java.nio.charset.Charset.forName("UTF-8")).length > MAX_REGION_CODE_BYTES) {
            throw new IllegalArgumentException("region code exceeds " + MAX_REGION_CODE_BYTES + " bytes");
        }
        return normalized;
    }
}
