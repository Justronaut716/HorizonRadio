package com.horizonradio.network.packets;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class SearchResultsPacket implements IMessage {

    private static final int MAX_CHART_REGION_CODE_BYTES = 16;

    private List<Entry> results;
    private boolean charts;
    private String chartRegionCode;

    public SearchResultsPacket() {
        results = new ArrayList<Entry>();
        chartRegionCode = "";
    }

    public SearchResultsPacket(List<Entry> results) {
        this(results, false, "");
    }

    public SearchResultsPacket(List<Entry> results, boolean charts) {
        this(results, charts, "");
    }

    public SearchResultsPacket(List<Entry> results, boolean charts, String chartRegionCode) {
        this.results = copy(results);
        this.charts = charts;
        this.chartRegionCode = charts ? normalizeChartRegionCode(chartRegionCode) : "";
    }

    public List<Entry> getResults() {
        return Collections.unmodifiableList(results);
    }

    public boolean isCharts() {
        return charts;
    }

    public String getChartRegionCode() {
        return chartRegionCode;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(charts);
        PacketBufferUtil.writeString(buf, chartRegionCode, MAX_CHART_REGION_CODE_BYTES);
        PacketBufferUtil.writeCount(buf, results.size());
        for (Entry result : results) {
            PacketBufferUtil.writeString(buf, result.getVideoId());
            PacketBufferUtil.writeString(buf, result.getTitle());
            PacketBufferUtil.writeString(buf, result.getChannel());
            PacketBufferUtil.writeString(buf, result.getDuration());
            PacketBufferUtil.writeString(buf, result.getThumbnail());
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        charts = buf.readBoolean();
        chartRegionCode = PacketBufferUtil.readString(buf, MAX_CHART_REGION_CODE_BYTES);
        if (!charts) {
            chartRegionCode = "";
        }
        int count = PacketBufferUtil.readCount(buf);
        List<Entry> decoded = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(
                new Entry(
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf)));
        }
        results = decoded;
    }

    private static List<Entry> copy(List<Entry> values) {
        if (values == null) {
            throw new IllegalArgumentException("results must not be null");
        }
        return new ArrayList<Entry>(values);
    }

    private static String normalizeChartRegionCode(String value) {
        if (value == null || value.trim()
            .length() == 0) {
            return "";
        }
        String normalized = value.trim()
            .toUpperCase(java.util.Locale.ROOT);
        if (normalized.getBytes(Charset.forName("UTF-8")).length > MAX_CHART_REGION_CODE_BYTES) {
            throw new IllegalArgumentException("chart region code exceeds " + MAX_CHART_REGION_CODE_BYTES + " bytes");
        }
        return normalized;
    }

    public static final class Entry {

        private final String videoId;
        private final String title;
        private final String channel;
        private final String duration;
        private final String thumbnail;

        public Entry(String videoId, String title, String channel, String duration, String thumbnail) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
            this.thumbnail = thumbnail;
        }

        public String getVideoId() {
            return videoId;
        }

        public String getTitle() {
            return title;
        }

        public String getChannel() {
            return channel;
        }

        public String getDuration() {
            return duration;
        }

        public String getThumbnail() {
            return thumbnail;
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
            return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
                && Objects.equals(channel, that.channel)
                && Objects.equals(duration, that.duration)
                && Objects.equals(thumbnail, that.thumbnail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoId, title, channel, duration, thumbnail);
        }
    }
}
