package com.horizonradio.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaErrorMessageTest {

    @Test
    public void unwrapsRateLimitsAndDistinguishesAccessBlocks() {
        assertEquals(
            "YouTube rate limit - please try again later.",
            MediaErrorMessage
                .describe(new java.util.concurrent.CompletionException(new java.io.IOException("HTTP 429")), "Failed"));
        assertEquals(
            "YouTube blocked the request - please try again later.",
            MediaErrorMessage.describe(new java.io.IOException("HTTP 403 Forbidden"), "Failed"));
    }

    @Test
    public void classifiesNetworkErrorsWithoutExposingExceptionDetails() {
        assertEquals(
            "Connection timed out - please try again.",
            MediaErrorMessage.describe(new java.net.SocketTimeoutException("private URL"), "Failed"));
        assertEquals("Failed", MediaErrorMessage.describe(new RuntimeException("secret data"), "Failed"));
        assertEquals("Failed", MediaErrorMessage.describe(null, "Failed"));
    }

    @Test
    public void errorCanBeDismissed() {
        HorizonRadioClient.clearCache();
        HorizonRadioClient.showMediaError(new java.io.IOException("HTTP 429"), "Failed");
        assertEquals("YouTube rate limit - please try again later.", HorizonRadioClient.mediaStatusMessage());
        HorizonRadioClient.dismissMediaError();
        assertEquals("", HorizonRadioClient.mediaStatusMessage());
    }
}
