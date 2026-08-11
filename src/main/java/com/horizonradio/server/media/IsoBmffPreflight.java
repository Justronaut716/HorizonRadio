package com.horizonradio.server.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded ISO-BMFF structure and sample-table validation before JAAD parsing. */
final class IsoBmffPreflight {

    static final long MAX_INPUT_BYTES = 32L * 1024L * 1024L;
    static final int MAX_FRAME_BYTES = 1024 * 1024;
    static final int MAX_FRAMES = 262144;
    private static final int MAX_BOXES = 65536;
    private static final int MAX_DEPTH = 8;

    private static final int FTYP = type("ftyp");
    private static final int MOOV = type("moov");
    private static final int TRAK = type("trak");
    private static final int MDIA = type("mdia");
    private static final int MINF = type("minf");
    private static final int STBL = type("stbl");
    private static final int EDTS = type("edts");
    private static final int DINF = type("dinf");
    private static final int UDTA = type("udta");
    private static final int META = type("meta");
    private static final int MDAT = type("mdat");
    private static final int STSZ = type("stsz");
    private static final int STCO = type("stco");
    private static final int CO64 = type("co64");
    private static final int STSC = type("stsc");
    private static final int STTS = type("stts");
    private static final int STSD = type("stsd");
    private static final int DREF = type("dref");
    private static final int MOOF = type("moof");
    private static final int MVEX = type("mvex");

    static byte[] readAndValidate(InputStream input) throws IOException {
        if (input == null) {
            throw new NullPointerException("input");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                continue;
            }
            if (count > MAX_INPUT_BYTES - total) {
                throw new MediaException("M4A preflight input exceeds bounded size");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        byte[] bytes = output.toByteArray();
        new Scanner(bytes).scan();
        return bytes;
    }

    private static final class Scanner {

        private final byte[] bytes;
        private int boxCount;
        private boolean ftypSeen;
        private boolean moovSeen;
        private boolean stszSeen;
        private long mediaPayloadBytes;
        private long declaredSampleBytes;

        Scanner(byte[] bytes) {
            this.bytes = bytes;
        }

        void scan() throws IOException {
            scanRange(0, bytes.length, 0, false);
            if (!ftypSeen || !moovSeen || !stszSeen) {
                throw new MediaException("M4A preflight is missing required ISO-BMFF boxes");
            }
            if (declaredSampleBytes > mediaPayloadBytes) {
                throw new MediaException("M4A preflight sample payload exceeds in-file media data");
            }
        }

        private void scanRange(int start, int end, int depth, boolean metaChildren) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new MediaException("M4A preflight box nesting exceeds limit");
            }
            int position = start;
            if (metaChildren) {
                if (end - position < 4) {
                    throw new MediaException("M4A preflight truncated meta box");
                }
                position += 4;
            }
            while (position < end) {
                Box box = readBox(position, end);
                if (++boxCount > MAX_BOXES) {
                    throw new MediaException("M4A preflight box count exceeds limit");
                }
                if (box.type == FTYP) {
                    ftypSeen = true;
                } else if (box.type == MOOV) {
                    if (moovSeen) {
                        throw new MediaException("M4A preflight contains more than one moov box");
                    }
                    moovSeen = true;
                    scanRange(box.dataOffset, box.end, depth + 1, false);
                } else if (box.type == MDAT) {
                    mediaPayloadBytes += box.dataLength();
                } else if (box.type == MOOF || box.type == MVEX) {
                    throw new MediaException("M4A preflight rejects fragmented media");
                } else if (isContainer(box.type)) {
                    scanRange(box.dataOffset, box.end, depth + 1, box.type == META);
                } else if (box.type == STSZ) {
                    validateStsz(box);
                } else if (box.type == STCO) {
                    validateCountTable(box, 8, 4, "stco");
                } else if (box.type == CO64) {
                    validateCountTable(box, 8, 8, "co64");
                } else if (box.type == STSC) {
                    validateCountTable(box, 8, 12, "stsc");
                } else if (box.type == STTS) {
                    validateCountTable(box, 8, 8, "stts");
                } else if (box.type == STSD) {
                    validateStsd(box);
                } else if (box.type == DREF) {
                    validateDref(box);
                }
                position = box.end;
            }
        }

