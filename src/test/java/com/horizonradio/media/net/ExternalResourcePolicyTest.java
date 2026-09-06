package com.horizonradio.media.net;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;

import org.junit.Test;

public class ExternalResourcePolicyTest {

    @Test
    public void rejectsNullResolver() {
        try {
            new ExternalResourcePolicy(null);
            fail("expected missing resolver rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("resolver"));
        }
    }

    @Test
    public void rejectsNullUrl() throws Exception {
        assertRejected(policyFor("93.184.216.34"), null, "required");
    }

    @Test
    public void rejectsUnsupportedScheme() throws Exception {
        assertRejected(policyFor("93.184.216.34"), new URL("file:///tmp/radio"), "HTTP");
    }

    @Test
    public void rejectsMissingHost() throws Exception {
        assertRejected(policyFor("93.184.216.34"), new URL("http:/stream"), "host");
    }

    @Test
    public void rejectsEmptyDnsResult() throws Exception {
        assertRejected(
            new ExternalResourcePolicy(host -> new InetAddress[0]),
            new URL("https://radio.example/stream"),
            "resolve");
    }

    @Test
    public void rejectsHostWhenAnyResolvedAddressIsPrivate() throws Exception {
        assertRejected(
            policyFor("93.184.216.34", "192.168.1.10"),
            new URL("https://radio.example/stream"),
            "non-public");
    }

    @Test
    public void rejectsIpv4Loopback() throws Exception {
        assertAddressRejected("127.0.0.1");
    }

    @Test
    public void rejectsIpv4Unspecified() throws Exception {
        assertAddressRejected("0.0.0.0");
    }

    @Test
    public void rejectsIpv4LinkLocal() throws Exception {
        assertAddressRejected("169.254.1.1");
    }

    @Test
    public void rejectsIpv4PrivateTenNetwork() throws Exception {
        assertAddressRejected("10.1.2.3");
    }

    @Test
    public void rejectsIpv4Private172Network() throws Exception {
        assertAddressRejected("172.16.1.2");
    }

    @Test
    public void rejectsIpv4Private192Network() throws Exception {
        assertAddressRejected("192.168.1.2");
    }

    @Test
    public void rejectsIpv4Multicast() throws Exception {
        assertAddressRejected("224.0.0.1");
    }

    @Test
    public void rejectsIpv6Loopback() throws Exception {
        assertAddressRejected("::1");
    }

    @Test
    public void rejectsIpv6Unspecified() throws Exception {
        assertAddressRejected("::");
    }

    @Test
    public void rejectsIpv6LinkLocal() throws Exception {
        assertAddressRejected("fe80::1");
    }

    @Test
    public void rejectsIpv6UniqueLocalFcNetwork() throws Exception {
        assertAddressRejected("fc00::1");
    }

    @Test
    public void rejectsIpv6UniqueLocalFdNetwork() throws Exception {
        assertAddressRejected("fd00::1");
    }

    @Test
    public void rejectsIpv6Multicast() throws Exception {
        assertAddressRejected("ff02::1");
    }

    @Test
    public void allowsPublicIpv4() throws Exception {
        assertAllowed("93.184.216.34");
    }

    @Test
    public void allowsPublicIpv6() throws Exception {
        assertAllowed("2606:4700:4700::1111");
    }

    private static void assertAddressRejected(String address) throws Exception {
        assertRejected(policyFor(address), new URL("https://radio.example/stream"), "non-public");
    }

    private static void assertAllowed(String address) throws Exception {
        ExternalResourcePolicy policy = policyFor(address);
        URL url = new URL("https://radio.example/stream");
        assertSame(url, policy.requirePublicHttpUrl(url));
    }

    private static ExternalResourcePolicy policyFor(String... addresses) throws Exception {
        final InetAddress[] resolved = new InetAddress[addresses.length];
        for (int index = 0; index < addresses.length; index++) {
            resolved[index] = InetAddress.getByName(addresses[index]);
        }
        return new ExternalResourcePolicy(host -> resolved);
    }

    private static void assertRejected(ExternalResourcePolicy policy, URL url, String message) throws Exception {
        try {
            policy.requirePublicHttpUrl(url);
            fail("expected URL rejection");
        } catch (IOException expected) {
            assertTrue(
                "unexpected rejection message: " + expected.getMessage(),
                expected.getMessage()
                    .contains(message));
        }
    }
}
