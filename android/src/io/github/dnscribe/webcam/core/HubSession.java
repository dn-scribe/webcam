package io.github.dnscribe.webcam.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.Buffer;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The hub protocol (see docs/SPEC.md) - a native port of the browser app's socket client.
 * Phone -> hub: hello, probe, state, rec_status, snap_meta + 0x02, rec_file_end + 0x03, lib, lib_thumb, lib_file_end + 0x04, preview frames 0x01, error, pong.
 * Hub -> phone: welcome, set, stream, snap, rec, lib_list, lib_get, lib_thumb, lib_delete, get_state, ping.
 */
public class HubSession {
    public interface Listener {
        void onStatus(String state /* off | wait | on */, String text);
        void onWelcome(String cameraName, String hubName);
    }

    public static class Config {
        public String host = "", token = "", name = "camera", appName = "webcam-android", version = "0.0.0", build = "dev";
        public int port = 8765;
        public boolean tls = true, auto = true, allowPublic = false;
    }

    private static final int CHUNK = 256 * 1024;

    private final Device dev;
    private final Listener lst;
    private final OkHttpClient client = Tls.client();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();   // message handling, in order
    private final ExecutorService xfer = Executors.newCachedThreadPool();         // file transfers (may block on back-pressure)
    private final Random rnd = new Random();

    private volatile WebSocket ws;
    private volatile boolean want, open, welcomed;
    private volatile Config cfg = new Config();
    private int failures;
    private long backoff = 1000;
    private ScheduledFuture<?> retry;
    private String libSig = "";

    public HubSession(Device dev, Listener lst) { this.dev = dev; this.lst = lst; }

    // ---------------------------------------------------------------- lifecycle
    public synchronized void start(Config c) {
        stop();
        cfg = c; want = true; backoff = 1000; failures = 0;
        connect();
    }

    public synchronized void stop() {
        want = false;
        if (retry != null) retry.cancel(false);
        WebSocket w = ws; ws = null; open = welcomed = false;
        if (w != null) { try { w.close(1000, "bye"); } catch (Exception ignored) { } }
        lst.onStatus("off", "offline");
    }

    public boolean isConnected() { return welcomed && ws != null; }
    public int failures() { return failures; }
    public void notifyError(String msg) { sendError(null, null, msg); }

