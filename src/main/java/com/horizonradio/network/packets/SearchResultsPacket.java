package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class SearchResultsPacket implements IMessage {

    private List<Entry> results;
    private boolean charts;

    public SearchResultsPacket() {
        results = new ArrayList<Entry>();
    }

    public SearchResultsPacket(List<Entry> results) {
        this(results, false);
    }

    public SearchResultsPacket(List<Entry> results, boolean charts) {
        this.results = copy(results);
        this.charts = charts;
    }

    public List<Entry> getResults() {
        return Collections.unmodifiableList(results);
    }

    public boolean isCharts() {
        return charts;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(charts);
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