        private void validateStsz(Box box) throws IOException {
            if (stszSeen || box.dataLength() < 12) {
                throw new MediaException("M4A preflight invalid stsz table");
            }
            stszSeen = true;
            long sampleSize = unsignedInt(box.dataOffset + 4);
            long sampleCount = unsignedInt(box.dataOffset + 8);
            if (sampleCount == 0L || sampleCount > MAX_FRAMES) {
                throw new MediaException("M4A preflight stsz sample count exceeds limit");
            }
            if (sampleSize != 0L) {
                if (sampleSize > MAX_FRAME_BYTES) {
                    throw new MediaException("M4A preflight stsz sample size exceeds limit");
                }
                addSampleBytes(sampleSize * sampleCount);
                return;
            }
            long required = 12L + sampleCount * 4L;
            if (required > box.dataLength()) {
                throw new MediaException("M4A preflight truncated stsz table");
            }
            int position = box.dataOffset + 12;
            for (int i = 0; i < (int) sampleCount; i++) {
                long sample = unsignedInt(position);
                if (sample == 0L || sample > MAX_FRAME_BYTES) {
                    throw new MediaException("M4A preflight stsz individual sample exceeds limit");
                }
                addSampleBytes(sample);
                position += 4;
            }
        }

        private void validateCountTable(Box box, int prefixBytes, int entryBytes, String name) throws IOException {
            if (box.dataLength() < prefixBytes) {
                throw new MediaException("M4A preflight truncated " + name + " table");
            }
            long count = unsignedInt(box.dataOffset + 4);
            if (count > MAX_FRAMES || (long) prefixBytes + count * entryBytes > box.dataLength()) {
                throw new MediaException("M4A preflight " + name + " table exceeds limit");
            }
        }

        private void validateStsd(Box box) throws IOException {
            if (box.dataLength() < 8) {
                throw new MediaException("M4A preflight truncated stsd table");
            }
            long count = unsignedInt(box.dataOffset + 4);
            if (count != 1L) {
                throw new MediaException("M4A preflight requires exactly one sample description");
            }
        }

        private void validateDref(Box box) throws IOException {
            if (box.dataLength() < 8) {
                throw new MediaException("M4A preflight truncated dref table");
            }
            long count = unsignedInt(box.dataOffset + 4);
            if (count != 1L || box.dataLength() < 20) {
                throw new MediaException("M4A preflight rejects external data references");
            }
        }

        private void addSampleBytes(long amount) throws IOException {
            if (amount < 0L || declaredSampleBytes > MAX_INPUT_BYTES - amount) {
                throw new MediaException("M4A preflight declared sample payload exceeds limit");
            }
            declaredSampleBytes += amount;
        }

        private Box readBox(int offset, int limit) throws IOException {
            if (limit - offset < 8) {
                throw new MediaException("M4A preflight truncated box header");
            }
            long size = unsignedInt(offset);
            int type = readInt(bytes, offset + 4);
            int headerLength = 8;
            if (size == 0L) {
                throw new MediaException("M4A preflight rejects unknown-size box");
            }
            if (size == 1L) {
                if (limit - offset < 16) {
                    throw new MediaException("M4A preflight truncated extended box header");
                }
                size = unsignedLong(offset + 8);
                headerLength = 16;
            }
            if (size < headerLength || size > limit - offset) {
                throw new MediaException("M4A preflight invalid box size");
            }
            return new Box(type, offset + headerLength, (int) (offset + size));
        }

        private long unsignedInt(int offset) {
            return ((long) bytes[offset] & 255L) << 24 | ((long) bytes[offset + 1] & 255L) << 16
                | ((long) bytes[offset + 2] & 255L) << 8
                | (long) bytes[offset + 3] & 255L;
        }

        private long unsignedLong(int offset) throws IOException {
            long high = unsignedInt(offset);
            long low = unsignedInt(offset + 4);
            if (high > 0x7fffffffL) {
                throw new MediaException("M4A preflight extended box size exceeds limit");
            }
            return high << 32 | low;
        }
    }

    private static boolean isContainer(int type) {
        return type == TRAK || type == MDIA
            || type == MINF
            || type == STBL
            || type == EDTS
            || type == DINF
            || type == UDTA
            || type == META;
    }

    private static int type(String value) {
        return (value.charAt(0) << 24) | (value.charAt(1) << 16) | (value.charAt(2) << 8) | value.charAt(3);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
            | (bytes[offset + 2] & 255) << 8
            | bytes[offset + 3] & 255;
    }

    private static final class Box {

        private final int type;
        private final int dataOffset;
        private final int end;

        private Box(int type, int dataOffset, int end) {
            this.type = type;
            this.dataOffset = dataOffset;
            this.end = end;
        }

        private int dataLength() {
            return end - dataOffset;
        }
    }
}
