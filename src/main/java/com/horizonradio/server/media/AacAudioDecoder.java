package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.AudioDecoderInfo;
import net.sourceforge.jaad.aac.ChannelConfiguration;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.DecoderConfig;
import net.sourceforge.jaad.aac.Profile;
import net.sourceforge.jaad.aac.SampleFrequency;

/** Direct JAAD ADTS AAC adapter. MP4/AAC is intentionally rejected by this ADTS-only adapter. */
public final class AacAudioDecoder implements AudioDecoder {

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        ResamplingPcmSink pcm = null;
        boolean finished = false;
        try {
            AdtsReader reader = new AdtsReader(input);
            Decoder decoder = null;
            int frames = 0;
            byte[] frame;
            while ((frame = reader.nextFrame()) != null) {
                if (decoder == null) decoder = Decoder.create(DecoderConfig.create(reader.decoderInfo()));
                SampleBuffer samples = new SampleBuffer();
                decoder.decodeFrame(frame, samples);
                byte[] data = samples.getData();
                if (data == null || data.length == 0) {
                    throw new MediaException("AAC decoder produced no PCM frame");
                }
                if (pcm == null) {
                    pcm = new ResamplingPcmSink(
                        new PcmFormat(
                            samples.getSampleRate(),
                            samples.getChannels(),
                            samples.getBitsPerSample(),
                            true,
                            !samples.isBigEndian()),
                        sink);
                }
                pcm.write(data, 0, data.length);
                frames++;
            }
            if (frames == 0 || pcm == null) {
                throw new MediaException("AAC stream contains no decodable ADTS frames");
            }
            pcm.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(pcm, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode AAC ADTS audio", exception);
            abort(pcm, sink, finished, wrapped);
            throw wrapped;
        }
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

    /** Bounded ADTS reader: EOF before a header is clean; incomplete headers/frames are not. */
    private static final class AdtsReader {

        private final InputStream input;
        private AudioDecoderInfo decoderInfo;

        private AdtsReader(InputStream input) {
            this.input = input;
        }

        private AudioDecoderInfo decoderInfo() {
            return decoderInfo;
        }

        private byte[] nextFrame() throws IOException {
            int first = input.read();
            if (first < 0) return null;
            byte[] header = new byte[7];
            header[0] = (byte) first;
            readFully(header, 1, 6, "ADTS header");
            if ((header[0] & 255) != 255 || (header[1] & 0xf6) != 0xf0)
                throw new MediaException("Invalid ADTS sync word");
            int profile = (header[2] >>> 6) & 3, frequency = (header[2] >>> 2) & 15,
                channels = ((header[2] & 1) << 2) | ((header[3] >>> 6) & 3);
            int headerLength = (header[1] & 1) == 0 ? 9 : 7;
            int frameLength = ((header[3] & 3) << 11) | ((header[4] & 255) << 3) | ((header[5] >>> 5) & 7);
            if (frequency == 15 || channels < 1 || channels > 2 || frameLength < headerLength || frameLength > 8191)
                throw new MediaException("Invalid ADTS frame header");
            if (headerLength == 9) readFully(new byte[2], 0, 2, "ADTS CRC");
            if (decoderInfo == null) {
                decoderInfo = new AdtsAudioDecoderInfo(
                    Profile.forInt(profile + 1),
                    SampleFrequency.forInt(frequency),
                    ChannelConfiguration.forInt(channels));
            }
            byte[] payload = new byte[frameLength - headerLength];
            readFully(payload, 0, payload.length, "ADTS frame");
            return payload;
        }

        private void readFully(byte[] b, int offset, int length, String part) throws IOException {
            int total = 0;
            while (total < length) {
                int count = input.read(b, offset + total, length - total);
                if (count < 0) throw new MediaException("Truncated " + part);
                total += count;
            }
        }
    }

    private static final class AdtsAudioDecoderInfo implements AudioDecoderInfo {

        private final Profile profile;
        private final SampleFrequency sampleFrequency;
        private final ChannelConfiguration channelConfiguration;

        private AdtsAudioDecoderInfo(Profile profile, SampleFrequency sampleFrequency,
            ChannelConfiguration channelConfiguration) {
            this.profile = profile;
            this.sampleFrequency = sampleFrequency;
            this.channelConfiguration = channelConfiguration;
        }

        @Override
        public Profile getProfile() {
            return profile;
        }

        @Override
        public SampleFrequency getSampleFrequency() {
            return sampleFrequency;
        }

        @Override
        public ChannelConfiguration getChannelConfiguration() {
            return channelConfiguration;
        }
    }
}
