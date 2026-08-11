package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import org.junit.Test;

public class AacAudioDecoderTest {

    private static final byte[] HE_AAC_ADTS = Base64.getDecoder().decode(
        "//FcgDABkCEbVTiK1QSxQVFQJhoRhCEqVbIzSoTCyJUSoq0igGcpz+DmBUeBKU/Sf+UUTMmCnJiWShGBj6yHOZdP/BBS1oXZliEGb1yIxpjadB0lJkiXsWwq"
            + "BDBHZDEbeavoDKA+F3XBQcz70vCJfrmBcCAif2r9lxH758Ey2Qxoz29lhn+xyWoxF7KdY86GBBkOumhrgG2FhHDOyAzYZN5xTLzOhTScFwS2Jol1vNjNQl7h"
            + "ZK8RY7KAEgCEAMAKCFs2mCuveBQAFVJKmvGXScmN4qc9UqU7la/cS0yQaZBagH7UWU953iVREN3aNd06qbEJ3ahKw1e+6CpuCdDlmRgVxvqMx7ZeRAzvv5Fl"
            + "ZA8iO+hHYWVIGGIEBdMrOy9MzToqJfZUGIgGIwcjvi9lLmXErEiosGcituvv+h/hoyMhWpq55mGNpTeuWvZpcXOGph29s7f0br3lwKAgAGeJDFJPRGbglQTB"
            + "MG8G0BCqih4cl878/+wHkrPN5/+YAADg//FcgC7BjCErVTWaEIKCIMiooiMJAqqkyLZeaqhLSQQlVIiVUAQRAgdn9GGv0F2/vGvhCCc/EmhcpQ/wOACpHXhW"
            + "N9Rp6m1jpmBFrto+4gG00Yz+5wzYNzZZT38zgMpQ1uItzbhnYlqWpuoHlFUGNX3R36AmusCXVwhTVgtY0hOJrUaFokFw8DkrARulMEpaFd8IR1xrmQUWfyph"
            + "mla3rdeYKMZNLsCGQQjIDqsrc3jhn0WbdQQUr6MG53Vq9JtnYLu9xjLZddcewCASpacqiaia4hcUkcnMGR+CAcDYiM7yqqHWRvDEQi/UyGy2Ez/eVzBfCy3k"
            + "IZlsHVELaQSURk0NBioGOXPFKuUmarIq7iIqSoMeFcdWb5n2BQQIGrtEDsvsQHCKVEAG+4qyu95goMb5reEgTlAOEIQNsqIIkCxenQ6N5qnhWl1FImz0gLtq"
            + "sBEDeJ79gJ4MYqooipfb9/9E9cvq//YRUfu/f/T9+/e8zBAEAAc=");

    @Test
    public void decodesImplicitSbrAacUsingAdtsHeaderInfo() throws Exception {
        RecordingSink sink = new RecordingSink();
        try {
            new AacAudioDecoder().decode(new ByteArrayInputStream(HE_AAC_ADTS), sink);
        } catch (IOException exception) {
            fail("HE-AAC ADTS stream should decode continuously: " + exception.getMessage());
        }

        assertEquals(16_384, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    private static final class RecordingSink implements PcmSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int finishCalls;
        private int abortCalls;

        @Override
        public void write(byte[] data, int offset, int length) {
            bytes.write(data, offset, length);
        }

        @Override
        public void finish() {
            finishCalls++;
        }

        @Override
        public void abort() {
            abortCalls++;
        }
    }
}
