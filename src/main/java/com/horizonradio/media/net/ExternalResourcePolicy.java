package com.horizonradio.media.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;

/** Rejects external HTTP targets that resolve to non-public network addresses. */
public final class ExternalResourcePolicy {

    public interface HostResolver {

        InetAddress[] resolve(String host) throws IOException;
    }

    private final HostResolver resolver;

    public ExternalResourcePolicy() {
        this(new HostResolver() {

            @Override
            public InetAddress[] resolve(String host) throws IOException {
                return InetAddress.getAllByName(host);
            }
        });
    }

    ExternalResourcePolicy(HostResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("Host resolver is required");
        }
        this.resolver = resolver;
    }

    public URL requirePublicHttpUrl(URL url) throws IOException {
        if (url == null) {
            throw new IOException("External resource URL is required");
        }
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("External resource URL must use HTTP or HTTPS");
        }
        String host = url.getHost();
        if (host == null || host.trim()
            .length() == 0) {
            throw new IOException("External resource URL must include a host");
        }
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new IOException("External resource host did not resolve to an address");
        }
        for (InetAddress address : addresses) {
            if (address == null || isNonPublic(address)) {
                throw new IOException("External resource host resolves to a non-public address");
            }
        }
        return url;
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
