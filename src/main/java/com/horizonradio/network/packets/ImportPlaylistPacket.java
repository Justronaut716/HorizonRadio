package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Sends a validated YouTube playlist URL to the server for import. */
public class ImportPlaylistPacket implements IMessage {

    private String playlistUrl;

    public ImportPlaylistPacket() {
        playlistUrl = "";
    }

    public ImportPlaylistPacket(String playlistUrl) {
        if (playlistUrl == null || playlistUrl.length() > PacketBufferUtil.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("playlist URL is invalid");
        }
        this.playlistUrl = playlistUrl;
    }

    public String getPlaylistUrl() {
        return playlistUrl;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, playlistUrl);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playlistUrl = PacketBufferUtil.readString(buf);
    }
}
