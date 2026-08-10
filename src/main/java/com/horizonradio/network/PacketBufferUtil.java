package com.horizonradio.network;

import java.nio.charset.Charset;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/** Bounded primitive encodings shared by all HorizonRadio messages. */
public final class PacketBufferUtil {

    public static final int MAX_STRING_BYTES = 16383;
    public static final int MAX_COLLECTION_SIZE = 1024;
    public static final int MAX_BYTE_ARRAY_BYTES = 30 * 1024;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private PacketBufferUtil() {}

    public static void writeString(ByteBuf buf, String value) {
        writeString(buf, value, MAX_STRING_BYTES);
    }

    public static void writeString(ByteBuf buf, String value, int maxBytes) {
        if (value == null) {
            throw new IllegalArgumentException("string must not be null");
        }
        byte[] bytes = value.getBytes(UTF_8);
        if (maxBytes < 0 || maxBytes > MAX_STRING_BYTES || bytes.length > maxBytes) {
            throw new IllegalArgumentException("string exceeds " + maxBytes + " bytes");
        }
        ByteBufUtils.writeUTF8String(buf, value);
    }

    /** Returns the longest code-point-safe prefix that fits the supplied UTF-8 byte bound. */
    public static String truncateUtf8(String value, int maxBytes) {
        if (value == null) {
            throw new IllegalArgumentException("string must not be null");
        }
        if (maxBytes < 0 || maxBytes > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("invalid UTF-8 byte bound: " + maxBytes);
        }
        if (value.getBytes(UTF_8).length <= maxBytes) {
            return value;
        }

        int usedBytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int characterCount = Character.charCount(codePoint);
            int codePointBytes = value.substring(end, end + characterCount)
                .getBytes(UTF_8).length;
            if (usedBytes + codePointBytes > maxBytes) {
                break;
            }
            usedBytes += codePointBytes;
            end += characterCount;
        }
        return value.substring(0, end);
    }

    public static String readString(ByteBuf buf) {
        return readString(buf, MAX_STRING_BYTES);
    }

    public static String readString(ByteBuf buf, int maxBytes) {
        int length = ByteBufUtils.readVarInt(buf, 2);
        if (maxBytes < 0 || maxBytes > MAX_STRING_BYTES || length < 0 || length > maxBytes) {
            throw new IllegalArgumentException("string exceeds " + maxBytes + " bytes");
        }
        if (buf.readableBytes() < length) {
            throw new IllegalArgumentException("truncated string");
        }
        String value = buf.toString(buf.readerIndex(), length, UTF_8);
        buf.readerIndex(buf.readerIndex() + length);
        return value;
    }

    public static void writeCount(ByteBuf buf, int count) {
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException("count must be between 0 and " + MAX_COLLECTION_SIZE);
        }
        ByteBufUtils.writeVarInt(buf, count, 5);
    }

    public static int readCount(ByteBuf buf) {
        int count = ByteBufUtils.readVarInt(buf, 5);
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException("count must be between 0 and " + MAX_COLLECTION_SIZE);
        }
        return count;
    }

    public static void writeByteArray(ByteBuf buf, byte[] bytes) {
        writeByteArray(buf, bytes, MAX_BYTE_ARRAY_BYTES);
    }

    public static void writeByteArray(ByteBuf buf, byte[] bytes, int maxBytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("byte array must not be null");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("byte array exceeds " + maxBytes + " bytes");
        }
        ByteBufUtils.writeVarInt(buf, bytes.length, 5);
        buf.writeBytes(bytes);
    }

    public static byte[] readByteArray(ByteBuf buf) {
        return readByteArray(buf, MAX_BYTE_ARRAY_BYTES);
    }

    public static byte[] readByteArray(ByteBuf buf, int maxBytes) {
        int length = ByteBufUtils.readVarInt(buf, 5);
        if (length < 0 || length > maxBytes) {
            throw new IllegalArgumentException("byte array exceeds " + maxBytes + " bytes");
        }
        if (buf.readableBytes() < length) {
            throw new IllegalArgumentException("truncated byte array");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }
}
