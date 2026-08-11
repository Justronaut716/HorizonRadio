package com.horizonradio.server.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.MP4InputStream;
import net.sourceforge.jaad.mp4.api.AudioTrack;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;
import net.sourceforge.jaad.mp4.api.Type;

/** Bounded progressive in-file AAC/ISO-BMFF adapter using JAAD's public MP4 API. */
public final class M4aAacDecoder implements AudioDecoder {

    @Override
    public void decode(InputStream input, PcmSink sink) throws IOException {
        ResamplingPcmSink pcm = null;
        boolean finished = false;
        try {
            byte[] media = IsoBmffPreflight.readAndValidate(input);
            MP4InputStream mp4 = MP4InputStream.open(new ByteArrayInputStream(media));
            MP4Container container = new MP4Container(mp4);
            Movie movie = container.getMovie();
            if (movie.getProtections() != null && !movie.getProtections().isEmpty()) {
                throw new MediaException("Protected M4A is unsupported");
            }
            List<Track> tracks = movie.getTracks(Type.AUDIO);
            if (tracks.size() != 1 || !(tracks.get(0) instanceof AudioTrack)) {
                throw new MediaException("M4A must contain exactly one AAC audio track");
            }
            AudioTrack track = (AudioTrack) tracks.get(0);
            if (!track.isInFile() || track.getLocation() != null || track.getProtection() != null
                || track.getDecoderSpecificInfo() == null || track.getDecoderSpecificInfo().getData() == null) {
                throw new MediaException("External, protected, or unconfigured M4A track is unsupported");
            }
            if (!"AAC".equals(String.valueOf(track.getCodec()))) {
                throw new MediaException("Only AAC M4A tracks are supported");
            }
            Decoder decoder = Decoder.create(track.getDecoderSpecificInfo().getData());
            int frames = 0;
            while (track.hasMoreFrames()) {
                if (++frames > IsoBmffPreflight.MAX_FRAMES) throw new MediaException("M4A frame count exceeds limit");
                Frame frame = track.readNextFrame();
                byte[] compressed = frame.getData();
                if (compressed == null || compressed.length == 0 || compressed.length > IsoBmffPreflight.MAX_FRAME_BYTES) {
                    throw new MediaException("Invalid M4A AAC frame");
                }
                SampleBuffer samples = new SampleBuffer();
                decoder.decodeFrame(compressed, samples);
                byte[] data = samples.getData();
                if (data == null || data.length == 0) throw new MediaException("M4A decoder produced no PCM frame");
                if (pcm == null) pcm = new ResamplingPcmSink(new PcmFormat(
                    samples.getSampleRate(), samples.getChannels(), samples.getBitsPerSample(), true, !samples.isBigEndian()), sink);
                pcm.write(data, 0, data.length);
            }
            if (frames == 0 || pcm == null) throw new MediaException("M4A contains no AAC frames");
            pcm.finish();
            finished = true;
        } catch (MediaException exception) {
            abort(pcm, sink, finished, exception);
            throw exception;
        } catch (Exception exception) {
            MediaException wrapped = new MediaException("Unable to decode M4A AAC audio", exception);
            abort(pcm, sink, finished, wrapped);
            throw wrapped;
        }
    }

    private static void abort(PcmSink pcm, PcmSink sink, boolean finished, IOException failure) {
        if (finished) return;
        try { if (pcm == null) sink.abort(); else pcm.abort(); } catch (IOException abortFailure) { failure.addSuppressed(abortFailure); }
    }
}
