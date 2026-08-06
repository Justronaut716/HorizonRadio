package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class AddToPlaylistPacket implements IMessage {

    private String videoId;
    private String title;
    private String duration;

    public AddToPlaylistPacket() {}

    public AddToPlaylistPacket(String videoId, String title, String duration) {
        this.videoId = videoId;
        this.title = title;
        this.duration = duration;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public String getDuration() {
        return duration;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, videoId);
        PacketBufferUtil.writeString(buf, title);
        PacketBufferUtil.writeString(buf, duration);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        videoId = PacketBufferUtil.readString(buf);
        title = PacketBufferUtil.readString(buf);
        duration = PacketBufferUtil.readString(buf);
    }
}