    private synchronized void connect() {
        if (!want) return;
        final Config c = cfg;
        if (c.host == null || c.host.isEmpty()) { lst.onStatus("off", "no hub configured"); return; }
        if (!c.allowPublic && !Net.isPrivate(c.host)) { lst.onStatus("off", "refusing non-LAN address " + c.host); want = false; return; }
        lst.onStatus("wait", "connecting…");
        Request req = new Request.Builder().url((c.tls ? "wss" : "ws") + "://" + Net.urlHost(c.host) + ":" + c.port + "/ws").build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                open = true;
                send(Json.obj("t", "hello", "token", c.token, "name", c.name, "app", c.appName, "proto", 1, "version", c.version));
            }
            @Override public void onMessage(WebSocket w, final String text) {
                worker.submit(new Runnable() { public void run() { try { handle(new JSONObject(text)); } catch (Exception e) { sendError(null, null, String.valueOf(e.getMessage())); } } });
            }
            @Override public void onClosed(WebSocket w, int code, String reason) { dropped(w, code); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) { dropped(w, -1); }
        });
    }

    private synchronized void dropped(WebSocket w, int code) {
        if (w != ws) return;                    // an older socket
        boolean was = welcomed;
        ws = null; open = welcomed = false;
        if (!want) return;
        if (code == 4401) { want = false; lst.onStatus("off", "hub rejected the token"); return; }
        if (code == 4403) { want = false; lst.onStatus("off", "hub only accepts LAN clients"); return; }
        if (!was) failures++;
        if (!cfg.auto) { lst.onStatus("off", "offline"); return; }
        long wait = backoff; backoff = Math.min((long) (backoff * 1.6), 10000);
        lst.onStatus("off", "offline · retry in " + (wait / 1000) + "s" + (failures > 0 ? " (attempt " + failures + ")" : ""));
        retry = sched.schedule(new Runnable() { public void run() { connect(); } }, wait, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- sending
    public boolean send(JSONObject o) {
        WebSocket w = ws;
        return w != null && open && w.send(o.toString());
    }

    private boolean sendBinary(int kind, long id, int idx, byte[] b, int off, int len) {
        WebSocket w = ws;
        if (w == null || !open) return false;
        Buffer buf = new Buffer();
        buf.writeByte(kind);
        if (kind != 1) buf.writeInt((int) id);
        if (kind == 3 || kind == 4) buf.writeInt(idx);
        buf.write(b, off, len);
        return w.send(buf.readByteString());
    }

    /** Preview frame (0x01). Returns false (frame dropped) when disconnected or the socket is backed up. */
    public boolean sendFrame(byte[] jpeg) {
        WebSocket w = ws;
        if (w == null || !welcomed || w.queueSize() > 512 * 1024) return false;
        return sendBinary(1, 0, 0, jpeg, 0, jpeg.length);
    }

    public boolean canSendFrame() { WebSocket w = ws; return w != null && welcomed && w.queueSize() <= 512 * 1024; }

    private void sendError(Long id, Long req, String msg) {
        JSONObject o = Json.obj("t", "error", "msg", msg);
        try { if (id != null) o.put("id", id); if (req != null) o.put("req", req); } catch (Exception ignored) { }
        send(o);
    }

    public void sendState() {
        try { JSONObject s = dev.state(); s.put("t", "state"); send(s); } catch (Exception e) { sendError(null, null, "state: " + e.getMessage()); }
    }

    public void sendRecStatus() {
        try { JSONObject s = dev.recStatus(); s.put("t", "rec_status"); send(s); } catch (Exception ignored) { }
    }

    /** Tell the hub the library changed (only when the set of items/names changed, unless forced). */
    public void notifyLibrary(boolean force) {
        if (!isConnected()) return;
        StringBuilder sig = new StringBuilder();
        List<LibraryStore.Item> items = dev.library().list();
        for (LibraryStore.Item i : items) sig.append(i.kind).append(i.id).append(i.name).append('|');
        String s = sig.toString();
        if (!force && s.equals(libSig)) return;
        libSig = s;
        send(Json.obj("t", "lib", "items", dev.library().toJson()));
    }

    /** "→ PC": push a library item to the hub's save folder. */
    public void pushToHub(final LibraryStore.Item it) {
        xfer.submit(new Runnable() { public void run() { try { sendFile(4, rnd.nextInt(Integer.MAX_VALUE), it, true); } catch (Exception ignored) { } } });
    }

    // ---------------------------------------------------------------- receiving
    private void handle(JSONObject m) throws Exception {
        String t = m.optString("t");
        switch (t) {
            case "welcome":
                backoff = 1000; failures = 0; welcomed = true; libSig = "";
                lst.onStatus("on", "hub " + m.optString("hub", cfg.host));
                lst.onWelcome(m.optString("name", cfg.name), m.optString("hub", ""));
                sendState(); sendRecStatus(); notifyLibrary(true);
                break;
            case "ping": send(Json.obj("t", "pong")); break;
            case "get_state": sendState(); break;
            case "set": {
                String err = dev.set(m.optJSONObject("settings") == null ? new JSONObject() : m.optJSONObject("settings"));
                if (err != null) sendError(null, null, err);
                sendState();
                break;
            }
            case "stream":
                dev.stream(m.optBoolean("on"), m.optInt("fps", 0), m.optInt("width", 0), m.optDouble("quality", 0));
                sendState();
                break;
            case "snap": {
                final long id = m.optLong("id") & 0xFFFFFFFFL;
                dev.snap(m.has("quality") ? m.optDouble("quality") : -1, new Device.SnapCallback() {
                    public void done(byte[] jpeg, int w, int h, String err) {
                        if (err != null || jpeg == null) { sendError(id, null, err == null ? "snapshot failed" : err); return; }
                        send(Json.obj("t", "snap_meta", "id", id, "w", w, "h", h, "size", jpeg.length, "mime", "image/jpeg"));
                        sendBinary(2, id, 0, jpeg, 0, jpeg.length);
                    }
                });
                break;
            }
            case "rec": {
                String a = m.optString("action");
                if (a.equals("start")) { String err = dev.recStart(); if (err != null) sendError(m.has("id") ? m.optLong("id") : null, null, err); }
                else if (a.equals("stop")) dev.recStop();
                else if (a.equals("send")) {
                    final LibraryStore.Item it = dev.recItem(m.optLong("id"));
                    if (it == null) sendError(m.optLong("id"), null, "no recording");
                    else xfer.submit(new Runnable() { public void run() { try { sendFile(3, it.id, it, false); } catch (Exception e) { sendError(it.id, null, String.valueOf(e.getMessage())); } } });
                } else if (a.equals("discard")) dev.discardRec(m.optLong("id"));
                sendRecStatus();
                break;
            }
            case "lib_list": send(Json.obj("t", "lib", "req", m.opt("req"), "items", dev.library().toJson())); break;
            case "lib_thumb": {
                LibraryStore.Item it = dev.library().find(m.optString("kind"), m.optLong("id"));
                if (it != null) {
                    String d = dev.library().thumbDataUrl(it);
                    JSONObject o = Json.obj("t", "lib_thumb", "kind", it.kind, "id", it.id);
                    o.put("data", d == null ? JSONObject.NULL : d);
                    send(o);
                }
                break;
            }
            case "lib_get": {
                final long req = m.optLong("req");
                final LibraryStore.Item it = dev.library().find(m.optString("kind"), m.optLong("id"));
                if (it == null) { sendError(null, req, "not found"); break; }
                xfer.submit(new Runnable() { public void run() { try { sendFile(4, req, it, false); } catch (Exception e) { sendError(null, req, String.valueOf(e.getMessage())); } } });
                break;
            }
            case "lib_delete": {
                JSONArray a = m.optJSONArray("items");
                if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject x = a.getJSONObject(i); dev.library().delete(x.optString("kind"), x.optLong("id")); }
                notifyLibrary(true);
                break;
            }
            default: break;   // unknown messages are ignored (additive protocol)
        }
    }

    /** Stream a file as [kind][id u32][idx u32][bytes...] chunks, then the matching *_file_end message. */
    private void sendFile(int kind, long id, LibraryStore.Item it, boolean push) throws IOException, InterruptedException {
        File f = it.file;
        byte[] buf = new byte[CHUNK];
        int idx = 0; long total = 0, n;
        try (FileInputStream in = new FileInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                WebSocket w;
                while ((w = ws) != null && w.queueSize() > 2L * CHUNK) Thread.sleep(20);
                if (w == null) throw new IOException("disconnected");
                sendBinary(kind, id, idx++, buf, 0, r);
                total += r;
            }
        }
        if (kind == 3) send(Json.obj("t", "rec_file_end", "id", id, "size", total, "mime", it.type, "chunks", idx, "name", it.name));
        else send(Json.obj("t", "lib_file_end", "req", id, "push", push, "kind", it.kind, "id", it.id, "name", it.name, "mime", it.type, "size", total, "chunks", idx));
    }

    public void shutdown() { stop(); sched.shutdownNow(); worker.shutdownNow(); xfer.shutdownNow(); }
}
