package com.horizonradio.network.packets;

import java.util.Arrays;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class RadioAudioChunkPacket implements IMessage {

    private long generation;
    private long sequence;
    private byte[] data;

    public RadioAudioChunkPacket() {
        data = new byte[0];
    }

    public RadioAudioChunkPacket(long generation, long sequence, byte[] data) {
        validateData(data);
        this.generation = generation;
        this.sequence = sequence;
        this.data = Arrays.copyOf(data, data.length);
    }

    public long getGeneration() {
        return generation;
    }

    public long getSequence() {
        return sequence;
    }

    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validateData(data);
        buf.writeLong(generation);
        buf.writeLong(sequence);
        PacketBufferUtil.writeByteArray(buf, data);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        generation = buf.readLong();
        sequence = buf.readLong();
        data = PacketBufferUtil.readByteArray(buf);
    }

    private static void validateData(byte[] value) {
        if (value == null || value.length > PacketBufferUtil.MAX_BYTE_ARRAY_BYTES) {
            throw new IllegalArgumentException(
                "radio audio data must be at most " + PacketBufferUtil.MAX_BYTE_ARRAY_BYTES + " bytes");
        }
    }
}
