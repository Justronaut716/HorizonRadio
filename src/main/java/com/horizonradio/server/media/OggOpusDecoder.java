package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;

/** Direct Concentus decoder for packetized Ogg Opus streams. */
public final class OggOpusDecoder implements AudioDecoder {

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        OpusPacketDecoder opus = null;
        boolean finished = false;
        try {
            OggPageReader reader = new OggPageReader(input);
            byte[] head = requiredPacket(reader, "Opus identification header").getData();
            byte[] tags = requiredPacket(reader, "Opus comment header").getData();
            if (!startsWith(tags, "OpusTags")) {
                throw new MediaException("Invalid Opus comment header");
            }
            opus = new OpusPacketDecoder(head, sink);
            int frames = 0;
            OggPageReader.Packet pending = null;
            OggPageReader.Packet packetInfo;
            while ((packetInfo = reader.nextPacketInfo()) != null) {
                if (pending != null) {
                    opus.decodePacket(pending.getData(), -1L);
                    frames++;
                }
                pending = packetInfo;
            }
            if (pending == null) {
                throw new MediaException("Opus stream contains no audio packets");
            }
            if (!reader.hasEndOfStream()) {
                throw new MediaException("Missing Opus EOS granule");
            }
            long finalGranule = reader.getEndOfStreamGranulePosition();
            if (finalGranule < opus.getPreSkip()) {
                throw new MediaException("Invalid Opus EOS granule position");
            }
            opus.decodePacket(pending.getData(), finalGranule - opus.getPreSkip());
            frames++;
            if (frames == 0) {
                throw new MediaException("Opus stream contains no audio packets");
            }
            opus.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(opus, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode Ogg Opus audio", exception);
            abort(opus, sink, finished, wrapped);
            throw wrapped;
        }
    }

    /**
     * Decodes an endless radio Ogg stream until its HTTP connection closes.
     * The finite decoder remains strict about EOS granules; radio input has no
     * finite duration and therefore decodes each packet incrementally.
     */
    public void decodeStreaming(InputStream input, PcmSink sink) throws IOException {
        OpusPacketDecoder opus = null;
        boolean finished = false;
        try {
            OggPageReader reader = OggPageReader.allowNoEos(input);
            byte[] head = requiredPacket(reader, "Opus identification header").getData();
            byte[] tags = requiredPacket(reader, "Opus comment header").getData();
            if (!startsWith(tags, "OpusTags")) {
                throw new MediaException("Invalid Opus comment header");
            }
            opus = new OpusPacketDecoder(head, sink);
            int frames = 0;
            OggPageReader.Packet packet;
            while ((packet = reader.nextPacketInfo()) != null) {
                opus.decodePacket(packet.getData(), -1L);
                frames++;
            }
            if (frames == 0) {
                throw new MediaException("Opus stream contains no audio packets");
            }
            opus.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(opus, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode streaming Ogg Opus audio", exception);
            abort(opus, sink, finished, wrapped);
            throw wrapped;
        }
    }

    private static OggPageReader.Packet requiredPacket(OggPageReader reader, String description) throws IOException {
        OggPageReader.Packet packet = reader.nextPacketInfo();
        if (packet == null) {
            throw new MediaException("Missing " + description);
        }
        return packet;
    }

    private static boolean startsWith(byte[] bytes, String text) {
        if (bytes.length < text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
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
}
