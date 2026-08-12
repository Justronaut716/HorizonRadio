package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests an authoritative playlist snapshot after a client detects a revision gap. */
public class PlaylistResyncRequestPacket implements IMessage {

    private long knownRevision;

    public PlaylistResyncRequestPacket() {}

    public PlaylistResyncRequestPacket(long knownRevision) {
        if (knownRevision < 0L) {
            throw new IllegalArgumentException("known playlist revision must not be negative");
        }
        this.knownRevision = knownRevision;
    }

    public long getKnownRevision() {
        return knownRevision;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (knownRevision < 0L) {
            throw new IllegalArgumentException("known playlist revision must not be negative");
        }
        buf.writeLong(knownRevision);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        knownRevision = buf.readLong();
        if (knownRevision < 0L) {
            throw new IllegalArgumentException("known playlist revision must not be negative");
        }
    }
}
