package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

/** Direct JOrbis decoder for bounded, packetized Ogg Vorbis streams. */
public final class OggVorbisDecoder implements AudioDecoder {

    private static final int MAX_PCM_FRAMES = 4096;

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        ResamplingPcmSink pcm = null;
        boolean finished = false;
        try {
            OggPageReader reader = new OggPageReader(input);
            Info info = new Info();
            Comment comment = new Comment();
            info.init();
            comment.init();
            for (int i = 0; i < 3; i++) {
                byte[] header = reader.nextPacket();
                if (header == null || info.synthesis_headerin(comment, packet(header, i == 0)) < 0) {
                    throw new MediaException("Invalid Vorbis header");
                }
            }
            if (info.channels < 1 || info.channels > 2 || info.rate <= 0) {
                throw new MediaException("Unsupported Vorbis PCM format");
            }
            DspState state = new DspState();
            state.synthesis_init(info);
            Block block = new Block(state);
            pcm = new ResamplingPcmSink(new PcmFormat(info.rate, info.channels, 16, true, true), sink);
            int decodedPackets = 0;
            long emittedNativeFrames = 0L;
            OggPageReader.Packet pending = null;
            OggPageReader.Packet packetInfo;
            while ((packetInfo = reader.nextPacketInfo()) != null) {
                if (pending != null) {
                    emittedNativeFrames += decodePacket(block, state, pending, info.channels, pcm, Long.MAX_VALUE);
                    decodedPackets++;
                }
                pending = packetInfo;
            }
            if (pending == null) {
                throw new MediaException("Vorbis stream contains no decodable audio packets");
            }
            if (!reader.hasEndOfStream()) {
                throw new MediaException("Missing Vorbis EOS granule");
            }
            long finalGranule = reader.getEndOfStreamGranulePosition();
            if (finalGranule < 0L || finalGranule < emittedNativeFrames) {
                throw new MediaException("Invalid Vorbis EOS granule position");
            }
            emittedNativeFrames += decodePacket(block, state, pending, info.channels, pcm, finalGranule - emittedNativeFrames);
            decodedPackets++;
            if (emittedNativeFrames != finalGranule) {
                throw new MediaException("Inconsistent Vorbis EOS granule position");
            }
            if (decodedPackets == 0) {
                throw new MediaException("Vorbis stream contains no decodable audio packets");
            }
            pcm.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(pcm, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode Ogg Vorbis audio", exception);
            abort(pcm, sink, finished, wrapped);
            throw wrapped;
        }
    }

    /**
     * Decodes an endless radio Ogg stream until its HTTP connection closes.
     * Unlike the finite-file path this deliberately does not require an EOS
     * page or use a final granule position to trim the last packet.
     */
    public void decodeStreaming(InputStream input, PcmSink sink) throws IOException {
        ResamplingPcmSink pcm = null;
        boolean finished = false;
        try {
            OggPageReader reader = OggPageReader.allowNoEos(input);
            Info info = new Info();
            Comment comment = new Comment();
            info.init();
            comment.init();
            for (int i = 0; i < 3; i++) {
                byte[] header = reader.nextPacket();
                if (header == null || info.synthesis_headerin(comment, packet(header, i == 0)) < 0) {
                    throw new MediaException("Invalid Vorbis header");
                }
            }
            if (info.channels < 1 || info.channels > 2 || info.rate <= 0) {
                throw new MediaException("Unsupported Vorbis PCM format");
            }
            DspState state = new DspState();
            state.synthesis_init(info);
            Block block = new Block(state);
            pcm = new ResamplingPcmSink(new PcmFormat(info.rate, info.channels, 16, true, true), sink);
            int decodedPackets = 0;
            OggPageReader.Packet packetInfo;
            while ((packetInfo = reader.nextPacketInfo()) != null) {
                if (decodePacket(block, state, packetInfo, info.channels, pcm, Long.MAX_VALUE) > 0L) {
                    decodedPackets++;
                }
            }
            if (decodedPackets == 0) {
                throw new MediaException("Vorbis stream contains no decodable audio packets");
            }
            pcm.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(pcm, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode streaming Ogg Vorbis audio", exception);
            abort(pcm, sink, finished, wrapped);
            throw wrapped;
        }
    }

    private static Packet packet(byte[] bytes, boolean bos) {
        Packet packet = new Packet();
        packet.packet_base = bytes;
        packet.packet = 0;
        packet.bytes = bytes.length;
        packet.b_o_s = bos ? 1 : 0;
        return packet;
    }

    private static long decodePacket(Block block, DspState state, OggPageReader.Packet packetInfo, int channels, PcmSink sink, long maximumFrames) throws IOException {
        if (block.synthesis(packet(packetInfo.getData(), false)) != 0) {
            throw new MediaException("Invalid Vorbis audio packet");
        }
        state.synthesis_blockin(block);
        return writeAvailablePcm(state, channels, sink, maximumFrames);
    }

    private static long writeAvailablePcm(DspState state, int channels, PcmSink sink, long maximumFrames) throws IOException {
        float[][][] pcm = new float[1][][];
        int[] offsets = new int[channels];
        long writtenFrames = 0L;
        int samples;
        while ((samples = state.synthesis_pcmout(pcm, offsets)) > 0) {
            int count = Math.min(samples, MAX_PCM_FRAMES);
            int writable = (int) Math.min((long) count, maximumFrames - writtenFrames);
            byte[] bytes = new byte[writable * channels * 2];
            for (int frame = 0; frame < writable; frame++) {
                for (int channel = 0; channel < channels; channel++) {
                    int value = (int) (pcm[0][channel][offsets[channel] + frame] * 32767.0f);
                    value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
                    int offset = (frame * channels + channel) * 2;
                    bytes[offset] = (byte) value;
                    bytes[offset + 1] = (byte) (value >>> 8);
                }
            }
            if (bytes.length > 0) {
                sink.write(bytes, 0, bytes.length);
            }
            state.synthesis_read(count);
            writtenFrames += writable;
        }
        return writtenFrames;
    }

    private static void abort(PcmSink pcm, PcmSink sink, boolean finished, IOException failure) {
        if (finished) {
            return;
        }
        try {
            if (pcm == null) {
                sink.abort();
            } else {
                pcm.abort();
            }
        } catch (IOException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }
}
