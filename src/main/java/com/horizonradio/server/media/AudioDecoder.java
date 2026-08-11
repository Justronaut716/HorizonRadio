package com.horizonradio.server.media;

import java.io.IOException;
import java.io.InputStream;

/** Streams one compressed or containerized input into PCM frames. */
public interface AudioDecoder {

    void decode(InputStream input, PcmSink sink) throws IOException;
}
