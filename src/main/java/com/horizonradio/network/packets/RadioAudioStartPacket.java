package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class RadioAudioStartPacket implements IMessage {

    private long generation;
    private long firstSequence;
    private int sampleRate;
    private int channels;
    private int sampleSizeInBits;
    private boolean bigEndian;

    public RadioAudioStartPacket() {}

    public RadioAudioStartPacket(long generation, long firstSequence, int sampleRate, int channels,
        int sampleSizeInBits, boolean bigEndian) {
        this.generation = generation;
        this.firstSequence = firstSequence;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.sampleSizeInBits = sampleSizeInBits;
        this.bigEndian = bigEndian;
    }

    public long getGeneration() {
        return generation;
    }

    public long getFirstSequence() {
        return firstSequence;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getSampleSizeInBits() {
        return sampleSizeInBits;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(generation);
        buf.writeLong(firstSequence);
        buf.writeInt(sampleRate);
        buf.writeInt(channels);
        buf.writeInt(sampleSizeInBits);
        buf.writeBoolean(bigEndian);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        generation = buf.readLong();
        firstSequence = buf.readLong();
        sampleRate = buf.readInt();
        channels = buf.readInt();
        sampleSizeInBits = buf.readInt();
        bigEndian = buf.readBoolean();
    }
}
