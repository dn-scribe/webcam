package io.github.dnscribe.webcam.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Net {
    private Net() {}

    /** RFC1918, link-local, 100.64/10 (CGNAT/Tailscale), loopback, *.local, localhost. */
    public static boolean isPrivate(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".local")) return true;
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] v = new int[4];
        try { for (int i = 0; i < 4; i++) v[i] = Integer.parseInt(p[i]); } catch (NumberFormatException e) { return false; }
        return v[0] == 10 || v[0] == 127 || (v[0] == 192 && v[1] == 168) || (v[0] == 172 && v[1] >= 16 && v[1] <= 31)
                || (v[0] == 169 && v[1] == 254) || (v[0] == 100 && v[1] >= 64 && v[1] <= 127);
    }

    /** "a.b.c." prefixes of this device's private IPv4 addresses (for /24 scanning). */
    public static List<String> localPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (!isPrivate(ip)) continue;
                    String pre = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!out.contains(pre)) out.add(pre);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Wrap IPv6 literals for URLs. */
    public static String urlHost(String h) { return h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h; }
}
