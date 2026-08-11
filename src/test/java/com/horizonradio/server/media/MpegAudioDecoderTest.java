package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import org.junit.Test;

public class MpegAudioDecoderTest {

    /** Five consecutive frames from a live MPEG stream with a frame reservoir. */
    private static final byte[] LIVE_MP3 = Base64.getDecoder()
        .decode(
            "//uyZN+ABLRc2PMsM+BkqzvPYMV5GY2zWI0wu0HJrGz49gzxwxR+ggRJP1FBAjV0ETmRhHfMhjRX64MsWFTZJdnUe7av8vpjBPXmZVAOOiAZIHo0qjC5MsiPyyzyASDq+in4uCFqCTYJ55FSYmnLuhfIYgiDVztJUd4nj+EB9GM+0qsNcieUWf2J1/h589zs"
                + "hhQ1QOjomSuutIOnfqtPITdjTqRTAChjApkT5OTSVQQmQDhwRO4wwBPlQExAMYAhAUuywRRgcAR5cLwsVHAugla75ugDIFx4"
                + "bLYt2XLKFepFsTWEUrZQpay5y0eSZVC+bJLcueN8nfaw0R31BI1Bq0GGx5rOEHOG6DotYlbPmasFcp/WLOS5TkXWSOvF5OeS"
                + "P5jaCHoqn9419K0aRNSrWoeWM16JdJ2eMr5YMFsmdEpu4jYy+44vagiXV5UcFRAKw4iMUDwiPrG17CdZTGYKQxsOtKiufuF4"
                + "qtGp4YnSeFcJANCK+PZuOCg8KQkgzHyBEfmETEL6xDTy7wj98gGmXQzkBAbWRNGV7k11U637/cTH+N95vVZ9LpWwXH0+s6z7"
                + "4xWR1pJ+uMxsFxiUCHpm7GU6IIC00+FsrU9Yc1cz0LKxNAjrM6fYUL9CfYjJCMg5cUULOQkNlErP0hBKXFDJ85X9n9JKilLu"
                + "6FSsLqLGdEmMULJVBdYMYgwrC9EItZeL4u5MKwqBoMJIrlpVhwDFYDyw+sepFyy5NCKFeWQMCEVTEokGCkyRNInUpjElOSrk"
                + "qZKlaNnZQZ0RMt1hxo+cLiIFSkJCn7q6pwfRpVHrHcg0tCD/NmO+5x/NUoRvxDDO38mh//uyZOoAB3No2WtYY/BmidtsPCPGF"
                + "fWjb8yxMMFuIDA8J4kd8Njv8tpKShtlSEWLxZWUruP9z9E5kVZupETKJZOxrVRGepFeJxaGUkV/7KvSz5VBMEdSisg1iciB"
                + "cuwoYU4hnkiw9RjcJIdz+Pp3ztJKdT1Ga01GPvy0R3XFOdlK5BBkLKzqQEfp3r5GP/1MnVd1llyRoH7Un5kflBAb4/Cu/Vve"
                + "IYt9jt0V1f/n69cEU0lUVBxlW5CLiA04Mba2aQaQpCUAAXpTTRRg9c6SrE090axQJl7LFcFgKymRSBAZQHywSjlXi/rwrzir"
                + "i5NEeMn1cWwUaOuKq2Baw2le+dV6QyfUNONqAxc0uO+ZaJVmNr14zL5siCgWlOeabMMeUlZZ+rw15OfpvtTBtq+UkagB9B+j"
                + "S9PzP9n357M9jaw5KSmY3IjwZzY6PWEFYBogRAIgIAQAMvOrleFAAqubr2YofW1fHn2koFG+RgVD2lXiM1QaHwREp+RmxyEl"
                + "KENP/kTI0hyhI5/5ERIX04J9M/+cLKGMhevc05bz/++fChHryWFY1v87/c4XcnWwFAGgtFFGN3+wgjiOK4klFUXS7KTAquDk"
                + "14gp6ATOMIj6QvurxCtL1YRskeh6DJHAjODoXrUMgPFQTjklqjgLA/lVYkki7RALZ8PpTLS5Do0sVFQnNHeLPZqvVWQ6681y"
                + "lz1bK2xKWla8S+7aVlM7uOWm5V73yOa70aiCOWrkKH4kHCTzdMPXDhhaqKCuer804wnZKMW/dmllfJe6iLf3tGUhib+i+wEl"
                + "BIDfyPxWDTk8EWWVq3VUjUjAe+DwRUjGuiZ2s4La//uwZOGABOxh23MsNFJqK2uPPMOSVN1/Z4yw0cobMm989Jnd+GCdA2hO"
                + "GA4SyEyCw6tB6nJ2iw0lq06DIpbRni5TKz07AR/7I2VB6YETTVFuao9O3BpjFXanJmOnv7f38+TnnXxp9fy1u3n97/1/H1/3"
                + "/Z9SNsqO96ve/I+tSaIi75+lcx9ot638VulAaOqx/2wuL2To1QEmSCIBEBAHBvaPphDpBVhIQFRFgIOilRmGmShL40Xpi6mC"
                + "dT1WG8cOJNUicRHJ0llx9lO4QjdYQX9ChnCQfrD1VAsKq9ppqEPGRIM2iHZklPKF8bC2W/tPr3e19aUdOpZg+1tmmNrbO71d"
                + "l6/Vh+EOlhOcvFt+eRdA2Uw8wdSkrETKvwlMWLIgugeDh9TKoPs8csDBueUODxcHlphhxmapCRGalOR1Xln4gzq8oxqTLbST"
                + "2uKmbeIQnUHyqAQuJMXj6vo/1pYAhXsDgBiUDkPTvElrI7g6EkMPvnMyREtSXZIlsiP3SAw4gmqi9KJBnSMHKDXDaGf0gWuT"
                + "qdLnniYRs3Ibr8BlwM2I6s4SX7l9B3deXPoryPKH5kRjIBwYIa9iCUCSGIPdla1RGjQzMhkkEmS6cDg5aTycdMClgYRnKIgN"
                + "SdIMdDLol21FgYFpLpVmk26aGW6RG7pTQCPKQzKTWJxYhKmmjtvF9vPP1CnzYtFAnM6rTG9dr0bT3SttrF5XpiaZrScoaRP6"
                + "wmjs21kM/+06zsGNZNCWS0JatPUdS+MpsQ0KP75rSleIsX4Li4Mk9NsYefHqXjalRXYyYSYnsQ2uOLfC5vlQ+JJRlbowSDxE"
                + "w7IpuONky2r/+7Jk7YAFUWJZ8yxE8oCL669hI3xUwYlt7DDTycWmLrzzCmiFQvkHNEuJvHaTJzTJ+G4fp4BenwhpossF0sMc"
                + "Sfsn0l0XTCDgYmUDOWLImNtu7LwVREd3KhL5hV4pp7Y6swxyu92SqIaZWf+jvf+lDO6p+amnwzux47WhBoyJXBNEkfSxKFrU"
                + "maPLJlp6d1Y0UmwVSWUyMlQCVQQQtWTCRwjyTCB8CTK3I6inytSQmcbCgBGqvSxboFMJh2BRRkufjFyqO2525lMwgA4gSFUk"
                + "akK6FWUuk26dXkZUoPIQrsjepJb8iib2vXGXvFWKhyUPB4P3OOLaWW5HcIQNoaSah8l1BViFZwqC0Wscc41oGI6OaONfWIMy"
                + "CWrgvf1Jcq/ShvHrUD7AjGKYzMwAygVJY53lMvszpkGKQgIODZT59HU7Qb5QD3YLPWXIyN8xFwk2yNhTdVeVXnWBChKwQPue"
                + "uthdLM4xhQ6n6oCr6TG/ad8hlzhF38i3EI1woqiShnDk996ruPShpO66TWRVbFmVR38fZme/VTDCjIkjHUuqkCRGS6as4kIZ"
                + "55EA9gVgQVNwAzjxZAwAANIpYVlOOX3Fs0ZTlH4oQwzxIIS1SJ4vDfCTt4Bb58Q5oRJ2COVETSfbpzcjRD9W4T0V4kIJAsDH"
                + "FZ7m4Es0TJZEK+sLkgfGFB1EiFYgGwlToB1KMlbl4RhA1BSSJJiXQUNTNRMFmVSdh6tldeMMfuxKoRdNCRAwXQ8+SnSIeUbA"
                + "lt7llJWjY2EVERJRyZCiy2hvdaWhaSf9F4evSGBf9i98kwGnJ3dyIAAHw96l/qhLYB6EhZ+XsSm1MIz/+7Jk9AAE2mXdewlE"
                + "eHPpe189I3RX/aNl7L0vQbWnbPmBimE1FTsiEs5S9pjkvPYxp5ekHQQWSdkKmFOt0Nb/TMmGaKs9PTvqu81K+9F3yPZB5m4R"
                + "0RNnOyzhRas3O5y5r9XVXcE2OLn//kwbWQt5JzX83aul1r0KIyVJmFYSQUinT+DMV4wuwMbFx1ZnRuHDsIABN0gOKDr0/13l"
                + "swUIutrTIHASEhukZ03Ku80UsKOQ5p9Um5+KP/AhUDDgJ6JLhkT0pI0h1+qgxsqspgQVZby2rmqd22stdurXgOp29SSPnGzU"
                + "hr48yviTG45RzSms4QnK5sueovshswq0hMnWWV66OAfjBGHxeWFscUlkp+hoDcLiRfU3mBxQdKJISg0SFyGVJeKpBciR1Y3q"
                + "qqe5BAZKbuP/Ul++vWuFJt6zsdL3mZYfrXoLtYsCIiaWVQCARBUu8MExmxPR0H4BO1CJIgA/zBFLiF1Y2uE+bGBVJsWj3dgD"
                + "FBKyI5SMedtqNsc2rc18h3vVGPSrSWqT1qeuPd6Ld6F1EsB2XIL9K5n5DRN4DECYYUwSP50v8jNnN8YiDU0hBg0M0CBTKwEA"
                + "oCAa/0ASgA3gJApIREgFfS6FiZQPb9RMskWxiTkEQCHYy2kei8Yjkgd+Shcf2TpFroh6MESaJzBQGbhDg+Ekj6Lrfo3hZz/F"
                + "zQRC0PIehDYRtfgAVDzZRNF9Pt20mS9AwJJ9Qlfy+IThPmUzneGqUh+DNOVtZ1WvuL2Azxm1LJ50heHpdoUZFtRuHS2TqhQ"
                + "eESUrF2fsKEYkE7iWR2VUwkJZGhndKhRXVr54yPCgcymbmiVFgKU=");

    @Test
    public void keepsMpegBitReservoirAcrossConsecutiveLiveFrames() throws Exception {
        RecordingSink sink = new RecordingSink();

        new MpegAudioDecoder().decode(new ByteArrayInputStream(LIVE_MP3), sink);

        // The capture starts mid-stream, so its first frame references an
        // earlier reservoir frame and contributes no complete PCM by itself.
        assertEquals(18432, sink.bytes.size());
        assertEquals(1, sink.finishCalls);
        assertEquals(0, sink.abortCalls);
    }

    private static final class RecordingSink implements PcmSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int finishCalls;
        private int abortCalls;

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
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
