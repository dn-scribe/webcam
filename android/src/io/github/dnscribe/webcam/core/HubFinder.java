package io.github.dnscribe.webcam.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

/** Looks for hubs on the LAN: sends {t:"probe"} to ws(s)://ip:port/ws and reads the {t:"hub", name, ...} reply. */
public final class HubFinder {
    public static final class Hub {
        public String host, name; public int port; public boolean tls, tokenRequired; public int cameras;
        @Override public String toString() { return name + " @ " + host + ":" + port + (tls ? "" : " (plain)") + (tokenRequired ? " (token)" : ""); }
    }
    public interface Progress { void update(int done, int total); }

    private final OkHttpClient client = Tls.client();

    /** Probe one host; null if no hub answers within timeoutMs. */
    public Hub probe(final String host, final int port, boolean tls, long timeoutMs) {
        if (!Net.isPrivate(host)) return null;
        final Hub[] res = new Hub[1];
        final CountDownLatch done = new CountDownLatch(1);
        OkHttpClient c = client.newBuilder().connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).build();
        final WebSocket ws = c.newWebSocket(new Request.Builder().url((tls ? "wss" : "ws") + "://" + Net.urlHost(host) + ":" + port + "/ws").build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) { w.send("{\"t\":\"probe\"}"); }
            @Override public void onMessage(WebSocket w, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    if ("hub".equals(m.optString("t"))) {
                        Hub h = new Hub(); h.host = host; h.port = port; h.tls = tls; h.name = m.optString("name", "hub");
                        h.tokenRequired = m.optBoolean("tokenRequired"); h.cameras = m.optInt("cameras"); res[0] = h;
                    }
                } catch (Exception ignored) { }
                done.countDown();
            }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) { done.countDown(); }
            @Override public void onClosed(WebSocket w, int code, String reason) { done.countDown(); }
        });
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        ws.cancel();
        return res[0];
    }

    /** Scan a.b.c.1-254 for each prefix with a worker pool. */
    public List<Hub> scan(List<String> prefixes, final int port, final boolean tls, final long timeoutMs, int concurrency, final Progress progress) {
        final List<String> hosts = new ArrayList<>();
        for (String p : prefixes) for (int i = 1; i <= 254; i++) hosts.add(p + i);
        return scanHosts(hosts, port, tls, timeoutMs, concurrency, progress);
    }

    /** Scan with the preferred TLS mode first, then the other one (a hub started with --no-tls speaks plain ws://). */
    public List<Hub> scanAuto(List<String> prefixes, int port, boolean preferTls, long timeoutMs, int concurrency, Progress progress) {
        List<Hub> r = scan(prefixes, port, preferTls, timeoutMs, concurrency, progress);
        return r.isEmpty() ? scan(prefixes, port, !preferTls, timeoutMs, concurrency, progress) : r;
    }

    public List<Hub> scanHosts(final List<String> hosts, final int port, final boolean tls, final long timeoutMs, int concurrency, final Progress progress) {
        final List<Hub> found = Collections.synchronizedList(new ArrayList<Hub>());
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(concurrency, hosts.size() == 0 ? 1 : hosts.size())));
        for (int t = 0; t < Math.max(1, Math.min(concurrency, hosts.size())); t++) pool.submit(new Runnable() {
            public void run() {
                int i;
                while ((i = next.getAndIncrement()) < hosts.size()) {
                    Hub h = probe(hosts.get(i), port, tls, timeoutMs);
                    if (h != null) found.add(h);
                    if (progress != null) progress.update(done.incrementAndGet(), hosts.size());
                }
            }
        });
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }
        return new ArrayList<>(found);
    }

    /** "office", "off*", substring - same rules as the web app and Python hub. */
    public static boolean nameMatch(String name, String pat) {
        if (pat == null || pat.isEmpty()) return true;
        String n = name == null ? "" : name.toLowerCase(), q = pat.toLowerCase();
        if (n.equals(q)) return true;
        if (q.contains("*") || q.contains("?")) {
            StringBuilder re = new StringBuilder("^");
            for (char c : q.toCharArray()) re.append(c == '*' ? ".*" : c == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(c)));
            return n.matches(re.append("$").toString());
        }
        return n.contains(q);
    }
}
