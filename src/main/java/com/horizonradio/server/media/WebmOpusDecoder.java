package com.horizonradio.server.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Bounded WebM reader for a single in-file Opus audio track.
 * It deliberately accepts only finite-size EBML elements and unlaced
 * {@code SimpleBlock}s, which keeps packet boundaries and resource use clear.
 */
public final class WebmOpusDecoder implements AudioDecoder {

    // The backend permits tracks up to 192 MiB; valid yt-dlp audio candidates can exceed 16 MiB.
    private static final int MAX_INPUT_BYTES = 192 * 1024 * 1024;
    private static final int MAX_ELEMENT_SIZE = 4 * 1024 * 1024;
    private static final int MAX_SEGMENT_SIZE = MAX_INPUT_BYTES;
    private static final int MAX_PACKET_SIZE = 512 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final long MAX_CLUSTER_TIMECODE_SPAN = 60 * 1000L;
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final int ID_EBML = 0x1a45dfa3;
    private static final int ID_EBML_VERSION = 0x4286;
    private static final int ID_EBML_READ_VERSION = 0x42f7;
    private static final int ID_EBML_MAX_ID_LENGTH = 0x42f2;
    private static final int ID_EBML_MAX_SIZE_LENGTH = 0x42f3;
    private static final int ID_DOC_TYPE = 0x4282;
    private static final int ID_DOC_TYPE_VERSION = 0x4287;
    private static final int ID_DOC_TYPE_READ_VERSION = 0x4285;
    private static final int ID_SEGMENT = 0x18538067;
    private static final int ID_INFO = 0x1549a966;
    private static final int ID_TRACKS = 0x1654ae6b;
    private static final int ID_TRACK_ENTRY = 0xae;
    private static final int ID_CLUSTER = 0x1f43b675;
    private static final int ID_BLOCK_GROUP = 0xa0;
    private static final int ID_BLOCK = 0xa1;
    private static final int ID_DISCARD_PADDING = 0x75a2;
    private static final int ID_TIMECODE_SCALE = 0x2ad7b1;
    private static final int ID_DURATION = 0x4489;
    private static final int ID_TRACK_NUMBER = 0xd7;
    private static final int ID_TRACK_TYPE = 0x83;
    private static final int ID_CODEC_ID = 0x86;
    private static final int ID_CODEC_PRIVATE = 0x63a2;
    private static final int ID_SIMPLE_BLOCK = 0xa3;
    private static final int ID_CLUSTER_TIMECODE = 0xe7;

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        OpusPacketDecoder opus = null;
        boolean finished = false;
        try {
            if (input == null || sink == null) {
                throw new NullPointerException(input == null ? "input" : "sink");
            }
            Parser parser = new Parser(readBounded(input));
            parser.parse();
            opus = new OpusPacketDecoder(parser.opusHead, sink);
            parser.decodeBlocks(opus);
            opus.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(opus, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode WebM Opus audio", exception);
            abort(opus, sink, finished, wrapped);
            throw wrapped;
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                continue;
            }
            if (count > MAX_INPUT_BYTES - total) {
                throw new MediaException("WebM input exceeds bounded size");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static void abort(OpusPacketDecoder opus, PcmSink sink, boolean finished, IOException failure) {
        if (finished) {
            return;
        }
        try {
            if (opus == null) {
                sink.abort();
            } else {
                opus.abort();
            }
        } catch (IOException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static final class Parser {

        private final byte[] bytes;
        private int maxElementIdLength = 4;
        private int maxElementSizeLength = 8;
        private int opusTrackNumber = -1;
        private byte[] opusHead;
        private long timecodeScale = 1000000L;
        private double duration = -1.0d;
        private int[] blockOffsets = new int[16];
        private int[] blockLengths = new int[16];
        private long[] blockTimes = new long[16];
        private long[] blockDiscardPaddingNanos = new long[16];
        private int blockCount;
        private boolean segmentSeen;
        private boolean tracksSeen;

        Parser(byte[] bytes) {
            this.bytes = bytes;
        }

        void parse() throws IOException {
            if (bytes.length == 0) {
                throw new MediaException("Missing WebM EBML header");
            }
            Element ebml = readElement(0, bytes.length);
            if (ebml.id != ID_EBML) {
                throw new MediaException("Missing WebM EBML header before Segment");
            }
            parseEbmlHeader(ebml.dataOffset, ebml.end, 0);
            if (ebml.end >= bytes.length) {
                throw new MediaException("Missing WebM Segment after EBML header");
            }
            Element segment = readElement(ebml.end, bytes.length, MAX_SEGMENT_SIZE);
            if (segment.id != ID_SEGMENT) {
                throw new MediaException("Missing WebM Segment after EBML header");
            }
            segmentSeen = true;
            parseSegment(segment.dataOffset, segment.end, 0);
            if (segment.end != bytes.length) {
                throw new MediaException("Unsupported trailing WebM top-level element");
            }
            if (!segmentSeen || !tracksSeen || opusHead == null || blockCount == 0) {
                throw new MediaException("WebM is missing a finite Opus audio track or SimpleBlock");
            }
        }

        private void parseEbmlHeader(int start, int end, int depth) throws IOException {
            checkDepth(depth);
            long ebmlVersion = -1L;
            long ebmlReadVersion = -1L;
            long maxIdLength = -1L;
            long maxSizeLength = -1L;
            long docTypeVersion = -1L;
            long docTypeReadVersion = -1L;
            String docType = null;
            int position = start;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_EBML_VERSION) {
                    ebmlVersion = uniqueEbmlValue(ebmlVersion, element, "EBMLVersion");
                } else if (element.id == ID_EBML_READ_VERSION) {
                    ebmlReadVersion = uniqueEbmlValue(ebmlReadVersion, element, "EBMLReadVersion");
                } else if (element.id == ID_EBML_MAX_ID_LENGTH) {
                    maxIdLength = uniqueEbmlValue(maxIdLength, element, "EBMLMaxIDLength");
                } else if (element.id == ID_EBML_MAX_SIZE_LENGTH) {
                    maxSizeLength = uniqueEbmlValue(maxSizeLength, element, "EBMLMaxSizeLength");
                } else if (element.id == ID_DOC_TYPE) {
                    if (docType != null) {
                        throw new MediaException("Duplicate WebM EBML DocType");
                    }
                    docType = new String(bytes, element.dataOffset, element.size, ASCII);
                } else if (element.id == ID_DOC_TYPE_VERSION) {
                    docTypeVersion = uniqueEbmlValue(docTypeVersion, element, "DocTypeVersion");
                } else if (element.id == ID_DOC_TYPE_READ_VERSION) {
                    docTypeReadVersion = uniqueEbmlValue(docTypeReadVersion, element, "DocTypeReadVersion");
                }
                position = element.end;
            }
            if (ebmlVersion != 1L || ebmlReadVersion != 1L
                || maxIdLength < 1L
                || maxIdLength > 4L
                || maxSizeLength < 1L
                || maxSizeLength > 8L
                || !"webm".equals(docType)
                || docTypeVersion < 2L
                || docTypeVersion > 4L
                || docTypeReadVersion < 1L
                || docTypeReadVersion > 2L
                || docTypeReadVersion > docTypeVersion) {
                throw new MediaException("Unsupported or malformed WebM EBML header");
            }
            maxElementIdLength = (int) maxIdLength;
            maxElementSizeLength = (int) maxSizeLength;
        }

        private long uniqueEbmlValue(long previous, Element element, String name) throws IOException {
            if (previous != -1L) {
                throw new MediaException("Duplicate WebM EBML " + name);
            }
            return unsigned(element.dataOffset, element.size);
        }

        void decodeBlocks(OpusPacketDecoder opus) throws IOException {
            long finalSamples = outputSamplesFromDuration();
            long lastTime = -1;
            for (int i = 0; i < blockCount; i++) {
                if (blockTimes[i] < lastTime) {
                    throw new MediaException("WebM SimpleBlock timecodes are not monotonic");
                }
                lastTime = blockTimes[i];
                byte[] packet = new byte[blockLengths[i]];
                System.arraycopy(bytes, blockOffsets[i], packet, 0, packet.length);
                long discard = blockDiscardPaddingNanos[i];
                if (discard != 0L && i != blockCount - 1) {
                    throw new MediaException("WebM DiscardPadding is supported only on the final Opus block");
                }
                long packetFinalSamples = discard == 0L ? finalSamples : -1L;
                opus.decodePacket(
                    packet,
                    i == blockCount - 1 ? packetFinalSamples : -1L,
                    i == blockCount - 1 ? discardSamples(discard) : 0L);
            }
        }

        private long outputSamplesFromDuration() throws IOException {
            if (duration < 0.0d) {
                return -1L;
            }
            double samples = duration * timecodeScale * 48000.0d / 1000000000.0d;
            if (Double.isNaN(samples) || Double.isInfinite(samples) || samples < 0.0d || samples > Long.MAX_VALUE) {
                throw new MediaException("Invalid WebM duration");
            }
            return Math.round(samples);
        }

        private void parseSegment(int start, int end, int depth) throws IOException {
            checkDepth(depth);
            int position = start;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_INFO) {
                    parseInfo(element.dataOffset, element.end, depth + 1);
                } else if (element.id == ID_TRACKS) {
                    if (tracksSeen) {
                        throw new MediaException("WebM contains more than one Tracks element");
                    }
                    tracksSeen = true;
                    parseTracks(element.dataOffset, element.end, depth + 1);
                } else if (element.id == ID_CLUSTER) {
                    if (opusHead == null) {
                        throw new MediaException("WebM Cluster precedes the Opus track definition");
                    }
                    parseCluster(element.dataOffset, element.end, depth + 1);
                }
                position = element.end;
            }
        }

        private void parseInfo(int start, int end, int depth) throws IOException {
            checkDepth(depth);
            int position = start;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_TIMECODE_SCALE) {
                    timecodeScale = unsigned(element.dataOffset, element.size);
                    if (timecodeScale <= 0L || timecodeScale > 1000000000L) {
                        throw new MediaException("Unsupported WebM TimecodeScale");
                    }
                } else if (element.id == ID_DURATION) {
                    duration = floating(element.dataOffset, element.size);
                }
                position = element.end;
            }
        }

        private void parseTracks(int start, int end, int depth) throws IOException {
            checkDepth(depth);
            int position = start;
            int trackEntries = 0;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_TRACK_ENTRY) {
                    parseTrackEntry(element.dataOffset, element.end, depth + 1);
                    trackEntries++;
                }
                position = element.end;
            }
            if (trackEntries != 1 || opusHead == null) {
                throw new MediaException("WebM must contain exactly one supported Opus audio track");
            }
        }

        private void parseTrackEntry(int start, int end, int depth) throws IOException {
            checkDepth(depth);
            long number = -1L;
            long type = -1L;
            String codec = null;
            byte[] privateData = null;
            int position = start;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_TRACK_NUMBER) {
                    number = unsigned(element.dataOffset, element.size);
                } else if (element.id == ID_TRACK_TYPE) {
                    type = unsigned(element.dataOffset, element.size);
                } else if (element.id == ID_CODEC_ID) {
                    codec = new String(bytes, element.dataOffset, element.size, ASCII);
                } else if (element.id == ID_CODEC_PRIVATE) {
                    privateData = copy(element.dataOffset, element.size);
                }
                position = element.end;
            }
            if (number < 1L || number > 126L || type != 2L || !"A_OPUS".equals(codec) || privateData == null) {
                throw new MediaException("Unsupported WebM track; only one A_OPUS audio track is accepted");
            }
            opusTrackNumber = (int) number;
            opusHead = privateData;
        }

        private void parseCluster(int start, int end, int depth) throws IOException {
            checkDepth(depth);
            long clusterTimecode = -1L;
            int position = start;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_CLUSTER_TIMECODE) {
                    clusterTimecode = unsigned(element.dataOffset, element.size);
                } else if (element.id == ID_SIMPLE_BLOCK) {
                    if (clusterTimecode < 0L) {
                        throw new MediaException("WebM Cluster is missing its Timecode before SimpleBlock");
                    }
                    parseBlock(element.dataOffset, element.size, clusterTimecode);
                } else if (element.id == ID_BLOCK_GROUP) {
                    if (clusterTimecode < 0L) {
                        throw new MediaException("WebM Cluster is missing its Timecode before BlockGroup");
                    }
                    parseBlockGroup(element.dataOffset, element.end, depth + 1, clusterTimecode);
                }
                position = element.end;
            }
        }

        private void parseBlockGroup(int start, int end, int depth, long clusterTimecode) throws IOException {
            checkDepth(depth);
            Element block = null;
            long discardPadding = 0L;
            boolean discardPaddingSeen = false;
            int position = start;
            while (position < end) {
                Element element = readElement(position, end);
                if (element.id == ID_BLOCK) {
                    if (block != null) {
                        throw new MediaException("WebM BlockGroup contains more than one Block");
                    }
                    block = element;
                } else if (element.id == ID_DISCARD_PADDING) {
                    if (discardPaddingSeen) {
                        throw new MediaException("WebM BlockGroup contains more than one DiscardPadding");
                    }
                    discardPadding = signed(element.dataOffset, element.size);
                    discardPaddingSeen = true;
                }
                position = element.end;
            }
            if (block == null) {
                throw new MediaException("WebM BlockGroup is missing its Block");
            }
            parseBlock(block.dataOffset, block.size, clusterTimecode, discardPadding);
        }

        private void parseBlock(int offset, int length, long clusterTimecode) throws IOException {
            parseBlock(offset, length, clusterTimecode, 0L);
        }

        private void parseBlock(int offset, int length, long clusterTimecode, long discardPaddingNanos)
            throws IOException {
            Vint track = readVint(offset, offset + length, false, 8);
            int headerEnd = offset + track.length + 3;
            if (headerEnd >= offset + length) {
                throw new MediaException("Truncated WebM SimpleBlock");
            }
            if (track.value != opusTrackNumber) {
                throw new MediaException("WebM SimpleBlock references an unsupported track");
            }
            int relative = (short) (((bytes[offset + track.length] & 255) << 8)
                | (bytes[offset + track.length + 1] & 255));
            int flags = bytes[offset + track.length + 2] & 255;
            if ((flags & 0x06) != 0) {
                throw new MediaException("WebM SimpleBlock lacing is unsupported");
            }
            long timecode = clusterTimecode + relative;
            if (relative < 0 || relative > MAX_CLUSTER_TIMECODE_SPAN || timecode < 0L) {
                throw new MediaException("WebM SimpleBlock timecode exceeds the bounded cluster duration");
            }
            int packetLength = offset + length - headerEnd;
            if (packetLength <= 0 || packetLength > MAX_PACKET_SIZE) {
                throw new MediaException("Invalid bounded WebM Opus packet size");
            }
            addBlock(headerEnd, packetLength, timecode, discardPaddingNanos);
        }

        private void addBlock(int offset, int length, long timecode, long discardPaddingNanos) throws IOException {
            if (blockCount == blockOffsets.length) {
                if (blockCount >= 65536) {
                    throw new MediaException("WebM contains too many Opus packets");
                }
                int newLength = blockOffsets.length * 2;
                int[] newOffsets = new int[newLength];
                int[] newLengths = new int[newLength];
                long[] newTimes = new long[newLength];
                long[] newDiscardPadding = new long[newLength];
                System.arraycopy(blockOffsets, 0, newOffsets, 0, blockCount);
                System.arraycopy(blockLengths, 0, newLengths, 0, blockCount);
                System.arraycopy(blockTimes, 0, newTimes, 0, blockCount);
                System.arraycopy(blockDiscardPaddingNanos, 0, newDiscardPadding, 0, blockCount);
                blockOffsets = newOffsets;
                blockLengths = newLengths;
                blockTimes = newTimes;
                blockDiscardPaddingNanos = newDiscardPadding;
            }
            blockOffsets[blockCount] = offset;
            blockLengths[blockCount] = length;
            blockTimes[blockCount] = timecode;
            blockDiscardPaddingNanos[blockCount] = discardPaddingNanos;
            blockCount++;
        }

        private Element readElement(int offset, int limit) throws IOException {
            return readElement(offset, limit, MAX_ELEMENT_SIZE);
        }

        private Element readElement(int offset, int limit, int maximumSize) throws IOException {
            Vint id = readVint(offset, limit, true, maxElementIdLength);
            Vint size = readVint(offset + id.length, limit, false, maxElementSizeLength);
            if (size.unknown || size.value > maximumSize) {
                throw new MediaException("WebM unknown or oversized EBML element");
            }
            long end = (long) offset + id.length + size.length + size.value;
            if (end > limit) {
                throw new MediaException("Truncated WebM EBML element");
            }
            return new Element((int) id.value, (int) size.value, offset + id.length + size.length, (int) end);
        }

        private Vint readVint(int offset, int limit, boolean id, int maximumLength) throws IOException {
            if (offset >= limit) {
                throw new MediaException("Truncated WebM EBML integer");
            }
            int first = bytes[offset] & 255;
            int length = 1;
            int mask = 0x80;
            while ((first & mask) == 0) {
                length++;
                mask >>>= 1;
                if (length > (id ? 4 : 8)) {
                    throw new MediaException("Unsupported WebM EBML integer length");
                }
            }
            if (length > maximumLength) {
                throw new MediaException("WebM EBML " + (id ? "ID" : "size") + " length exceeds declared maximum");
            }
            if (offset + length > limit) {
                throw new MediaException("Truncated WebM EBML integer");
            }
            long value = id ? first : first & (mask - 1);
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (bytes[offset + i] & 255L);
            }
            long unknown = (1L << (7 * length)) - 1L;
            return new Vint(value, length, !id && value == unknown);
        }

        private long unsigned(int offset, int length) throws IOException {
            if (length < 1 || length > 8) {
                throw new MediaException("Invalid WebM unsigned integer");
            }
            long result = 0L;
            for (int i = 0; i < length; i++) {
                if (result > (Long.MAX_VALUE >>> 8)) {
                    throw new MediaException("WebM unsigned integer is too large");
                }
                result = (result << 8) | (bytes[offset + i] & 255L);
            }
            return result;
        }

        private long signed(int offset, int length) throws IOException {
            if (length < 1 || length > 8) {
                throw new MediaException("Invalid WebM signed integer");
            }
            long result = 0L;
            for (int i = 0; i < length; i++) {
                result = result << 8 | (bytes[offset + i] & 255L);
            }
            if (length < 8 && (bytes[offset] & 128) != 0) {
                result |= -1L << length * 8;
            }
            return result;
        }

        private long discardSamples(long discardPaddingNanos) throws IOException {
            if (discardPaddingNanos < 0L) {
                throw new MediaException("Negative WebM DiscardPadding is unsupported");
            }
            long seconds = discardPaddingNanos / 1000000000L;
            long remainder = discardPaddingNanos % 1000000000L;
            if (seconds > Long.MAX_VALUE / 48000L) {
                throw new MediaException("WebM DiscardPadding exceeds supported range");
            }
            long samples = seconds * 48000L;
            long fractional = (remainder * 48000L + 999999999L) / 1000000000L;
            if (samples > Long.MAX_VALUE - fractional) {
                throw new MediaException("WebM DiscardPadding exceeds supported range");
            }
            return samples + fractional;
        }

        private double floating(int offset, int length) throws IOException {
            if (length == 4) {
                return Float.intBitsToFloat((int) unsigned(offset, length));
            }
            if (length == 8) {
                return Double.longBitsToDouble(unsigned(offset, length));
            }
            throw new MediaException("Invalid WebM floating-point duration");
        }

        private byte[] copy(int offset, int length) {
            byte[] result = new byte[length];
            System.arraycopy(bytes, offset, result, 0, length);
            return result;
        }

        private static void checkDepth(int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new MediaException("WebM EBML nesting is too deep");
            }
        }
    }

    private static final class Element {

        private final int id;
        private final int size;
        private final int dataOffset;
        private final int end;

        private Element(int id, int size, int dataOffset, int end) {
            this.id = id;
            this.size = size;
            this.dataOffset = dataOffset;
            this.end = end;
        }
    }

    private static final class Vint {

        private final long value;
        private final int length;
        private final boolean unknown;

        private Vint(long value, int length, boolean unknown) {
            this.value = value;
            this.length = length;
            this.unknown = unknown;
        }
    }
}
